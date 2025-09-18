package li.crescio.penates.diana.session

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.text.Charsets

class SessionRepository(
    private val filesDir: File,
    private val firestore: FirebaseFirestore,
    private val remoteDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val sessionFile = File(filesDir, "sessions.json")
    private val sessions = mutableListOf<Session>()
    private var selectedSessionId: String? = null
    private val remoteScope = CoroutineScope(SupervisorJob() + remoteDispatcher)
    private val logger = StructuredLogger()

    init {
        val state = loadFromDisk()
        sessions.addAll(state.sessions)
        selectedSessionId = state.selectedSessionId
    }

    fun list(): List<Session> = synchronized(lock) {
        sessions.toList()
    }

    fun create(name: String, settings: SessionSettings): Session {
        val (session, selectedId) = synchronized(lock) {
            val session = Session(UUID.randomUUID().toString(), name, settings)
            sessions.add(session)
            persistLocked()
            session to selectedSessionId
        }
        syncSessionAsync(session, selectedId)
        return session
    }

    fun importRemoteSession(session: Session): Session {
        val (persisted, selectedId) = synchronized(lock) {
            val index = sessions.indexOfFirst { it.id == session.id }
            if (index >= 0) {
                sessions[index] = session
            } else {
                sessions.add(session)
            }
            persistLocked()
            session to selectedSessionId
        }
        syncSessionAsync(persisted, selectedId)
        return persisted
    }

    fun update(session: Session): Session {
        val (persisted, selectedId) = synchronized(lock) {
            val index = sessions.indexOfFirst { it.id == session.id }
            require(index >= 0) { "Session not found: ${session.id}" }
            sessions[index] = session
            persistLocked()
            session to selectedSessionId
        }
        syncSessionAsync(persisted, selectedId)
        return persisted
    }

    fun delete(id: String): Boolean {
        val (removedSession, selectedIdAfter, remainingSessions) = synchronized(lock) {
            val index = sessions.indexOfFirst { it.id == id }
            if (index < 0) {
                Triple<Session?, String?, List<Session>>(null, null, emptyList())
            } else {
                val removed = sessions.removeAt(index)
                if (selectedSessionId == id) {
                    selectedSessionId = null
                }
                persistLocked()
                Triple(removed, selectedSessionId, sessions.toList())
            }
        }
        if (removedSession != null) {
            remoteScope.launch {
                deleteSessionRemote(removedSession.id)
                syncSelectionRemote(selectedIdAfter, remainingSessions, previousSelectedId = removedSession.id)
            }
            return true
        }
        return false
    }

    fun getSelected(): Session? = synchronized(lock) {
        val selectedId = selectedSessionId
        if (selectedId == null) {
            null
        } else {
            sessions.firstOrNull { it.id == selectedId }
        }
    }

    fun setSelected(id: String?) {
        val (newSelectedId, previousSelectedId, snapshot) = synchronized(lock) {
            if (id == selectedSessionId) {
                return
            }
            if (id != null && sessions.none { it.id == id }) {
                throw IllegalArgumentException("Session not found: $id")
            }
            val previous = selectedSessionId
            selectedSessionId = id
            persistLocked()
            Triple(selectedSessionId, previous, sessions.toList())
        }
        remoteScope.launch {
            syncSelectionRemote(newSelectedId, snapshot, previousSelectedId)
        }
    }

    private fun persistLocked() {
        val payload = JSONObject()
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(session.toJson())
        }
        payload.put("sessions", array)
        selectedSessionId?.let { payload.put("selectedSessionId", it) }
        writeAtomically(payload.toString())
    }

    private fun Session.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("settings", settings.toJson())
        return obj
    }

    private fun SessionSettings.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("processTodos", processTodos)
        obj.put("processAppointments", processAppointments)
        obj.put("processThoughts", processThoughts)
        obj.put("model", model)
        return obj
    }

    private fun loadFromDisk(): RepositoryState {
        if (!sessionFile.exists()) {
            return RepositoryState(emptyList(), null)
        }

        return try {
            val contents = sessionFile.readText()
            if (contents.isBlank()) {
                RepositoryState(emptyList(), null)
            } else {
                parseState(JSONObject(contents))
            }
        } catch (_: Exception) {
            RepositoryState(emptyList(), null)
        }
    }

    private fun parseState(json: JSONObject): RepositoryState {
        val sessionsArray = json.optJSONArray("sessions") ?: JSONArray()
        val parsedSessions = mutableListOf<Session>()
        for (i in 0 until sessionsArray.length()) {
            val obj = sessionsArray.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val name = obj.optString("name", "")
            if (id.isBlank() || name.isBlank()) continue
            val settings = parseSettings(obj.optJSONObject("settings"))
            parsedSessions += Session(id, name, settings)
        }
        val selectedIdRaw = json.optString("selectedSessionId", "")
        val selectedId = if (selectedIdRaw.isBlank()) {
            null
        } else {
            parsedSessions.firstOrNull { it.id == selectedIdRaw }?.id
        }
        return RepositoryState(parsedSessions, selectedId)
    }

    private fun parseSettings(obj: JSONObject?): SessionSettings {
        if (obj == null) return SessionSettings()
        fun JSONObject.optBooleanWithFallback(newKey: String, oldKey: String, default: Boolean): Boolean {
            return if (has(newKey)) {
                optBoolean(newKey, default)
            } else {
                optBoolean(oldKey, default)
            }
        }
        return SessionSettings(
            processTodos = obj.optBooleanWithFallback("processTodos", "saveTodos", true),
            processAppointments = obj.optBooleanWithFallback("processAppointments", "saveAppointments", true),
            processThoughts = obj.optBooleanWithFallback("processThoughts", "saveThoughts", true),
            model = obj.optString("model", ""),
        )
    }

    private fun writeAtomically(contents: String) {
        val parent = sessionFile.parentFile ?: filesDir
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create directory: ${parent.absolutePath}")
        }
        val tmp = File.createTempFile("sessions", ".json.tmp", parent)
        try {
            tmp.writeText(contents, Charsets.UTF_8)
            if (sessionFile.exists()) {
                if (!tmp.renameTo(sessionFile)) {
                    if (!sessionFile.delete() || !tmp.renameTo(sessionFile)) {
                        tmp.copyTo(sessionFile, overwrite = true)
                        tmp.delete()
                    }
                }
            } else {
                if (!tmp.renameTo(sessionFile)) {
                    tmp.copyTo(sessionFile, overwrite = true)
                    tmp.delete()
                }
            }
        } finally {
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    private data class RepositoryState(
        val sessions: List<Session>,
        val selectedSessionId: String?,
    )

    private fun syncSessionAsync(session: Session, selectedId: String?) {
        remoteScope.launch {
            try {
                syncSessionDocument(session, selectedId)
            } catch (e: Exception) {
                logger.warn(
                    "session_remote_upsert_failed",
                    mapOf(
                        "sessionId" to session.id,
                        "selectedSessionId" to selectedId,
                    ),
                    e,
                )
            }
        }
    }

    private suspend fun syncSelectionRemote(
        selectedId: String?,
        sessionsSnapshot: List<Session>,
        previousSelectedId: String?,
    ) {
        val targets = if (selectedId == null) {
            sessionsSnapshot
        } else {
            sessionsSnapshot.filter { session ->
                session.id == selectedId || session.id == previousSelectedId
            }
        }
        for (session in targets) {
            try {
                syncSessionDocument(session, selectedId)
            } catch (e: Exception) {
                logger.warn(
                    "session_selection_sync_failed",
                    mapOf(
                        "sessionId" to session.id,
                        "selectedSessionId" to selectedId,
                        "previousSelectedId" to previousSelectedId,
                    ),
                    e,
                )
            }
        }
    }

    private suspend fun syncSessionDocument(session: Session, selectedId: String?) {
        val data = mutableMapOf<String, Any?>(
            "name" to session.name,
            "settings" to session.settings.toMap(),
            "selectedSessionId" to selectedId,
            "selected" to (selectedId == session.id),
        )
        sessionDocument(session.id).set(data).await()
        logger.info(
            "session_remote_upserted",
            mapOf(
                "sessionId" to session.id,
                "selectedSessionId" to selectedId,
                "selected" to (selectedId == session.id),
            ),
        )
    }

    private suspend fun deleteSessionRemote(sessionId: String) {
        val document = sessionDocument(sessionId)
        try {
            val notesSnapshot = document.collection("notes").get().await()
            var deletedNotes = 0
            for (note in notesSnapshot.documents) {
                try {
                    note.reference.delete().await()
                    deletedNotes += 1
                } catch (noteError: Exception) {
                    logger.warn(
                        "session_note_delete_failed",
                        mapOf(
                            "sessionId" to sessionId,
                            "noteId" to note.id,
                        ),
                        noteError,
                    )
                }
            }
            if (deletedNotes > 0) {
                logger.info(
                    "session_notes_deleted",
                    mapOf(
                        "sessionId" to sessionId,
                        "count" to deletedNotes,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.warn(
                "session_notes_query_failed",
                mapOf("sessionId" to sessionId),
                e,
            )
        }
        try {
            document.delete().await()
            logger.info(
                "session_remote_deleted",
                mapOf("sessionId" to sessionId),
            )
        } catch (e: Exception) {
            logger.warn(
                "session_remote_delete_failed",
                mapOf("sessionId" to sessionId),
                e,
            )
        }
    }

    private fun sessionDocument(sessionId: String) = firestore
        .collection("sessions")
        .document(sessionId)

    private fun SessionSettings.toMap(): Map<String, Any> = mapOf(
        "processTodos" to processTodos,
        "processAppointments" to processAppointments,
        "processThoughts" to processThoughts,
        "model" to model,
    )

    private class StructuredLogger(private val tag: String = "SessionRepository") {
        fun info(event: String, data: Map<String, Any?> = emptyMap()) {
            log(Log.INFO, event, data, null)
        }

        fun warn(event: String, data: Map<String, Any?> = emptyMap(), throwable: Throwable?) {
            log(Log.WARN, event, data, throwable)
        }

        private fun log(priority: Int, event: String, data: Map<String, Any?>, throwable: Throwable?) {
            val payload = buildString {
                append(event)
                if (data.isNotEmpty()) {
                    append(' ')
                    append(formatData(data))
                }
            }
            try {
                when (priority) {
                    Log.INFO -> Log.i(tag, payload)
                    Log.WARN -> Log.w(tag, payload, throwable)
                    Log.ERROR -> Log.e(tag, payload, throwable)
                    else -> Log.d(tag, payload, throwable)
                }
            } catch (_: Throwable) {
                val fallback = StringBuilder()
                fallback.append(tag)
                fallback.append(':')
                fallback.append(payload)
                throwable?.let {
                    fallback.append('\n')
                    fallback.append(it.stackTraceToString())
                }
                println(fallback.toString())
            }
        }

        private fun formatData(data: Map<String, Any?>): String {
            val json = JSONObject()
            data.toSortedMap().forEach { (key, value) ->
                json.put(key, wrapValue(value))
            }
            return json.toString()
        }

        private fun wrapValue(value: Any?): Any? = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val nested = JSONObject()
                value.forEach { (k, v) ->
                    if (k != null) {
                        nested.put(k.toString(), wrapValue(v))
                    }
                }
                nested
            }
            is Iterable<*> -> {
                val array = JSONArray()
                value.forEach { array.put(wrapValue(it)) }
                array
            }
            else -> value
        }
    }
}
