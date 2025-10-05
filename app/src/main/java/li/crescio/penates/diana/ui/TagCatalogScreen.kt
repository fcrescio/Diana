package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import li.crescio.penates.diana.R
import li.crescio.penates.diana.tags.EditableLabel
import li.crescio.penates.diana.tags.EditableTag
import li.crescio.penates.diana.tags.TagCatalogUiState

@Composable
fun TagCatalogScreen(
    state: TagCatalogUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddTag: () -> Unit,
    onDeleteTag: (String) -> Unit,
    onTagIdChange: (String, String) -> Unit,
    onTagColorChange: (String, String) -> Unit,
    onAddLabel: (String) -> Unit,
    onDeleteLabel: (String, String) -> Unit,
    onLabelLocaleChange: (String, String, String) -> Unit,
    onLabelValueChange: (String, String, String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.manage_tags)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.loadError != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.tag_catalog_loading_error),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.tags.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.tag_catalog_empty),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        items(state.tags, key = { it.key }) { tag ->
                            TagEditorCard(
                                tag = tag,
                                onTagIdChange = { value -> onTagIdChange(tag.key, value) },
                                onTagColorChange = { value -> onTagColorChange(tag.key, value) },
                                onAddLabel = { onAddLabel(tag.key) },
                                onDeleteLabel = { labelKey -> onDeleteLabel(tag.key, labelKey) },
                                onLabelLocaleChange = { labelKey, value ->
                                    onLabelLocaleChange(tag.key, labelKey, value)
                                },
                                onLabelValueChange = { labelKey, value ->
                                    onLabelValueChange(tag.key, labelKey, value)
                                },
                                onDeleteTag = { onDeleteTag(tag.key) }
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.saveSuccess) {
                        Text(
                            text = stringResource(R.string.tag_catalog_saved),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.saveError != null) {
                        Text(
                            text = stringResource(R.string.tag_catalog_save_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = onAddTag,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.add_tag))
                    }
                    Button(
                        onClick = onSave,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun TagEditorCard(
    tag: EditableTag,
    onTagIdChange: (String) -> Unit,
    onTagColorChange: (String) -> Unit,
    onAddLabel: () -> Unit,
    onDeleteLabel: (String) -> Unit,
    onLabelLocaleChange: (String, String) -> Unit,
    onLabelValueChange: (String, String) -> Unit,
    onDeleteTag: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = tag.id,
                onValueChange = onTagIdChange,
                label = { Text(stringResource(R.string.tag_id_label)) },
                isError = tag.validation.idMissing || tag.validation.idDuplicate,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    when {
                        tag.validation.idMissing -> Text(stringResource(R.string.tag_validation_id_required))
                        tag.validation.idDuplicate -> Text(stringResource(R.string.tag_validation_id_duplicate))
                    }
                }
            )
            OutlinedTextField(
                value = tag.color,
                onValueChange = onTagColorChange,
                label = { Text(stringResource(R.string.tag_color_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.tag_labels_title),
                style = MaterialTheme.typography.titleSmall
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tag.labels.forEach { label ->
                    LabelRow(
                        label = label,
                        hasLocaleError = tag.validation.labelLocaleDuplicate.contains(label.key),
                        hasValueError = tag.validation.labelValueMissing.contains(label.key),
                        onLocaleChange = { value -> onLabelLocaleChange(label.key, value) },
                        onValueChange = { value -> onLabelValueChange(label.key, value) },
                        onDelete = { onDeleteLabel(label.key) }
                    )
                }
                if (tag.validation.englishFallbackMissing) {
                    Text(
                        text = stringResource(R.string.tag_validation_english_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onAddLabel) {
                    Text(stringResource(R.string.add_label))
                }
            }
            TextButton(
                onClick = onDeleteTag,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete_tag)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.delete_tag))
            }
        }
    }
}

@Composable
private fun LabelRow(
    label: EditableLabel,
    hasLocaleError: Boolean,
    hasValueError: Boolean,
    onLocaleChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = label.locale,
            onValueChange = onLocaleChange,
            label = { Text(stringResource(R.string.tag_label_locale_hint)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = hasLocaleError,
            supportingText = {
                if (hasLocaleError) {
                    Text(
                        text = stringResource(R.string.tag_validation_locale_duplicate),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        )
        OutlinedTextField(
            value = label.value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.tag_label_value_label)) },
            modifier = Modifier.weight(2f),
            singleLine = true,
            isError = hasValueError,
            supportingText = {
                if (hasValueError) {
                    Text(
                        text = stringResource(R.string.tag_validation_label_required),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete_label)
            )
        }
    }
}
