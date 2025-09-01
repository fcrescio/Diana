package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import li.crescio.penates.diana.notes.StructuredNote

@Composable
fun NotesListScreen(notes: List<StructuredNote>, onRecord: () -> Unit) {
    Column {
        Button(onClick = onRecord) { Text("Record") }
        LazyColumn {
            items(notes) { note ->
                Text(note.toString())
            }
        }
    }
}
