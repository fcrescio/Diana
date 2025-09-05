package li.crescio.penates.diana.persistence

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import li.crescio.penates.diana.llm.MemoSummary
import li.crescio.penates.diana.notes.StructuredNote
import java.io.File

class NoteRepository(
    private val firestore: FirebaseFirestore,
    private val file: File
) {
    suspend fun saveNotes(notes: List<StructuredNote>) {
        file.writeText(notes.joinToString("\n") { it.toString() })
        firestore.collection("notes").add(mapOf("raw" to notes.map { it.toString() }))
    }

    suspend fun saveSummary(summary: MemoSummary) {
        saveNotes(summaryToNotes(summary))
    }

    suspend fun loadNotes(): List<StructuredNote> {
        val local = if (file.exists()) {
            file.readLines().mapNotNull { parse(it) }
        } else emptyList()

        val remote = try {
            firestore.collection("notes").get().await().documents
                .flatMap { doc ->
                    val raws = doc.get("raw") as? List<*>
                    raws?.mapNotNull { parse(it.toString()) } ?: emptyList()
                }
        } catch (_: Exception) {
            emptyList()
        }

        return local + remote
    }

    private fun parse(line: String): StructuredNote? {
        return when {
            line.startsWith("ToDo(") ->
                StructuredNote.ToDo(line.substringAfter("text=").substringBeforeLast(")"))
            line.startsWith("Memo(") ->
                StructuredNote.Memo(line.substringAfter("text=").substringBeforeLast(")"))
            line.startsWith("Event(") -> {
                val textPart = line.substringAfter("text=").substringBefore(", datetime=")
                val datePart = line.substringAfter(", datetime=").substringBeforeLast(")")
                StructuredNote.Event(textPart, datePart)
            }
            line.startsWith("Free(") ->
                StructuredNote.Free(line.substringAfter("text=").substringBeforeLast(")"))
            else -> null
        }
    }

    private fun summaryToNotes(summary: MemoSummary): List<StructuredNote> {
        val notes = mutableListOf<StructuredNote>()
        notes += summary.todo.lines().filter { it.isNotBlank() }.map { StructuredNote.ToDo(it.trim()) }
        notes += summary.appointments.lines().filter { it.isNotBlank() }
            .map { StructuredNote.Event(it.trim(), "") }
        notes += summary.thoughts.lines().filter { it.isNotBlank() }.map { StructuredNote.Memo(it.trim()) }
        return notes
    }
}
