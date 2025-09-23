package li.crescio.penates.diana.tags

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val ENGLISH_LANGUAGE_TAG = "en"

class TagCatalogViewModel(
    private val repository: TagCatalogDataSource,
    coroutineScope: CoroutineScope? = null,
) {
    private val ownsScope = coroutineScope == null
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(TagCatalogUiState())
    val uiState: StateFlow<TagCatalogUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { current ->
            current.copy(isLoading = true, loadError = null, saveSuccess = false)
        }
        scope.launch {
            try {
                val catalog = repository.loadCatalog()
                val editable = catalog.tags.map { it.toEditableTag() }
                _uiState.update {
                    it.copy(
                        tags = applyValidation(editable),
                        isLoading = false,
                        loadError = null,
                        saveSuccess = false,
                        saveError = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        tags = emptyList(),
                        isLoading = false,
                        loadError = e.message ?: e.javaClass.simpleName,
                        saveSuccess = false,
                    )
                }
            }
        }
    }

    fun addTag() {
        updateTags { tags ->
            val newTag = EditableTag(
                key = newTagKey(),
                id = "",
                color = "",
                labels = listOf(
                    EditableLabel(
                        key = newLabelKey(),
                        locale = ENGLISH_LANGUAGE_TAG,
                        value = "",
                    )
                ),
            )
            tags + newTag
        }
    }

    fun deleteTag(tagKey: String) {
        updateTags { tags -> tags.filterNot { it.key == tagKey } }
    }

    fun updateTagId(tagKey: String, newId: String) {
        updateTags { tags ->
            tags.map { tag ->
                if (tag.key == tagKey) tag.copy(id = newId) else tag
            }
        }
    }

    fun updateTagColor(tagKey: String, newColor: String) {
        updateTags { tags ->
            tags.map { tag ->
                if (tag.key == tagKey) tag.copy(color = newColor) else tag
            }
        }
    }

    fun addLabel(tagKey: String) {
        updateTags { tags ->
            tags.map { tag ->
                if (tag.key != tagKey) {
                    tag
                } else {
                    tag.copy(labels = tag.labels + EditableLabel(newLabelKey(), locale = "", value = ""))
                }
            }
        }
    }

    fun removeLabel(tagKey: String, labelKey: String) {
        updateTags { tags ->
            tags.map { tag ->
                if (tag.key != tagKey) {
                    tag
                } else {
                    tag.copy(labels = tag.labels.filterNot { it.key == labelKey })
                }
            }
        }
    }

    fun updateLabelLocale(tagKey: String, labelKey: String, locale: String) {
        updateTags { tags ->
            tags.map { tag ->
                if (tag.key != tagKey) {
                    tag
                } else {
                    tag.copy(
                        labels = tag.labels.map { label ->
                            if (label.key == labelKey) label.copy(locale = locale) else label
                        }
                    )
                }
            }
        }
    }

    fun updateLabelValue(tagKey: String, labelKey: String, value: String) {
        updateTags { tags ->
            tags.map { tag ->
                if (tag.key != tagKey) {
                    tag
                } else {
                    tag.copy(
                        labels = tag.labels.map { label ->
                            if (label.key == labelKey) label.copy(value = value) else label
                        }
                    )
                }
            }
        }
    }

    fun save() {
        val current = _uiState.value
        if (current.isSaving) {
            return
        }
        val validated = applyValidation(current.tags)
        val hasErrors = validated.any { !it.validation.isValid }
        _uiState.value = current.copy(
            tags = validated,
            saveSuccess = false,
            saveError = null,
        )
        if (hasErrors) {
            return
        }
        val catalog = TagCatalog(validated.map { it.toDefinition() })
        _uiState.update { it.copy(isSaving = true) }
        scope.launch {
            try {
                val outcome = repository.saveCatalog(catalog)
                _uiState.update {
                    if (outcome.error != null) {
                        it.copy(
                            isSaving = false,
                            saveSuccess = false,
                            saveError = outcome.error.message ?: outcome.error.javaClass.simpleName,
                        )
                    } else {
                        it.copy(
                            isSaving = false,
                            saveSuccess = true,
                            saveError = null,
                            tags = applyValidation(validated),
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveSuccess = false,
                        saveError = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun dispose() {
        if (ownsScope) {
            scope.cancel()
        }
    }

    private fun updateTags(transform: (List<EditableTag>) -> List<EditableTag>) {
        _uiState.update { current ->
            val updated = transform(current.tags)
            current.copy(
                tags = applyValidation(updated),
                saveSuccess = false,
                saveError = null,
            )
        }
    }

    private fun applyValidation(tags: List<EditableTag>): List<EditableTag> {
        val idCounts = mutableMapOf<String, Int>()
        tags.forEach { tag ->
            val trimmed = tag.id.trim()
            if (trimmed.isNotEmpty()) {
                idCounts[trimmed] = (idCounts[trimmed] ?: 0) + 1
            }
        }
        return tags.map { tag ->
            val trimmedId = tag.id.trim()
            val localeToKeys = mutableMapOf<String?, MutableList<String>>()
            var hasEnglishFallback = false
            tag.labels.forEach { label ->
                val normalized = label.locale.trim().takeUnless { it.isBlank() }?.let { normalizeLocaleTag(it) }
                val key = normalized
                localeToKeys.getOrPut(key) { mutableListOf() }.add(label.key)
                val valuePresent = label.value.trim().isNotEmpty()
                if (valuePresent) {
                    if (normalized == null) {
                        hasEnglishFallback = true
                    } else if (normalized == ENGLISH_LANGUAGE_TAG || normalized.startsWith("$ENGLISH_LANGUAGE_TAG-")) {
                        hasEnglishFallback = true
                    }
                }
            }
            val duplicateLocales = localeToKeys.filter { it.value.size > 1 }.values.flatten().toSet()
            val emptyLabels = tag.labels.filter { it.value.trim().isEmpty() }.map { it.key }.toSet()
            val validation = TagValidation(
                idMissing = trimmedId.isEmpty(),
                idDuplicate = trimmedId.isNotEmpty() && (idCounts[trimmedId] ?: 0) > 1,
                englishFallbackMissing = !hasEnglishFallback,
                labelLocaleDuplicate = duplicateLocales,
                labelValueMissing = emptyLabels,
            )
            tag.copy(validation = validation)
        }
    }

    private fun TagDefinition.toEditableTag(): EditableTag {
        val editableLabels = labels.map { label ->
            EditableLabel(
                key = newLabelKey(),
                locale = label.localeTag ?: "",
                value = label.value,
            )
        }
        return EditableTag(
            key = newTagKey(),
            id = id,
            color = color ?: "",
            labels = editableLabels,
        )
    }

    private fun EditableTag.toDefinition(): TagDefinition {
        val sanitizedLabels = labels.map { label ->
            val locale = label.locale.trim().takeUnless { it.isBlank() }
            LocalizedLabel.create(locale, label.value.trim())
        }
        val colorValue = color.trim().takeUnless { it.isBlank() }
        return TagDefinition(
            id = id.trim(),
            labels = sanitizedLabels,
            color = colorValue,
        )
    }

    private fun newTagKey(): String = UUID.randomUUID().toString()
    private fun newLabelKey(): String = UUID.randomUUID().toString()
}

data class TagCatalogUiState(
    val tags: List<EditableTag> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val loadError: String? = null,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
) {
    val hasValidationErrors: Boolean get() = tags.any { !it.validation.isValid }
}

data class EditableTag(
    val key: String,
    val id: String,
    val color: String,
    val labels: List<EditableLabel>,
    val validation: TagValidation = TagValidation(),
)

data class EditableLabel(
    val key: String,
    val locale: String,
    val value: String,
)

data class TagValidation(
    val idMissing: Boolean = false,
    val idDuplicate: Boolean = false,
    val englishFallbackMissing: Boolean = false,
    val labelLocaleDuplicate: Set<String> = emptySet(),
    val labelValueMissing: Set<String> = emptySet(),
) {
    val isValid: Boolean
        get() = !idMissing && !idDuplicate && !englishFallbackMissing &&
            labelLocaleDuplicate.isEmpty() && labelValueMissing.isEmpty()
}
