package li.crescio.penates.diana.session

/**
 * Represents a saved conversation session.
 */
data class Session(
    val id: String,
    val name: String,
    val settings: SessionSettings = SessionSettings(),
)

/**
 * Controls what data should be persisted for a session and which LLM model to use.
 */
data class SessionSettings(
    val saveTodos: Boolean = true,
    val saveAppointments: Boolean = true,
    val saveThoughts: Boolean = true,
    val model: String = "",
)
