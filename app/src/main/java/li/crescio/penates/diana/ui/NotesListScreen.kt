package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import li.crescio.penates.diana.R
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.llm.Appointment
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
@Composable
fun NotesListScreen(
    todoItems: List<TodoItem>,
    appointments: List<Appointment>,
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
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    todoItems.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Checkbox(checked = item.status == "done", onCheckedChange = null)
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(item.text)
                                Row {
                                    item.tags.forEach { tag ->
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(tag) },
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.appointments))
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    appointments.sortedBy { it.datetime }.forEach { appt ->
                        val formatted = runCatching {
                            OffsetDateTime.parse(appt.datetime)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        }.getOrElse { appt.datetime }
                        val location = if (appt.location.isNotBlank()) " @ ${appt.location}" else ""
                        Text("$formatted ${appt.text}$location")
                    }
                }
            }
            item {
                Text(stringResource(R.string.thoughts_notes))
                Text(thoughts)
            }
        }

        LogSection(logs)
    }
}
