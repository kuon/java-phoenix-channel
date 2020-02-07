package ch.kuon.phoenix

import org.json.JSONObject
import org.json.JSONArray
import com.neovisionaries.ws.client.*
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.schedule
import java.util.Timer
import kotlin.math.min
import java.util.concurrent.atomic.AtomicInteger
import java.net.URI
import org.apache.http.client.utils.URIBuilder


class Channel(var topic: String, var params: JSONObject, var socket: Socket) {

    enum class State(var state: String) {
        CLOSED("closed"),
        ERRORED("errored"),
        JOINED("joined"),
        JOINING("joining"),
        LEAVING("leaving"),
    }

    enum class Event(var event: String) {
        CLOSE("phx_close"),
        ERROR("phx_error"),
        JOIN("phx_join"),
        REPLY("phx_reply"),
        LEAVE("phx_leave")
    }

    abstract class Message {
        abstract val status: String
        abstract val response: JSONObject

        open fun isError(): Boolean {
            return false
        }

        open fun getError(): String {
            throw Exception("Not an error")
        }

        companion object {
            fun build(status: String, response: JSONObject): Message {
                return Message.Data(status, response)
            }

            fun build(payload: JSONObject): Message {
                if (payload.length() == 0) {
                    return Message.None()
                }
                if (payload.isNull("status") || payload.isNull("response")) {
                    return Message.Data("ok", payload)
                } else {
                    return Message.Data(
                        payload.getString("status"),
                        payload.getJSONObject("response")
                    )
                }
            }

            fun none(): Message {
                return Message.None()
            }

            fun error(error: String): Message {
                return Message.Error(error)
            }

            fun leave(): Message {
                return Message.Leave()
            }
        }

        private class Data(
            override val status: String,
            override val response: JSONObject): Message() {

            override fun toString(): String {
                return String.format(
                    "status: %s, " +
                    "response: %s",
                    status, response.toString()
                )
            }
        }

        private class Error(val errorMsg: String): Message() {
            override val status = "error"
            override val response = JSONObject()


            override fun isError(): Boolean {
                return true
            }

            override fun getError(): String {
                return errorMsg
            }

            override fun toString(): String {
                return String.format(
                    "error: %s",
                    errorMsg
                )
            }
        }

        private class Leave(): Message() {
            override val status = "leave"
            override val response = JSONObject()

            override fun toString(): String {
                return status
            }
        }

        private class None(): Message() {
            override val status = "none"
            override val response = JSONObject()

            override fun toString(): String {
                return status
            }
        }

    }


    companion object {
        val LifeCycleEvents = Event.values().map { e -> e.event }
    }


    class Push(
        private val channel: Channel,
        private val event: String,
        private val payload: JSONObject,
        private var timeout: Int
    ) {
        private var receivedResp: Message? = null
        private var timeoutTimer: Timer? = null
        private var recHooks = listOf<Pair<String, (JSONObject) -> Unit>>()
        private var sent = false
        internal var ref: Ref? = null
        private var refEvent: String? = null


        internal fun resend(timeout: Int) {
            this.timeout = timeout
            reset()
            send()
        }

        internal fun send() {
            synchronized(channel.socket) {
                if (hasReceived("timeout")) {
                    return
                }
                startTimeout()
                sent = true
                channel.socket.push(Socket.Message(
                    topic = channel.topic,
                    event = event,
                    payload = payload,
                    ref = ref,
                    joinRef = channel.joinRef()
                ))
            }
        }

        fun receive(status: String, callback: (JSONObject) -> Unit): Push {
            synchronized(channel.socket) {
                if (hasReceived(status)) {
                    val response = receivedResp?.response
                    if (response != null) {
                        callback(response)
                    }
                }

                recHooks = recHooks.plus(Pair(status, callback))
                return this
            }
        }

        internal fun reset() {
            cancelRefEvent()
            ref = null
            refEvent = null
            receivedResp = null
            sent = false
        }

        internal fun matchReceive(message: Message?) {
            synchronized(channel.socket) {
                if (message == null) {
                    return
                }

                recHooks.forEach { (status, callback) ->
                    if (message.status == status) {
                        callback(message.response)
                    }
                }
            }
        }

        internal fun cancelRefEvent() {
            var refEvent = this.refEvent
            if (refEvent != null) {
                channel.off(refEvent)
            }
        }

        internal fun cancelTimeout() {
            timeoutTimer?.cancel()
            timeoutTimer = null
        }

        internal fun startTimeout() {
            cancelTimeout()

            val ref = channel.socket.makeRef()
            val refEvent = channel.replyEventName(ref)
            this.ref = ref
            this.refEvent = refEvent

            channel.onEvent(refEvent) { message, _, _ ->
                synchronized(channel.socket) {
                    cancelRefEvent()
                    cancelTimeout()
                    receivedResp = message
                    matchReceive(message)
                }
            }

            val timeout = this.timeout
            timeoutTimer = Timer()
            timeoutTimer?.schedule(timeout.toLong()) {
                trigger("timeout")
            }
        }

        internal fun hasReceived(status: String): Boolean {
            return receivedResp?.status == status
        }

        internal fun trigger(
            status: String,
            response: JSONObject = JSONObject()
        ) {
            synchronized(channel.socket) {
                val refEvent = this.refEvent
                if (refEvent != null) {
                    val msg = Message.build(status, response)
                    channel.trigger(refEvent, msg)
                }
            }
        }
    }

    private var state = State.CLOSED
    private var bindings =
                listOf<Triple<String, Ref, (Message?, Ref?, Ref?) -> Unit>>()
    private var bindingRef: Ref = 0
    private var timeout = socket.opts.timeout
    private var joinedOnce: Boolean = false
    private var joinPush: Push
    private var pushBuffer = listOf<Push>()
    internal var stateChangeRefs = listOf<Ref>()
    private val rejoinTimer: ExTimer


    init {
        joinPush = Push(this, Event.JOIN.event, params, timeout)

        rejoinTimer = ExTimer({
            socket.log("CHANNEL", "Rejoin")
            if (socket.isConnected()) {
                rejoin()
            }
        }, socket.opts.rejoinAfterMs)

        stateChangeRefs = stateChangeRefs.plus(socket.onError({ rejoinTimer.reset() }))
        stateChangeRefs = stateChangeRefs.plus(socket.onOpen({
            synchronized(socket) {
                rejoinTimer.reset()
                if (isErrored()) {
                    rejoin()
                }
            }
        }))

        joinPush.receive("ok") {
            synchronized(socket) {
                state = State.JOINED
                rejoinTimer.reset()
                pushBuffer.forEach { evt ->
                    evt.send()
                }
                pushBuffer = listOf()
            }
        }
        joinPush.receive("error") {
            synchronized(socket) {
                state = State.ERRORED
                if (socket.isConnected()) {
                    rejoinTimer.scheduleTimeout()
                }
            }
        }

        onClose {
            synchronized(socket) {
                rejoinTimer.reset()
                socket.log("CHANNEL", "Closed", this)
                state = State.CLOSED
                socket.remove(this)
            }
        }

        onError { reason ->
            synchronized(socket) {
                socket.log("CHANNEL", "Error: " + reason, this)
                if (isJoining()) {
                    joinPush.reset()
                }
                state = State.ERRORED
                if (socket.isConnected()) {
                    rejoinTimer.scheduleTimeout()
                }
            }
        }

        joinPush.receive("timeout") {
            synchronized(socket) {
                socket.log("CHANNEL", "Timeout after: " + timeout, this)
                val leavePush = Push(this, Event.LEAVE.event, JSONObject(), timeout)
                leavePush.send()
                state = State.ERRORED
                joinPush.reset()
                if (socket.isConnected()) {
                    rejoinTimer.scheduleTimeout()
                }
            }
        }

        onReply { payload, ref ->
            if (ref != null) {
                synchronized(socket) {
                    trigger(replyEventName(ref), payload)
                }
            }
        }


    }

    override fun toString(): String {
        return String.format(
            "topic: %s, " +
            "state: %s, " +
            "join_ref: %s",
            topic,
            state.state,
            joinRef())
    }

    fun join(timeout: Int = this.timeout): Push {
        if (joinedOnce) {
            throw Exception("tried to join multiple times. "+
            "'join' can only be called a single time per channel instance")
        } else {
            synchronized(socket) {
                this.timeout = timeout
                joinedOnce = true
                rejoin()
            }
            return joinPush
        }
    }

    private fun onReply(callback: (Message?, Ref?) -> Unit): Ref {
        return onEvent(Event.REPLY.event) { msg, ref, _ ->
            synchronized(socket) {
                callback(msg, ref)
            }
        }
    }

    fun onClose(callback: () -> Unit): Ref {
        return onEvent(Event.CLOSE.event) { _, _, _ ->
            synchronized(socket) {
                callback()
            }
        }
    }

    fun onError(callback: (String) -> Unit): Ref {
        return onEvent(Event.ERROR.event) { msg, _, _ ->
            synchronized(socket) {
                var error = "Unknown error"
                if (msg != null && msg.isError()) {
                    error = msg.getError()
                }
                callback(error)
            }
        }
    }

    private fun onEvent(
        event: String,
        callback: (Message?, Ref?, Ref?) -> Unit
    ): Ref {
        synchronized(socket) {
            val ref = bindingRef++
            bindings = bindings.plus(Triple(event, ref, callback))
            return ref
        }
    }

    fun on(event: String, callback: (Message) -> Unit): Ref {
        return onEvent(event) { msg, _, _ ->
            synchronized(socket) {
                if (msg != null) {
                    callback(msg)
                }
            }
        }
    }

    fun off(event: String, ref: Ref? = null) {
        synchronized(socket) {
            bindings = bindings.filterNot { (event_, ref_, _) ->
                event == event_ && (ref == null || ref == ref_)
            }
        }
    }

    internal fun canPush(): Boolean {
        return socket.isConnected() && isJoined()
    }

    fun push(
        event: String,
        payload: JSONObject = JSONObject(),
        timeout: Int = this.timeout
    ): Push {
        if (!joinedOnce) {
            throw Exception("tried to push " + event + " to " + topic +
            " before joining. Use channel.join() before pushing events")
        }
        synchronized(socket) {
            val pushEvent = Push(this, event, payload, timeout)
            if (canPush()) {
                pushEvent.send()
            } else {
                pushEvent.startTimeout()
                pushBuffer = pushBuffer.plus(pushEvent)
            }
            return pushEvent
        }
    }

    fun leave(timeout: Int = this.timeout): Push {
        synchronized(socket) {
            rejoinTimer.reset()
            joinPush.cancelTimeout()

            state = State.LEAVING

            val onClose: (JSONObject) -> Unit = { _ ->
                socket.log("CHANNEL", "Leaving", this)
                trigger(Event.CLOSE.event, Message.leave())
            }
            val leavePush = Push(this, Event.LEAVE.event, JSONObject(), timeout)

            leavePush
            .receive("ok", onClose)
            .receive("timeout", onClose)
            .send()

            if (!canPush()) {
                leavePush.trigger("ok")
            }
            return leavePush
        }
    }


    internal fun isLifecycleEvent(event: String): Boolean {
        return Channel.LifeCycleEvents.contains(event)
    }

    internal fun isMember(msg: Socket.Message): Boolean {
        if (topic != msg.topic) {
            return false
        }
        if (msg.joinRef != null
            && msg.joinRef != joinRef()
            && isLifecycleEvent(msg.event)) {
            socket.log("CHANNEL", "Dropping outdated message", msg)
            return false
        }
        return true
    }

    internal fun joinRef(): Ref? {
        return joinPush.ref
    }

    internal fun sendJoin(timeout: Int) {
        state = State.JOINING
        joinPush.resend(timeout)
    }

    internal fun rejoin(timeout: Int = this.timeout) {
        if (!isLeaving()) {
            sendJoin(timeout)
        }
    }

    internal fun trigger(
        event: String,
        payload: Message? = null,
        ref: Ref? = null,
        joinRef: Ref? = this.joinRef()
    ) {
        socket.log(
            "CHANNEL",
            "Trigger", hashMapOf(
            "channel" to this,
            "event" to event,
            "message" to payload))

        synchronized(socket) {
            bindings.forEach { (event_, _, callback) ->
                if (event == event_) {
                    callback(payload, ref, joinRef)
                }
            }
        }
    }

    internal fun replyEventName(ref: Ref): String {
        return "chan_reply_" + ref.toString()
    }

    internal fun isClosed() : Boolean {
        return state == State.CLOSED
    }

    internal fun isErrored() : Boolean {
        return state == State.ERRORED
    }

    internal fun isJoined() : Boolean {
        return state == State.JOINED
    }

    internal fun isJoining() : Boolean {
        return state == State.JOINING
    }

    internal fun isLeaving() : Boolean {
        return state == State.LEAVING
    }
}

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


internal typealias Ref = Int
internal typealias Callback = () -> Unit
internal typealias OpenCallback = () -> Unit
internal typealias CloseCallback = (Int, String) -> Unit
internal typealias ErrorCallback = (String) -> Unit
internal typealias MessageCallback = (Socket.Message) -> Unit

class Socket(
    var url: String,
    var opts: Socket.Options = Socket.Options()
): WebSocketAdapter() {

    enum class State(var state: String) {
        CONNECTING("connecting"),
        OPEN("open"),
        CLOSING("closing"),
        CLOSED("closed"),
        CREATED("created"),
        UNKNOWN("unknown")
    }

    class Options {
        var timeout = 10000
        var heartbeatIntervalMs: Long = 30000
        var rejoinAfterMs: ((Int) -> Int) = {
            tries -> min(tries * tries * 1000, 10000)
        }
        var reconnectAfterMs: ((Int) -> Int) = {
            tries -> min(tries * tries * tries * 5, 10000)
        }
        var logger: ((String, String) -> Unit)? = { tag, msg ->
            System.out.println(tag + ": " + msg)
        }
        var params: HashMap<String, Any>? = null
    }

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
     * Override `logger` in socket option to change this.
     * Default to `stdout`.
     *
     * @param tag Message tag
     * @param msg Message
     * @param data An object that will be appended to the message,
     *             `toString()` will be called on `data`.
     */
    fun log(tag: String, msg: String, data: Any? = null) {
        if (data != null) {
            opts.logger?.invoke(tag, msg + " <" + data.toString() + ">")
        } else {
            opts.logger?.invoke(tag, msg)
        }
    }

    fun onOpen(callback: OpenCallback): Ref {
        synchronized(this) {
            val ref = this.makeRef()
            openCallbacks = openCallbacks.plus(Pair(ref, callback))
            return ref
        }
    }

    fun onClose(callback: CloseCallback): Ref {
        synchronized(this) {
            val ref = this.makeRef()
            closeCallbacks = closeCallbacks.plus(Pair(ref, callback))
            return ref
        }
    }

    fun onError(callback: ErrorCallback): Ref {
        synchronized(this) {
            val ref = this.makeRef()
            errorCallbacks = errorCallbacks.plus(Pair(ref, callback))
            return ref
        }
    }

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

    fun isConnected(): Boolean {
        return connectionState() == State.OPEN
    }

    internal fun remove(channel: Channel) {
        synchronized(this) {
            off(channel.stateChangeRefs)
            channels = channels.filterNot { c -> c.joinRef() == channel.joinRef() }
        }
    }

    fun off(refs: List<Int>) {
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

    fun channel(topic: String, params: JSONObject = JSONObject()): Channel {
        synchronized(this) {
            val chan = Channel(topic, params, this)
            channels = channels.plus(chan)
            return chan
        }
    }

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
            sock.log("RAW SOCKET", "Connected to", sock.endPointURL())
            sock.triggerConnect()
        }

        override fun onDisconnected(
            websocket: WebSocket,
            serverCloseFrame: WebSocketFrame,
            clientCloseFrame: WebSocketFrame,
            closedByServer: Boolean
        ) {
            sock.log("RAW SOCKET", "Disconnected")
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
            sock.log("RAW SOCKET", "Error", cause)

            sock.triggerError(cause.toString())

            if (cause.getError() == WebSocketError.NO_MORE_FRAME
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
            sock.log("RAW SOCKET", "Unexpected error", cause)

            sock.triggerError(cause.toString())
        }

        override fun onTextMessage(websocket: WebSocket, rawMessage: String) {
            sock.log("RAW SOCKET", "Message", rawMessage)

            sock.triggerTextMessage(rawMessage)
        }
    }
}

// TODO implement presence

class Presence(val channel: Channel, val opts: Options = Options()) {

    class Options(
        var eventNames: EventNames = EventNames("presence_state", "presence_diff")
    ) {
        class EventNames(val state: String, val diff: String)
    }

    class Entry(val str: String): JSONObject(str) {
        val metas = getJSONArray("metas")

        fun setMetas(metas: JSONArray) {
            put("metas", metas)
        }

        fun setMetas(metas: List<JSONObject>) {
            put("metas", JSONArray(metas))
        }

        fun prependMetas(newMetas: List<JSONObject>) {
            val joined = mutableListOf<JSONObject>()
            joined.addAll(newMetas)
            metas.forEach { m ->
                if (m is JSONObject) {
                    joined.add(m)
                }
            }
            put("metas", joined)
        }
    }

    class Diff(
        val joins: HashMap<String, Entry>,
        val leaves: HashMap<String, Entry>
    )

    private var state = hashMapOf<String, Entry>()
    private var pendingDiffs = listOf<Diff>()
    private var joinRef: Ref? = null

    private var onJoin =
    fun (
        key: String,
        currentPresence: Entry?,
        newPresence: Entry
    ) {
    }

    private var onLeave =
    fun (
        key: String,
        currentPresence: Entry?,
        leftPresence: Entry
    ) {
    }

    private var onSync = fun () {}

    init {
        channel.on(opts.eventNames.state) { msg ->
            synchronized(channel.socket) {
                val newState = toState(msg.response)
                joinRef = channel.joinRef()
                state = Presence.syncState(state, newState, onJoin, onLeave)
                pendingDiffs.forEach { diff ->
                    state = Presence.syncDiff(state, diff, onJoin, onLeave)
                }
                pendingDiffs = listOf()
                onSync()
            }
        }
        channel.on(opts.eventNames.diff) { msg ->
            synchronized(channel.socket) {
                val diff = toDiff(msg.response)
                if (inPendingSyncState()) {
                    pendingDiffs = pendingDiffs.plus(diff)
                } else {
                    state = Presence.syncDiff(state, diff, onJoin, onLeave)
                    onSync()
                }
            }
        }
    }

    /**
     * Set the callback for join event
     *
     * @param callback The function to be called on join
     */
    fun onJoin(
        callback: (key: String,
                   currentPresence: Entry?,
                   newPresence: Entry) -> Unit
    ) {
        onJoin = callback
    }

    /**
     * Set the callback for leave event
     *
     * @param callback The function to be called on leave
     */
    fun onLeave(
        callback: (key: String,
                   currentPresence: Entry?,
                   leftPresence: Entry) -> Unit
    ) {
        onLeave = callback
    }

    /**
     * Set the callback for sync event
     *
     * @param callback The function to be called on sync
     */
    fun onSync(callback: () -> Unit) {
        onSync = callback
    }

    fun list(by: (String, Entry) -> Entry = { _, p -> p }) : List<Entry> {
        synchronized(channel.socket) {
            return Presence.list(state, by)
        }
    }


    fun inPendingSyncState(): Boolean {
        return joinRef != channel.joinRef()
    }

    companion object {
        private fun toState(obj: JSONObject): HashMap<String, Entry> {
            val state = hashMapOf<String, Entry>()
            obj.keys().forEach { key ->
                val entry = Entry(obj.getJSONObject(key).toString())
                state.put(key, entry)
            }
            return state
        }

        private fun toDiff(obj: JSONObject): Diff {
            val joins = toState(obj.getJSONObject("joins"))
            val leaves = toState(obj.getJSONObject("leaves"))
            return Diff(joins, leaves)
        }

        private fun mapRefs(a: JSONArray): Set<String> {
            return a.map { m ->
                if (m is JSONObject) {
                    m.getString("phx_ref")
                } else {
                    throw Exception("Meta element is not JSON object")
                }
            }.toSet()
        }

        private fun filterMetas(a: JSONArray, refs: Set<String>): List<JSONObject> {
            val results = mutableListOf<JSONObject>()

            a.forEach { m ->
                if (m is JSONObject) {
                    val ref = m.getString("phx_ref")
                    if (!refs.contains(ref)) {
                        results.add(m)
                    }
                } else {
                    throw Exception("Meta element is not JSON object")
                }
            }
            return results
        }


        fun syncState(
            currentState: HashMap<String, Entry>,
            newState: HashMap<String, Entry>,
            onJoin: (key: String,
                     currentPresence: Entry?,
                     newPresence: Entry) -> Unit,
            onLeave: (key: String,
                     currentPresence: Entry?,
                     leftPresence: Entry) -> Unit
        ): HashMap<String, Entry> {
            val state = clone(currentState)
            val joins = hashMapOf<String, Entry>()
            val leaves = hashMapOf<String, Entry>()

            map(state) { key, presence ->
                if (!newState.containsKey(key)) {
                    leaves.set(key, presence)
                }
                presence
            }
            map(newState) { key, newPresence ->
                val currentPresence = state.get(key)
                if (currentPresence != null) {
                    val newRefs = mapRefs(newPresence.metas)
                    val curRefs = mapRefs(currentPresence.metas)
                    val joinedMetas = filterMetas(newPresence.metas, curRefs)
                    val leftMetas = filterMetas(currentPresence.metas, newRefs)
                    if (joinedMetas.size > 0) {
                        val presence = clone(newPresence)
                        presence.setMetas(joinedMetas)
                        joins.put(key, presence)
                    }
                    if (leftMetas.size > 0) {
                        val presence = clone(currentPresence)
                        presence.setMetas(leftMetas)
                        leaves.put(key, presence)
                    }
                } else {
                    joins.put(key, newPresence)
                }
                newPresence
            }

            return syncDiff(state, Diff(joins, leaves), onJoin, onLeave)
        }

        fun syncDiff(
            currentState: HashMap<String, Entry>,
            diff: Diff,
            onJoin: (key: String,
                     currentPresence: Entry?,
                     newPresence: Entry) -> Unit,
            onLeave: (key: String,
                     currentPresence: Entry?,
                     leftPresence: Entry) -> Unit
        ): HashMap<String, Entry> {
            val state = clone(currentState)

            map(diff.joins) { key, newPresence ->
                val currentPresence = state.get(key)
                state.put(key, newPresence)
                if (currentPresence != null) {
                    val joinedRefs = mapRefs(newPresence.metas)
                    val curMetas = filterMetas(currentPresence.metas, joinedRefs)
                    newPresence.prependMetas(curMetas)
                }
                onJoin(key, currentPresence, newPresence)
                newPresence
            }
            map(diff.leaves) { key, leftPresence ->
                val currentPresence = state.get(key)
                if (currentPresence != null) {
                    val refsToRemove = mapRefs(leftPresence.metas)
                    val newMetas = filterMetas(
                        currentPresence.metas,
                        refsToRemove
                    )
                    currentPresence.setMetas(newMetas)
                    onLeave(key, currentPresence, leftPresence)
                    if (currentPresence.metas.length() === 0) {
                        state.remove(key)
                    }
                }
                leftPresence
            }

            return state
        }

        fun list(
            presences: HashMap<String, Entry>,
            chooser: (String, Entry) -> Entry = { _, p -> p }
        ): List<Entry> {
            return map(presences, chooser)
        }

        fun map(
            entries: HashMap<String, Entry>,
            func: (String, Entry) -> Entry
        ): List<Entry> {
            return entries.map { (key, entry) ->
                func(key, entry)
            }
        }

        fun clone(obj: HashMap<String, Entry>): HashMap<String, Entry> {
            val copy = hashMapOf<String, Entry>()
            obj.forEach { (key, entry) ->
                copy.put(key, Entry(entry.toString()))
            }
            return copy
        }

        fun clone(entry: Entry): Entry {
            return Entry(entry.toString())
        }
    }


}


/**
 *
 * Exponential backoff timer
 *
 * @property callback The function called when the timer fires
 * @property timerCalc A function that is called when the timer is scheduled.
 *                     It is called with the current try and must return a
 *                     time in miliseconds. It is used to implement exponential
 *                     timers. First value of `tries` is 1.
 *                     Example: `{ tries -> min(tries * tries * 1000, 60000) }`
 */
class ExTimer(var callback: Callback, var timerCalc: (Int) -> Int) {

    var timer: Timer? = null
    var tries = AtomicInteger()

    fun reset() {
        tries.set(0)
        timer?.cancel()
        timer = null
    }

    fun scheduleTimeout() {
        timer?.cancel()

        timer = Timer()

        timer?.schedule(timerCalc(tries.get() + 1).toLong()) {
            tries.incrementAndGet()
            callback()
        }
    }
}
