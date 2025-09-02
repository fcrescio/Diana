package li.crescio.penates.diana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
@Composable
fun NotesListScreen(
    todo: String,
    appointments: String,
    thoughts: String,
    logs: List<String>,
    onRecord: () -> Unit,
    onViewRecordings: () -> Unit,
    onAddMemo: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = onRecord) { Text("Record") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onAddMemo) { Text("Text Memo") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onViewRecordings) { Text("View Recordings") }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text("To-Do List")
                Text(todo, modifier = Modifier.padding(bottom = 16.dp))
            }
            item {
                Text("Appointments")
                Text(appointments, modifier = Modifier.padding(bottom = 16.dp))
            }
            item {
                Text("Thoughts & Notes")
                Text(thoughts)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.Black)
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(logs) { log ->
                    Text(
                        log,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Green
                    )
                }
            }
        }
    }
}
