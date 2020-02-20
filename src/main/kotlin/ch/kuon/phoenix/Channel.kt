package ch.kuon.phoenix

import kotlin.concurrent.schedule
import java.util.Timer
import com.github.openjson.JSONObject
import com.github.openjson.JSONArray

/**
 * Phoenix channel client
 *
 * Channels are created using the [Socket.channel] method.
 *
 */
class Channel internal constructor(
    var topic: String,
    var params: JSONObject,
    var socket: Socket
) {

    /**
     * Channel state
     */
    enum class State(var state: String) {
        CLOSED("closed"),
        ERRORED("errored"),
        JOINED("joined"),
        JOINING("joining"),
        LEAVING("leaving"),
    }

    /**
     * Channel built in events
     */
    enum class Event(var event: String) {
        CLOSE("phx_close"),
        ERROR("phx_error"),
        JOIN("phx_join"),
        REPLY("phx_reply"),
        LEAVE("phx_leave")
    }

    /**
     * Channel messages
     *
     * Usually this class should not be created directly. It is
     * used as a wrapper around response in [Channel.on] callback.
     */
    abstract class Message {
        /**
         * Status of the response as sent by the server.
         */
        abstract val status: String

        /**
         * Response data from the server
         *
         * It can be an empty object if the response was empty, but never
         * `null`.
         */
        abstract val response: JSONObject

        /**
         * Returns the error state of the message
         */
        open fun isError(): Boolean {
            return false
        }

        /**
         * Returns the error reason.
         *
         * If this message is not an error, this will throw an exception
         * so be sure to check [Message.isError] first.
         */
        open fun getError(): String {
            throw Exception("Not an error")
        }

        internal companion object {
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


    /*
     * Class representing the push state of a message
     *
     * This class is not created directly but it is returned by the
     * [Channel.push] method.
     */
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

        /**
         * Register a callback when a message with the following
         * status is returned.
         *
         * Note: the callback might be called from another thread.
         *
         * @param status The status to register the callback for
         * @param callback A callback called with the response
         * @return Self for method chaining
         */
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

    /**
     * Join the channel.
     *
     * @param timeout Timeout in milliseconds,
     *  socket timeout will be used by default.
     * @return A push that can be used to receive the messages returned
     *  by the server on the join.
     */
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

    /**
     * Register a close callback
     *
     * Note: the callback might be called on another thread
     *
     * @param callback A callback called when the channel is closed
     * @return A ref that can be used with [Channel.off]
     */
    fun onClose(callback: () -> Unit): Ref {
        return onEvent(Event.CLOSE.event) { _, _, _ ->
            synchronized(socket) {
                callback()
            }
        }
    }

    /**
     * Register an error callback
     *
     * Note: the callback might be called on another thread
     *
     * @param callback A callback called when the channel has an error
     * @return A ref that can be used with [Channel.off]
     */
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

    /**
     * Register a callback on arbitrary event
     *
     * Note: the callback might be called on another thread
     *
     * @param event The event to register the callback for
     * @param callback A callback called when the channel receive a
     *  message for the given event
     * @return A ref that can be used with [Channel.off]
     */
    fun on(event: String, callback: (Message) -> Unit): Ref {
        return onEvent(event) { msg, _, _ ->
            synchronized(socket) {
                if (msg != null) {
                    callback(msg)
                }
            }
        }
    }

    /**
     * Unregister a callback
     *
     * @param event The event name to unregister the callback for.
     *  This must match what was passed to [Channel.on].
     * @param ref The ref returned by [Channel.on], can be ommited
     *  to unregister all callbacks for a particular event.
     */
    fun off(event: String, ref: Ref? = null) {
        synchronized(socket) {
            bindings = bindings.filterNot { (event_, ref_, _) ->
                event == event_ && (ref == null || ref == ref_)
            }
        }
    }

    /**
     * Unregister multiple callbacks
     *
     * @param refs The refs returned by [Channel.on].
     */
    fun off(refs: List<Ref>) {
        synchronized(socket) {
            bindings = bindings.filterNot { (_, ref_, _) ->
                refs.contains(ref_)
            }
        }
    }

    /**
     * Unregister a single callback
     *
     * @param ref A single reference
     */
    fun off(ref: Ref) {
        off(listOf(ref))
    }

    internal fun canPush(): Boolean {
        return socket.isConnected() && isJoined()
    }

    /**
     * Push a message to the channel.
     *
     * @param event Event name
     * @param payload Arbitrary payload
     * @param timeout Timeout, default to socket timeout
     * @return A push that can be used for chaining
     */
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

    /**
     * Leave a channel
     *
     * @param timeout Timeout, default to socket timeout
     * @return A push that can be used for chaining
     */
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
