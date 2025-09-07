package li.crescio.penates.diana.notes

data class RawRecording(val filePath: String)

data class Transcript(val text: String)

/** A memo consists of the written text and, optionally, the path to the
 * original audio that produced it. */
data class Memo(
    val text: String,
    val audioPath: String? = null
)

sealed class StructuredNote(open val createdAt: Long) {
    data class ToDo(
        val text: String,
        override val createdAt: Long = System.currentTimeMillis()
    ) : StructuredNote(createdAt)

    data class Memo(
        val text: String,
        override val createdAt: Long = System.currentTimeMillis()
    ) : StructuredNote(createdAt)

    data class Event(
        val text: String,
        val datetime: String,
        override val createdAt: Long = System.currentTimeMillis()
    ) : StructuredNote(createdAt)

    data class Free(
        val text: String,
        override val createdAt: Long = System.currentTimeMillis()
    ) : StructuredNote(createdAt)
}

data class NoteCollection(val notes: List<StructuredNote>)
