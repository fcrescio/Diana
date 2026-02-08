package li.crescio.penates.diana.llm

data class TodoAction(
    val op: String,
    val before: TodoItem?,
    val after: TodoItem?,
)

data class TodoChangeSet(
    val changeSetId: String,
    val sessionId: String,
    val memoId: String,
    val timestamp: Long,
    val model: String,
    val promptVersion: String,
    val actions: List<TodoAction>,
    val type: String = "apply",
)
