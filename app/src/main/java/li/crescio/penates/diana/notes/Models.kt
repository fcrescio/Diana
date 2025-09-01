package li.crescio.penates.diana.notes

data class RawRecording(val filePath: String)

data class Transcript(val text: String)

sealed class StructuredNote {
    data class ToDo(val text: String) : StructuredNote()
    data class Memo(val text: String) : StructuredNote()
    data class Event(val text: String, val datetime: String) : StructuredNote()
    data class Free(val text: String) : StructuredNote()
}

data class NoteCollection(val notes: List<StructuredNote>)
