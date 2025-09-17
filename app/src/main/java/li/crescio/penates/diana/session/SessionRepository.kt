package li.crescio.penates.diana.session

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.text.Charsets

class SessionRepository(private val filesDir: File) {
    private val lock = Any()
    private val sessionFile = File(filesDir, "sessions.json")
    private val sessions = mutableListOf<Session>()
    private var selectedSessionId: String? = null

    init {
        val state = loadFromDisk()
        sessions.addAll(state.sessions)
        selectedSessionId = state.selectedSessionId
    }

    fun list(): List<Session> = synchronized(lock) {
        sessions.toList()
    }

    fun create(name: String, settings: SessionSettings): Session = synchronized(lock) {
        val session = Session(UUID.randomUUID().toString(), name, settings)
        sessions.add(session)
        persistLocked()
        session
    }

    fun update(session: Session): Session = synchronized(lock) {
        val index = sessions.indexOfFirst { it.id == session.id }
        require(index >= 0) { "Session not found: ${session.id}" }
        sessions[index] = session
        persistLocked()
        session
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val removed = sessions.removeAll { it.id == id }
        if (removed && selectedSessionId == id) {
            selectedSessionId = null
        }
        if (removed) {
            persistLocked()
        }
        removed
    }

    fun getSelected(): Session? = synchronized(lock) {
        val selectedId = selectedSessionId
        if (selectedId == null) {
            null
        } else {
            sessions.firstOrNull { it.id == selectedId }
        }
    }

    fun setSelected(id: String?) = synchronized(lock) {
        if (id == selectedSessionId) {
            return
        }
        if (id != null && sessions.none { it.id == id }) {
            throw IllegalArgumentException("Session not found: $id")
        }
        selectedSessionId = id
        persistLocked()
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
}
