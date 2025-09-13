package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    onTodoDelete: (TodoItem) -> Unit,
    onAppointmentDelete: (Appointment) -> Unit,
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
                            var expanded by remember { mutableStateOf(false) }
                            var showConfirm by remember { mutableStateOf(false) }
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
                                Box {
                                    IconButton(onClick = { expanded = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.delete)) },
                                            onClick = {
                                                expanded = false
                                                showConfirm = true
                                            }
                                        )
                                    }
                                }
                            }
                            if (showConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showConfirm = false },
                                    title = { Text(stringResource(R.string.delete_todo_title)) },
                                    text = { Text(stringResource(R.string.delete_todo_message)) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            onTodoDelete(item)
                                            showConfirm = false
                                        }) {
                                            Text(stringResource(R.string.delete))
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showConfirm = false }) {
                                            Text(stringResource(R.string.cancel))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.appointments))
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    val grouped = appointments.groupBy { appt ->
                        runCatching { OffsetDateTime.parse(appt.datetime).toLocalDate().toString() }
                            .getOrElse {
                                if (appt.datetime.length >= 10) appt.datetime.substring(0, 10) else appt.datetime
                            }
                    }.toSortedMap()
                    grouped.forEach { (date, appts) ->
                        Text(date)
                        appts.sortedBy { it.datetime }.forEach { appt ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                var expanded by remember { mutableStateOf(false) }
                                var showConfirm by remember { mutableStateOf(false) }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    ) {
                                        val time = runCatching {
                                            OffsetDateTime.parse(appt.datetime)
                                                .format(DateTimeFormatter.ofPattern("HH:mm"))
                                        }.getOrElse { appt.datetime }
                                        val location = if (appt.location.isNotBlank()) " @ ${appt.location}" else ""
                                        Text("$time ${appt.text}$location")
                                    }
                                    Box {
                                        IconButton(onClick = { expanded = true }) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                                        }
                                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.delete)) },
                                                onClick = {
                                                    expanded = false
                                                    showConfirm = true
                                                }
                                            )
                                        }
                                    }
                                }
                                if (showConfirm) {
                                    AlertDialog(
                                        onDismissRequest = { showConfirm = false },
                                        title = { Text(stringResource(R.string.delete_appointment_title)) },
                                        text = { Text(stringResource(R.string.delete_appointment_message)) },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                onAppointmentDelete(appt)
                                                showConfirm = false
                                            }) {
                                                Text(stringResource(R.string.delete))
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showConfirm = false }) {
                                                Text(stringResource(R.string.cancel))
                                            }
                                        }
                                    )
                                }
                            }
                        }
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
