package li.crescio.penates.diana.session

/**
 * Represents a saved conversation session.
 */
data class Session(
    val id: String,
    val name: String,
    val settings: SessionSettings = SessionSettings(),
    val summaryGroup: String = "",
)

/**
 * Controls what data should be persisted for a session and which LLM model to use.
 */
data class SessionSettings(
    val processTodos: Boolean = true,
    val processAppointments: Boolean = true,
    val processThoughts: Boolean = true,
    val model: String = "",
)
