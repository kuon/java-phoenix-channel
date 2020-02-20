package ch.kuon.phoenix

import java.net.URI
import java.util.Timer
import com.github.openjson.JSONObject
import com.github.openjson.JSONArray
import com.neovisionaries.ws.client.*
import org.apache.http.client.utils.URIBuilder
import kotlin.math.min
import kotlin.concurrent.schedule


internal typealias Ref = Int
internal typealias Callback = () -> Unit
internal typealias OpenCallback = () -> Unit
internal typealias CloseCallback = (Int, String) -> Unit
internal typealias ErrorCallback = (String) -> Unit
internal typealias MessageCallback = (Socket.Message) -> Unit


/**
 * Socket main constructor.
 *
 * This class is a thin layer above websockets. This layer provides
 * compatibility with Phoenix Sockets. It handle heartbeat, serialization and
 * provides a simple event based API.
 *
 * When using Phoenix, you will usually not push messages directly to sockets
 * but use [Channels][Channel] instead.
 *
 * The url can use either the `ws://` or `wss://` scheme.
 *
 * @property url The full web socket URL
 * @property opts An `Socket.Option` object. If ommited default configuration is
 *  used.
 */
class Socket(
    var url: String,
    var opts: Socket.Options = Socket.Options()
): WebSocketAdapter() {


    /**
     * Enum representing socket state
     */
    enum class State(var state: String) {
        CONNECTING("connecting"),
        OPEN("open"),
        CLOSING("closing"),
        CLOSED("closed"),
        CREATED("created"),
        UNKNOWN("unknown")
    }

    /**
     * Socket options.
     *
     * Build the object with default constructor `Options()` and
     * customize it afterward.
     *
     * For interval function, look at [ExTimer.timerCalc].
     *
     * Note: The logger callback might be called on other thread.
     */
    class Options {
        /**
         * Timeout in miliseconds
         */
        var timeout = 10000

        /**
         * Heartbeat interval in miliseconds
         */
        var heartbeatIntervalMs: Long = 30000

        /**
         * Rejoin interval functions (must be thread safe)
         */
        var rejoinAfterMs: ((Int) -> Int) = {
            tries -> min(tries * tries * 1000, 10000)
        }

        /**
         * Reconnect interval functions (must be thread safe)
         */
        var reconnectAfterMs: ((Int) -> Int) = {
            tries -> min(tries * tries * tries * 5, 10000)
        }

        /**
         * Logger function (must be thread safe)
         *
         * Set to null to disable logging.
         */
        var logger: ((String, String) -> Unit)? = { tag, msg ->
            System.out.println(tag + ": " + msg)
        }

        /**
         * A list of key/value sent with the conection.
         *
         * Those params are serialized to url parameters and appended to the
         * connection url.
         *
         * For example passing `mapOf("foo" to "bar")` will happend `&foo=bar`
         * to the URL.
         *
         * `toString()` is automaticall called on the values.
         */
        var params: HashMap<String, Any>? = null
    }

    /**
     * A raw socket message.
     *
     * This object is usually not created directly and Channel.Message
     * is used instead.
     *
     * @property joinRef A join_ref
     * @property ref The ref
     * @property topic The topic
     * @property event The event name
     * @property payload A payload object
     */
    class Message(
        var joinRef: Int? = null,
        var ref: Int? = null,
        var topic: String,
        var event: String,
        var payload: JSONObject = JSONObject()
    ) {
        override fun toString(): String {
            return String.format(
                "join_ref: %d, " +
                "ref: %d, " +
                "topic: %s, " +
                "event: %s, " +
                "payload: %s",
                joinRef,
                ref,
                topic,
                event,
                payload.toString()
            )
        }
    }


    private var openCallbacks = listOf<Pair<Ref, OpenCallback>>()
    private var closeCallbacks = listOf<Pair<Ref, CloseCallback>>()
    private var errorCallbacks = listOf<Pair<Ref, ErrorCallback>>()
    private var messageCallbacks = listOf<Pair<Ref, MessageCallback>>()

    private var channels = listOf<Channel>()
    private var sendBuffer = listOf<Callback>()
    private var ref = 0
    private var closeWasClean = false
    private var unloaded = false
    private val encode = Serializer::encode
    private val decode = Serializer::decode
    private var heartbeatTimer: Timer? = null
    private var pendingHeartbeatRef: Int? = null
    private val reconnectTimer: ExTimer
    private var conn: WebSocket? = null
    private var listener = Listener(this)

    init {
        reconnectTimer = ExTimer({
            log("SOCKET", "Reconnecting:", this)
            teardown({ connect() })
        }, opts.reconnectAfterMs)
    }

    override fun toString(): String {
        return String.format(
            "url: %s, " +
            "state: %s, " +
            "ref: %d, " +
            "socket: %s",
            endPointURL(),
            connectionState().state,
            ref,
            conn.toString()
        )
    }

    /**
     * Returns the full endpoint URL.
     *
     * Phoenix requires `/websocket` to be appended to the URL.
     * `vsn` is set to `2.0.0` for V2serializer
     *
     * @return URI The full endpoint URL.
     */
    fun endPointURL(): URI {
        val uri = URIBuilder(url)

        uri.addParameter("vsn", "2.0.0")

        opts.params?.forEach { (name, value) ->
            uri.addParameter(name, value.toString())
        }

        val segments = uri.getPathSegments()
        segments.add("websocket")
        uri.setPathSegments(segments)

        return uri.build()
    }

    /**
     * Disconnect the socket.
     *
     * Note: The callback might be called on another thread
     *
     * @param callback Optional callback called after the socket is
     *  disconnected.
     * @param code Send a disconnect code to the server
     * @param reason Send a disconnect reason to the server
     */
    fun disconnect(
        callback: (() -> Unit)? = null,
        code: Int? = null,
        reason: String? = null
    ) {
        log("SOCKET", "Disconnecting")
        synchronized(this) {
            closeWasClean = true
            reconnectTimer.reset()
            teardown(callback, code, reason)
        }
    }

    /**
     * Connect the socket to the server
     */
    fun connect() {
        if (conn != null) {
            return
        }

        synchronized(this) {
            closeWasClean = false
            conn = WebSocketFactory()
            .setConnectionTimeout(opts.timeout)
            .createSocket(endPointURL().toString())
            .addListener(listener)
            .addExtension(WebSocketExtension.PERMESSAGE_DEFLATE)
            .setMissingCloseFrameAllowed(false)
            .connectAsynchronously()
        }
    }

    /**
     * Log a message.
     *
     * Override `logger` in [Socket.Option] to change this.
     *
     * Default to `stdout`.
     *
     * @param tag Message tag
     * @param msg Message
     * @param data An object that will be appended to the message,
     *  `toString()` will be called on `data`.
     */
    fun log(tag: String, msg: String, data: Any? = null) {
        if (data != null) {
            opts.logger?.invoke(tag, msg + " <" + data.toString() + ">")
        } else {
            opts.logger?.invoke(tag, msg)
        }
    }

    /**
     * Register an open callback.
     * Multiple callbacks can be registerd.
     *
     * Note: The callback might be called on another thread
     *
     * @param callback A callback function
     * @return A reference to the callback that can be used with the
     * [Socket.off] function.
     */
    fun onOpen(callback: OpenCallback): Ref {
        synchronized(this) {
            val ref = this.makeRef()
            openCallbacks = openCallbacks.plus(Pair(ref, callback))
            return ref
        }
    }

    /**
     * Register an close callback.
     * Multiple callbacks can be registerd.
     *
     * Note: The callback might be called on another thread
     *
     * @param callback A callback function
     * @return A reference to the callback that can be used with the
     * [Socket.off] function.
     */
    fun onClose(callback: CloseCallback): Ref {
        synchronized(this) {
            val ref = this.makeRef()
            closeCallbacks = closeCallbacks.plus(Pair(ref, callback))
            return ref
        }
    }

    /**
     * Register an error callback.
     * Multiple callbacks can be registerd.
     *
     * Note: The callback might be called on another thread
     *
     * @param callback A callback function
     * @return A reference to the callback that can be used with the
     * [Socket.off] function.
     */
    fun onError(callback: ErrorCallback): Ref {
        synchronized(this) {
            val ref = this.makeRef()
            errorCallbacks = errorCallbacks.plus(Pair(ref, callback))
            return ref
        }
    }

    /**
     * Register an on message callback.
     * Multiple callbacks can be registerd.
     *
     * Note: The callback might be called on another thread
     *
     * @param callback A callback function
     * @return A reference to the callback that can be used with the
     * [Socket.off] function.
     */
    fun onMessage(callback: MessageCallback): Ref {
        synchronized(this) {
            val ref = this.makeRef()
            messageCallbacks = messageCallbacks.plus(Pair(ref, callback))
            return ref
        }
    }

    private fun resetHeartbeat() {
        synchronized(this) {
            pendingHeartbeatRef = null
            heartbeatTimer?.cancel()
            heartbeatTimer = Timer()
            heartbeatTimer?.schedule(
                opts.heartbeatIntervalMs,
                opts.heartbeatIntervalMs) {
                sendHeartbeat()
            }
        }
    }

    private fun teardown(
        callback: (Callback)?,
        code: Int? = null,
        reason: String? = null
    ) {
        log("SOCKET", "Teardown", this)
        synchronized(this) {
            val conn = this.conn
            this.conn = null
            if (conn != null) {
                if (code != null) {
                    if (reason != null) {
                        conn.disconnect(code, reason)
                    } else {
                        conn.disconnect(code)
                    }
                } else {
                    conn.disconnect()
                }
            }
            callback?.invoke()
        }
    }

    private fun triggerChanError(cause: String) {
        synchronized(this) {
            channels.forEach { chan ->
                if (!(chan.isErrored() || chan.isLeaving() || chan.isClosed()))
                {
                    chan.trigger(
                        Channel.Event.ERROR.event,
                        Channel.Message.error(cause)
                    )
                }
            }
        }
    }

    /**
     * Returns the current socket [state][Socket.State]
     *
     * @return The current [state][Socket.State]
     */
    fun connectionState(): State {
        when (conn?.getState()) {
            WebSocketState.CLOSED ->
                return State.CLOSED
            WebSocketState.CLOSING ->
                return State.CLOSING
            WebSocketState.CONNECTING ->
                return State.CONNECTING
            WebSocketState.CREATED ->
                return State.CREATED
            WebSocketState.OPEN ->
                return State.OPEN
        }
        return State.UNKNOWN
    }

    /**
     * Returns the connected state (if state is [OPEN][Socket.State.OPEN])
     *
     * @return True if [state][Socket.connectionState]
     *  is [OPEN][Socket.State.OPEN], false otherwise
     */
    fun isConnected(): Boolean {
        return connectionState() == State.OPEN
    }

    internal fun remove(channel: Channel) {
        synchronized(this) {
            off(channel.stateChangeRefs)
            channels = channels.filterNot { c -> c.joinRef() == channel.joinRef() }
        }
    }

    /**
     * Unregister on<Event> listeners
     *
     * @param refs A list of references
     */
    fun off(refs: List<Ref>) {
        synchronized(this) {
            openCallbacks = openCallbacks.filterNot {
                (ref, _) -> refs.contains(ref)
            }
            closeCallbacks = closeCallbacks.filterNot {
                (ref, _) -> refs.contains(ref)
            }
            errorCallbacks = errorCallbacks.filterNot {
                (ref, _) -> refs.contains(ref)
            }
            messageCallbacks = messageCallbacks.filterNot {
                (ref, _) -> refs.contains(ref)
            }
        }
    }

    /**
     * Unregister on<Event> listener
     *
     * @param ref A single reference
     */
    fun off(ref: Ref) {
        off(listOf(ref))
    }

    /**
     * Prefered constructor for channels.
     *
     * Creates a channel and register it with the Socket.
     *
     * @param topic The channel topic
     * @param params Params sent to the server on join
     * @return The created channel
     */
    fun channel(topic: String, params: JSONObject = JSONObject()): Channel {
        synchronized(this) {
            val chan = Channel(topic, params, this)
            channels = channels.plus(chan)
            return chan
        }
    }

    /**
     * Push a message to the socket
     *
     * @param msg A message
     */
    fun push(msg: Message) {
        log("SOCKET", "Pushing message", msg)
        synchronized(this) {
            if (isConnected()) {
                encode(msg) { data -> conn?.sendText(data)}
            } else {
                sendBuffer = sendBuffer.plus {
                    encode(msg) { data -> conn?.sendText(data)}
                }
            }
        }
    }

    internal fun makeRef(): Int {
        synchronized(this) {
            val newRef = ref + 1
            if (newRef < 0) {
                ref = 0
            } else {
                ref = newRef
            }
            return ref
        }
    }

    private fun sendHeartbeat() {
        if (!isConnected()) {
            return
        }
        synchronized(this) {
            if (pendingHeartbeatRef != null) {
                pendingHeartbeatRef = null
                log("SOCKET",
                "Heartbeat timeout, attempting to re-establish connection")
                abnormalClose("heartbeat timeout")
                return
            }
            val ref = makeRef()
            pendingHeartbeatRef = ref
            push(Message(
                topic = "phoenix",
                event = "heartbeat",
                ref = ref
            ))
        }
    }

    private fun abnormalClose(reason: String) {
        synchronized(this) {
            closeWasClean = false
            conn?.disconnect(reason)
        }
    }

    private fun flushSendBuffer() {
        synchronized(this) {
            if (isConnected() && sendBuffer.size > 0) {
                sendBuffer.forEach { callback -> callback() }
                sendBuffer = listOf()
            }
        }
    }

    internal fun handleHeartbeat(msg: Message) {
        synchronized(this) {
            if (msg.ref == pendingHeartbeatRef) {
                pendingHeartbeatRef = null
            }
        }
    }

    internal fun triggerOpenCallbacks() {
        synchronized(this) {
            openCallbacks.forEach { (_, callback) -> callback() }
        }
    }

    internal fun triggerCloseCallbacks(code: Int, reason: String) {
        synchronized(this) {
            closeCallbacks.forEach { (_, callback) -> callback(code, reason) }
        }
    }

    internal fun triggerErrorCallbacks(error: String) {
        synchronized(this) {
            errorCallbacks.forEach { (_, callback) -> callback(error) }
        }
    }

    internal fun triggerMessageCallbacks(msg: Message) {
        synchronized(this) {
            messageCallbacks.forEach { (_, callback) -> callback(msg) }
        }
    }

    internal fun triggerChannels(msg: Message) {
        synchronized(this) {
            channels.forEach { chan ->
                if (chan.isMember(msg)) {
                    chan.trigger(
                        msg.event,
                        Channel.Message.build(msg.payload),
                        msg.ref,
                        msg.joinRef
                    )
                }
            }
        }
    }

    internal fun triggerTextMessage(rawMessage: String) {
        synchronized(this) {
            decode(rawMessage) { msg ->
                log("SOCKET", "Decoded message", msg)

                handleHeartbeat(msg)
                triggerChannels(msg)
                triggerMessageCallbacks(msg)
            }
        }
    }

    internal fun triggerError(error: String) {
        synchronized(this) {
            triggerChanError(error)

            triggerErrorCallbacks(error)
        }
    }

    internal fun triggerConnect() {
        synchronized(this) {
            val rawSocket = conn?.getSocket()
            if (rawSocket != null) {
                rawSocket.setSoTimeout(opts.heartbeatIntervalMs.toInt() + 2000)
                rawSocket.setKeepAlive(true)
            }
            unloaded = false
            closeWasClean = false
            flushSendBuffer()
            reconnectTimer.reset()
            resetHeartbeat()

            triggerOpenCallbacks()
        }
    }

    internal fun triggerDisconnect(code: Int, reason: String) {
        log("SOCKET", "Diconnect code: " + code + ", reason: " + reason, this)
        synchronized(this) {
            triggerChanError(reason)
            heartbeatTimer?.cancel()
            if (!closeWasClean) {
                reconnectTimer.scheduleTimeout()
            }
            triggerCloseCallbacks(code, reason)
        }
    }

    private class Listener(var sock: Socket): WebSocketAdapter() {
        override fun onConnected(
            websocket: WebSocket,
            headers: Map<String,List<String>>
        ) {
            sock.log("RAW_SOCKET", "Connected to", sock.endPointURL())
            sock.triggerConnect()
        }

        override fun onDisconnected(
            websocket: WebSocket,
            serverCloseFrame: WebSocketFrame,
            clientCloseFrame: WebSocketFrame,
            closedByServer: Boolean
        ) {
            sock.log("RAW_SOCKET", "Disconnected")
            var code: Int
            var reason: String


            if (closedByServer) {
                code = serverCloseFrame.getCloseCode()
                reason = "closed by server"
            } else {
                code = clientCloseFrame.getCloseCode()
                reason = "closed by client"
            }

            websocket.removeListener(this)
            sock.triggerDisconnect(code, reason)
        }

        override fun onError(websocket: WebSocket, cause: WebSocketException) {
            sock.log("RAW_SOCKET", "Error", cause)

            sock.triggerError(cause.toString())

            val err = cause.getError()
            if (err == WebSocketError.IO_ERROR_IN_READING
               || err == WebSocketError.IO_ERROR_IN_WRITING
               || err == WebSocketError.NO_MORE_FRAME
               || err == WebSocketError.INSUFFICENT_DATA
               || err == WebSocketError.NOT_IN_CREATED_STATE
               || err == WebSocketError.INTERRUPTED_IN_READING
               || websocket.getState() == WebSocketState.CLOSED
            ) {
                websocket.removeListener(this)
                sock.triggerDisconnect(
                    WebSocketCloseCode.NONE,
                    cause.toString()
                )
            }
        }

        override fun onUnexpectedError(
            websocket: WebSocket,
            cause: WebSocketException
        ) {
            sock.log("RAW_SOCKET", "Unexpected error", cause)

            sock.triggerError(cause.toString())
        }

        override fun onTextMessage(websocket: WebSocket, rawMessage: String) {
            sock.log("RAW_SOCKET", "Message", rawMessage)

            sock.triggerTextMessage(rawMessage)
        }
    }
}


/**
 * Default serializer
 *
 * Phoenix protocol uses json encoded array of the following form:
 *
 * `[join_ref:int, ref:int, topic:string, event:string, payload:object]`
 *
 */
internal object Serializer {
    fun encode(msg: Socket.Message, callback: (String) -> Unit) {
        val payload = arrayOf(
            msg.joinRef,
            msg.ref,
            msg.topic,
            msg.event,
            msg.payload
        )
        callback(JSONArray(payload).toString())
    }

    fun decode(rawPayload: String, callback: (Socket.Message) -> Unit) {
        val a = JSONArray(rawPayload)
        var msg = Socket.Message(
            (if (a.isNull(0)) { null } else { a.getInt(0) }),
            (if (a.isNull(1)) { null } else { a.getInt(1) }),
            a.getString(2),
            a.getString(3),
            a.getJSONObject(4)
        )
        callback(msg)
    }
}
