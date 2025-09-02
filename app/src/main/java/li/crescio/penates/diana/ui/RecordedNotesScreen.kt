package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import li.crescio.penates.diana.notes.RecordedNote
import li.crescio.penates.diana.player.Player

@Composable
fun RecordedNotesScreen(
    notes: List<RecordedNote>,
    player: Player,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = onBack) { Text("Back") }
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(notes) { note ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Button(onClick = { player.play(note.recording.filePath) }) { Text("Play") }
                    Text(
                        note.transcript.text,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }
}
