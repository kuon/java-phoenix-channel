package ch.kuon.phoenix

import com.github.openjson.JSONObject
import com.github.openjson.JSONArray

/**
 * Listen for presence changes
 *
 * @property channel A channel
 * @property opts Options
 */
class Presence(val channel: Channel, val opts: Options = Options()) {

    /**
     * Presence options
     *
     * Default options should be used except for special requirements.
     * They line up with phoenix default options.
     */
    class Options(
        var eventNames: EventNames = EventNames("presence_state", "presence_diff")
    ) {
        class EventNames(val state: String, val diff: String)
    }

    /**
     * A presence entry
     *
     * While presence are JSONObject subclasses, they should be considered
     * immutable.
     *
     * Presence entries are returned by [Presence.list] and other callbacks.
     * @property rawData The JSON string that sent by the server
     */
    class Entry(val rawData: String): JSONObject(rawData) {

        /**
         * The presence meta data
         *
         * This is an arbitrary array and the format depends on your
         * implementation.
         */
        fun getMetas(): JSONArray {
            return getJSONArray("metas")
        }

        internal fun setMetas(metas: JSONArray) {
            put("metas", metas)
        }

        internal fun setMetas(metas: List<JSONObject>) {
            put("metas", JSONArray(metas))
        }

        internal fun prependMetas(newMetas: List<JSONObject>) {
            val joined = mutableListOf<JSONObject>()
            joined.addAll(newMetas)
            val metas = getMetas()
            val len = metas.length()
            for (i in 0 until len) {
                val m = metas.get(i)
                if (m is JSONObject) {
                    joined.add(m)
                }
            }
            put("metas", joined)
        }
    }

    internal class Diff(
        val joins: HashMap<String, Entry>,
        val leaves: HashMap<String, Entry>
    )

    private var state = hashMapOf<String, Entry>()
    private var pendingDiffs = listOf<Diff>()
    private var joinRef: Ref? = null

    private var onJoin =
    fun (
        _: String, // key
        _: Entry?, // currentPresence
        _: Entry // newPresence
    ) {
    }

    private var onLeave =
    fun (
        _: String, // key
        _: Entry?, // currentPresence
        _: Entry // leftPresence
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
     * Note: the callback might be called on another thread
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
     * Note: the callback might be called on another thread
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
     * Note: the callback might be called on another thread
     *
     * @param callback The function to be called on sync
     */
    fun onSync(callback: () -> Unit) {
        onSync = callback
    }

    /**
     * Returns the list of presence
     *
     * This method will return every presence returned by the server
     */
    fun list(by: (String, Entry) -> Entry = { _, p -> p }): List<Entry> {
        synchronized(channel.socket) {
            return Presence.list(state, by)
        }
    }


    /**
     * Is there any pendinng sync state
     */
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
            val results = mutableListOf<String>()
            val len = a.length()
            for (i in 0 until len) {
                val m = a.get(i)
                if (m is JSONObject) {
                    results.add(m.getString("phx_ref"))
                } else {
                    throw Exception("Meta element is not JSON object")
                }
            }
            return results.toSet()
        }

        private fun filterMetas(a: JSONArray, refs: Set<String>): List<JSONObject> {
            val results = mutableListOf<JSONObject>()

            val len = a.length()
            for (i in 0 until len) {
                val m = a.get(i)
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


        internal fun syncState(
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
                    val newRefs = mapRefs(newPresence.getMetas())
                    val curRefs = mapRefs(currentPresence.getMetas())
                    val joinedMetas = filterMetas(newPresence.getMetas(), curRefs)
                    val leftMetas = filterMetas(currentPresence.getMetas(), newRefs)
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

        internal fun syncDiff(
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
                    val joinedRefs = mapRefs(newPresence.getMetas())
                    val curMetas = filterMetas(currentPresence.getMetas(), joinedRefs)
                    newPresence.prependMetas(curMetas)
                }
                onJoin(key, currentPresence, newPresence)
                newPresence
            }
            map(diff.leaves) { key, leftPresence ->
                val currentPresence = state.get(key)
                if (currentPresence != null) {
                    val refsToRemove = mapRefs(leftPresence.getMetas())
                    val newMetas = filterMetas(
                        currentPresence.getMetas(),
                        refsToRemove
                    )
                    currentPresence.setMetas(newMetas)
                    onLeave(key, currentPresence, leftPresence)
                    if (currentPresence.getMetas().length() == 0) {
                        state.remove(key)
                    }
                }
                leftPresence
            }

            return state
        }

        internal fun list(
            presences: HashMap<String, Entry>,
            chooser: (String, Entry) -> Entry = { _, p -> p }
        ): List<Entry> {
            return map(presences, chooser)
        }

        internal fun map(
            entries: HashMap<String, Entry>,
            func: (String, Entry) -> Entry
        ): List<Entry> {
            return entries.map { (key, entry) ->
                func(key, entry)
            }
        }

        internal fun clone(obj: HashMap<String, Entry>): HashMap<String, Entry> {
            val copy = hashMapOf<String, Entry>()
            obj.forEach { (key, entry) ->
                copy.put(key, Entry(entry.toString()))
            }
            return copy
        }

        internal fun clone(entry: Entry): Entry {
            return Entry(entry.toString())
        }
    }


}

