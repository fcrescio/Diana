package li.crescio.penates.diana.notes

data class RawRecording(val filePath: String)

data class Transcript(val text: String)

/**
 * A captured audio note and its raw transcript prior to any processing.
 */
data class RecordedNote(
    val recording: RawRecording,
    val transcript: Transcript
)

sealed class StructuredNote {
    data class ToDo(val text: String) : StructuredNote()
    data class Memo(val text: String) : StructuredNote()
    data class Event(val text: String, val datetime: String) : StructuredNote()
    data class Free(val text: String) : StructuredNote()
}

data class NoteCollection(val notes: List<StructuredNote>)
