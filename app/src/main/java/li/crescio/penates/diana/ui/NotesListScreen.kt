package li.crescio.penates.diana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import li.crescio.penates.diana.R
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
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                listState.scrollToItem(logs.lastIndex)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = onRecord) { Text(stringResource(R.string.record)) }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onAddMemo) { Text(stringResource(R.string.text_memo)) }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onViewRecordings) { Text(stringResource(R.string.view_recordings)) }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text(stringResource(R.string.todo_list))
                Text(todo, modifier = Modifier.padding(bottom = 16.dp))
            }
            item {
                Text(stringResource(R.string.appointments))
                Text(appointments, modifier = Modifier.padding(bottom = 16.dp))
            }
            item {
                Text(stringResource(R.string.thoughts_notes))
                Text(thoughts)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.Black)
        ) {
            LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
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
