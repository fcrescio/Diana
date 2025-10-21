package li.crescio.penates.diana.ui

import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import li.crescio.penates.diana.R
import li.crescio.penates.diana.llm.Appointment
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.ThoughtDocument
import li.crescio.penates.diana.notes.ThoughtOutlineSection
import li.crescio.penates.diana.notes.ResolvedTag
import li.crescio.penates.diana.tags.TagCatalog
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.LinkedHashSet
import java.util.Locale

private fun parseDate(dateString: String): LocalDate? {
    if (dateString.isBlank()) return null
    return runCatching { OffsetDateTime.parse(dateString).toLocalDate() }
        .recoverCatching { LocalDate.parse(dateString) }
        .getOrNull()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThoughtsSection(
    notes: List<StructuredNote>,
    thoughtDocument: ThoughtDocument?,
    tagCatalog: TagCatalog?,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
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

        val tagIndex = remember(notes, tagCatalog, locale) {
            buildSectionTagIndex(notes, tagCatalog, locale)
        }
        val outlineItems = remember(thoughtDocument) {
            thoughtDocument?.outline?.sections?.let { flattenOutline(it) } ?: emptyList()
        }

        if (thoughtDocument == null || outlineItems.isEmpty()) {
            val filteredNotes = notes.filter { note ->
                filter.isBlank() || when (note) {
                    is StructuredNote.Memo -> note.resolvedTags(tagCatalog, locale)
                        .any { it.label.contains(filter, ignoreCase = true) }
                    is StructuredNote.Free -> note.resolvedTags(tagCatalog, locale)
                        .any { it.label.contains(filter, ignoreCase = true) }
                    else -> false
                }
            }
            if (filteredNotes.isEmpty()) {
                Text(
                    text = if (notes.isEmpty()) {
                        stringResource(R.string.thoughts_outline_missing)
                    } else {
                        stringResource(R.string.thoughts_outline_no_match)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column {
                    filteredNotes.forEach { note ->
                        val (text, tags) = when (note) {
                            is StructuredNote.Memo -> note.text to note.resolvedTags(tagCatalog, locale)
                            is StructuredNote.Free -> note.text to note.resolvedTags(tagCatalog, locale)
                            else -> "" to emptyList()
                        }
                        if (text.isNotBlank()) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(text)
                                if (tags.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        tags.forEach { tag ->
                                            AssistChip(
                                                onClick = { filter = tag.label },
                                                label = { Text(tag.label) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (tagIndex.allTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.thoughts_tags_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                TagRow(tags = tagIndex.allTags, onTagClick = { filter = it.label })
            }
            return
        }

        var selectedAnchor by remember(thoughtDocument) {
            mutableStateOf(outlineItems.firstOrNull()?.section?.anchor)
        }

        val filteredOutline = remember(filter, outlineItems, tagIndex, locale) {
            if (filter.isBlank()) {
                outlineItems
            } else {
                val query = filter.trim().lowercase(locale)
                outlineItems.filter { item ->
                    tagIndex.tagsFor(item.section).any {
                        it.label.lowercase(locale).contains(query)
                    }
                }
            }
        }

        LaunchedEffect(filteredOutline) {
            selectedAnchor = when {
                filteredOutline.isEmpty() -> null
                filteredOutline.none { it.section.anchor == selectedAnchor } ->
                    filteredOutline.first().section.anchor
                else -> selectedAnchor
            }
        }

        if (filteredOutline.isEmpty()) {
            Text(
                text = stringResource(R.string.thoughts_outline_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (tagIndex.allTags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.thoughts_tags_label),
                    style = MaterialTheme.typography.labelLarge,
                )
            TagRow(tags = tagIndex.allTags, onTagClick = { filter = it.label })
            }
            return
        }

        val selectedSection = filteredOutline.firstOrNull { it.section.anchor == selectedAnchor }?.section
        val sectionMarkdown = remember(thoughtDocument, selectedSection) {
            selectedSection?.let { extractSectionMarkdown(thoughtDocument.markdownBody, it) }
                ?: thoughtDocument.markdownBody
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp)
                .padding(vertical = 8.dp)
        ) {
            OutlineList(
                sections = filteredOutline,
                selectedAnchor = selectedAnchor,
                onSelect = { anchor -> selectedAnchor = anchor },
                modifier = Modifier
                    .weight(0.35f)
                    .padding(end = 16.dp)
            )
            MarkdownViewer(
                markdown = sectionMarkdown,
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxHeight()
                    .testTag("thought-markdown")
            )
        }

        val tagsToShow = selectedSection?.let { tagIndex.tagsFor(it) }
            .takeUnless { it.isNullOrEmpty() }
            ?: tagIndex.allTags
        if (tagsToShow.isNotEmpty()) {
            Text(
                text = stringResource(R.string.thoughts_tags_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            TagRow(tags = tagsToShow, onTagClick = { filter = it.label })
        }
    }
}

@Composable
private fun OutlineList(
    sections: List<OutlineItem>,
    selectedAnchor: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sections, key = { it.section.anchor }) { item ->
            val anchor = item.section.anchor
            FilterChip(
                selected = anchor == selectedAnchor,
                onClick = { onSelect(anchor) },
                label = { Text(item.section.title) },
                modifier = Modifier.padding(start = (item.depth * 12).dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagRow(
    tags: Set<ResolvedTag>,
    onTagClick: (ResolvedTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val comparator = compareBy<ResolvedTag> { it.label.lowercase(Locale.getDefault()) }
            .thenBy { it.id }
        tags.sortedWith(comparator).forEach { tag ->
            AssistChip(
                onClick = { onTagClick(tag) },
                label = { Text(tag.label) }
            )
        }
    }
}

@Composable
private fun MarkdownViewer(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.builder(context).build() }
    AndroidView(
        modifier = modifier
            .semantics { this.text = AnnotatedString(markdown) },
        factory = { ctx ->
            ScrollView(ctx).apply {
                isFillViewport = true
                val textView = TextView(ctx).apply {
                    movementMethod = LinkMovementMethod.getInstance()
                }
                addView(
                    textView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        },
        update = { view ->
            val scrollView = view as ScrollView
            val textView = scrollView.getChildAt(0) as TextView
            val current = textView.tag as? String
            if (current != markdown) {
                markwon.setMarkdown(textView, markdown)
                textView.tag = markdown
                scrollView.scrollTo(0, 0)
            }
        }
    )
}

private data class OutlineItem(
    val section: ThoughtOutlineSection,
    val depth: Int,
)

private fun flattenOutline(
    sections: List<ThoughtOutlineSection>,
    depth: Int = 0,
): List<OutlineItem> {
    val items = mutableListOf<OutlineItem>()
    for (section in sections) {
        items += OutlineItem(section, depth)
        if (section.children.isNotEmpty()) {
            items += flattenOutline(section.children, depth + 1)
        }
    }
    return items
}

internal data class SectionTagIndex(
    val anchorTags: Map<String, Set<ResolvedTag>>,
    val titleTags: Map<String, Set<ResolvedTag>>,
    val freeTags: Set<ResolvedTag>,
) {
    val allTags: Set<ResolvedTag> = buildSet {
        anchorTags.values.forEach { addAll(it) }
        titleTags.values.forEach { addAll(it) }
        addAll(freeTags)
    }
}

internal fun SectionTagIndex.tagsFor(section: ThoughtOutlineSection): Set<ResolvedTag> {
    anchorTags[section.anchor]?.takeIf { it.isNotEmpty() }?.let { return it }
    titleTags[section.title]?.takeIf { it.isNotEmpty() }?.let { return it }
    return emptySet()
}

internal fun buildSectionTagIndex(
    notes: List<StructuredNote>,
    tagCatalog: TagCatalog? = null,
    locale: Locale = Locale.getDefault(),
): SectionTagIndex {
    val anchorTags = mutableMapOf<String, MutableSet<ResolvedTag>>()
    val titleTags = mutableMapOf<String, MutableSet<ResolvedTag>>()
    val freeTags = LinkedHashSet<ResolvedTag>()
    notes.forEach { note ->
        val tags = when (note) {
            is StructuredNote.Memo -> note.resolvedTags(tagCatalog, locale)
            is StructuredNote.Free -> note.resolvedTags(tagCatalog, locale)
            else -> emptyList()
        }
        if (tags.isEmpty()) return@forEach
        when (note) {
            is StructuredNote.Memo -> {
                note.sectionAnchor?.takeUnless { it.isBlank() }?.let { anchor ->
                    anchorTags.getOrPut(anchor) { LinkedHashSet() }.addAll(tags)
                }
                note.sectionTitle?.takeUnless { it.isBlank() }?.let { title ->
                    titleTags.getOrPut(title) { LinkedHashSet() }.addAll(tags)
                }
                if (note.sectionAnchor.isNullOrBlank() && note.sectionTitle.isNullOrBlank()) {
                    freeTags.addAll(tags)
                }
            }
            is StructuredNote.Free -> freeTags.addAll(tags)
            else -> Unit
        }
    }
    return SectionTagIndex(
        anchorTags.mapValues { it.value.toSet() },
        titleTags.mapValues { it.value.toSet() },
        freeTags.toSet(),
    )
}

internal fun extractSectionMarkdown(
    markdown: String,
    section: ThoughtOutlineSection,
): String {
    val level = section.level.coerceAtLeast(1)
    val headingPattern = Regex("^#{$level}\\s+${Regex.escape(section.title)}\\s*$", RegexOption.MULTILINE)
    val match = headingPattern.find(markdown) ?: return markdown
    val startIndex = match.range.first
    val searchStart = (match.range.last + 1).coerceAtMost(markdown.length)
    val nextHeadingPattern = Regex("^#{1,$level}\\s+.*$", RegexOption.MULTILINE)
    val nextMatch = nextHeadingPattern.find(markdown, searchStart)
    val endIndex = nextMatch?.range?.first ?: markdown.length
    return markdown.substring(startIndex, endIndex).trim()
}

@Composable
fun NotesListScreen(
    todoItems: List<TodoItem>,
    appointments: List<Appointment>,
    notes: List<StructuredNote>,
    thoughtDocument: ThoughtDocument?,
    tagCatalog: TagCatalog? = null,
    locale: Locale = Locale.getDefault(),
    logs: List<String>,
    showTodos: Boolean,
    showAppointments: Boolean,
    showThoughts: Boolean,
    modifier: Modifier = Modifier,
    onTodoCheckedChange: (TodoItem, Boolean) -> Unit,
    onTodoEdit: (TodoItem) -> Unit,
    onTodoDelete: (TodoItem) -> Unit,
    onAppointmentDelete: (Appointment) -> Unit,
    onTodoMove: (TodoItem) -> Unit,
    onAppointmentMove: (Appointment) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            if (showTodos) {
                item {
                    Text(stringResource(R.string.todo_list))
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
                        val sortedTodos = remember(todoItems) {
                            todoItems.sortedBy {
                                parseDate(it.dueDate.ifBlank { it.eventDate }) ?: LocalDate.MAX
                            }
                        }
                        sortedTodos.forEach { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                var expanded by remember { mutableStateOf(false) }
                                var showConfirm by remember { mutableStateOf(false) }
                                var showEditor by remember { mutableStateOf(false) }
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
                                        val baseColor = if (item.status == "done") {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                        val textColor = calculateUrgencyColor(
                                            item.dueDate.ifBlank { item.eventDate },
                                            baseColor
                                        )
                                        val decoration = if (item.status == "done") {
                                            TextDecoration.LineThrough
                                        } else {
                                            TextDecoration.None
                                        }
                                        Text(
                                            item.text,
                                            color = textColor,
                                            textDecoration = decoration
                                        )
                                        parseDate(item.dueDate.ifBlank { item.eventDate })?.let { date ->
                                            Text(
                                                date.format(dateFormatter),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = textColor,
                                                textDecoration = decoration
                                            )
                                        }
                                        val tags = remember(item, tagCatalog, locale) {
                                            item.resolvedTags(tagCatalog, locale)
                                        }
                                        Row {
                                            tags.forEach { tag ->
                                                AssistChip(
                                                    onClick = {},
                                                    label = {
                                                        Text(
                                                            tag.label,
                                                            color = textColor,
                                                            textDecoration = decoration
                                                        )
                                                    },
                                                    modifier = Modifier.padding(end = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                    Box {
                                        IconButton(onClick = { expanded = true }) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                                        }
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.edit)) },
                                                onClick = {
                                                    expanded = false
                                                    showEditor = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.move_to)) },
                                                onClick = {
                                                    expanded = false
                                                    onTodoMove(item)
                                                }
                                            )
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
                                if (showEditor) {
                                    TodoEditDialog(
                                        item = item,
                                        onDismiss = { showEditor = false },
                                        onConfirm = { updated ->
                                            onTodoEdit(updated)
                                            showEditor = false
                                        }
                                    )
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
            }
            if (showAppointments) {
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
                                            DropdownMenu(
                                                expanded = expanded,
                                                onDismissRequest = { expanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.move_to)) },
                                                    onClick = {
                                                        expanded = false
                                                        onAppointmentMove(appt)
                                                    }
                                                )
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
            }
            if (showThoughts) {
                item {
                    ThoughtsSection(
                        notes = notes,
                        thoughtDocument = thoughtDocument,
                        tagCatalog = tagCatalog,
                        locale = locale,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }
        }

        LogSection(logs)
    }
}

@Composable
private fun TodoEditDialog(
    item: TodoItem,
    onDismiss: () -> Unit,
    onConfirm: (TodoItem) -> Unit,
) {
    var text by remember(item) { mutableStateOf(item.text) }
    var dueDate by remember(item) { mutableStateOf(item.dueDate) }
    var eventDate by remember(item) { mutableStateOf(item.eventDate) }
    var tagIds by remember(item) { mutableStateOf(item.tagIds.joinToString(", ")) }
    var tagLabels by remember(item) { mutableStateOf(item.tagLabels.joinToString(", ")) }
    val canSave = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_todo_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.todo_text_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = { Text(stringResource(R.string.todo_due_date_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = eventDate,
                    onValueChange = { eventDate = it },
                    label = { Text(stringResource(R.string.todo_event_date_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tagIds,
                    onValueChange = { tagIds = it },
                    label = { Text(stringResource(R.string.todo_tag_ids_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tagLabels,
                    onValueChange = { tagLabels = it },
                    label = { Text(stringResource(R.string.todo_tag_labels_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = item.copy(
                        text = text.trim(),
                        dueDate = dueDate.trim(),
                        eventDate = eventDate.trim(),
                        tagIds = parseTagInput(tagIds),
                        tagLabels = parseTagInput(tagLabels),
                    )
                    onConfirm(updated)
                },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun parseTagInput(value: String): List<String> {
    if (value.isBlank()) return emptyList()
    return value.split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

