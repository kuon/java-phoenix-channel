package ch.kuon.phoenix

import org.json.JSONObject
import org.json.JSONArray

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
                    if (currentPresence.metas.length() == 0) {
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

