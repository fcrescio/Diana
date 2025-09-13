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
import li.crescio.penates.diana.notes.StructuredNote
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun NotesListScreen(
    todoItems: List<TodoItem>,
    appointments: List<Appointment>,
    notes: List<StructuredNote>,
    logs: List<String>,
    modifier: Modifier = Modifier,
    onTodoCheckedChange: (TodoItem, Boolean) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text(stringResource(R.string.todo_list))
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    todoItems.forEach { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                            ) {
                                Checkbox(
                                    checked = item.status == "done",
                                    onCheckedChange = { checked ->
                                        onTodoCheckedChange(item, checked)
                                    }
                                )
                                Column(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .weight(1f)
                                ) {
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
                var filter by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(R.string.filter_tag)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    notes.filter { note ->
                        filter.isBlank() || when (note) {
                            is StructuredNote.Memo -> note.tags.any { it.contains(filter, true) }
                            is StructuredNote.Free -> note.tags.any { it.contains(filter, true) }
                            else -> false
                        }
                    }.forEach { note ->
                        val (text, tags) = when (note) {
                            is StructuredNote.Memo -> note.text to note.tags
                            is StructuredNote.Free -> note.text to note.tags
                            else -> "" to emptyList()
                        }
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(text)
                            Row {
                                tags.forEach { tag ->
                                    AssistChip(
                                        onClick = { filter = tag },
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

        LogSection(logs)
    }
}
