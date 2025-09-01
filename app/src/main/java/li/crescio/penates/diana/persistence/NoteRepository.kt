package li.crescio.penates.diana.persistence

import com.google.firebase.firestore.FirebaseFirestore
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

    suspend fun loadNotes(): List<StructuredNote> = emptyList()
}
