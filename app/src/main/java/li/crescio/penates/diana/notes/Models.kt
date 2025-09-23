package li.crescio.penates.diana.notes

import li.crescio.penates.diana.tags.TagCatalog
import java.util.LinkedHashMap
import java.util.Locale

data class RawRecording(val filePath: String)

data class Transcript(val text: String)

/** A memo consists of the written text and, optionally, the path to the
 * original audio that produced it. */
data class Memo(
    val text: String,
    val audioPath: String? = null
)

sealed class StructuredNote(open val createdAt: Long) {
    interface Tagged {
        val tagIds: List<String>
        val tagLabels: List<String>

        fun unresolvedTagLabels(): List<String> = tagLabels

        fun displayTags(): List<String> =
            if (tagLabels.isNotEmpty()) tagLabels else tagIds

        val tags: List<String>
            get() = displayTags()

        fun resolvedTagLabels(
            catalog: TagCatalog?,
            locale: Locale = Locale.getDefault(),
        ): List<String> = resolvedTags(catalog, locale).map { it.label }

        fun resolvedTags(
            catalog: TagCatalog?,
            locale: Locale = Locale.getDefault(),
        ): List<ResolvedTag> {
            val resolved = LinkedHashMap<String, ResolvedTag>()
            val definitions = catalog?.tags?.associateBy { it.id }
            tagIds.forEach { rawId ->
                val id = rawId.trim()
                if (id.isEmpty()) return@forEach
                val definition = definitions?.get(id)
                val label = definition?.labelForLocale(locale)
                    ?: definition?.labels?.firstOrNull()?.value
                    ?: id
                val entry = ResolvedTag(id, label)
                resolved.putIfAbsent(entry.id, entry)
            }
            tagLabels.forEach { label ->
                val trimmed = label.trim()
                if (trimmed.isNotEmpty()) {
                    val entry = ResolvedTag(trimmed, trimmed)
                    resolved.putIfAbsent(entry.id, entry)
                }
            }
            return resolved.values.toList()
        }
    }

    data class ToDo(
        val text: String,
        val status: String = "",
        override val tagIds: List<String> = emptyList(),
        override val tagLabels: List<String> = emptyList(),
        val dueDate: String = "",
        val eventDate: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        val id: String = ""
    ) : StructuredNote(createdAt), Tagged

    data class Memo(
        val text: String,
        override val tagIds: List<String> = emptyList(),
        override val tagLabels: List<String> = emptyList(),
        val sectionAnchor: String? = null,
        val sectionTitle: String? = null,
        override val createdAt: Long = System.currentTimeMillis(),
    ) : StructuredNote(createdAt), Tagged

    data class Event(
        val text: String,
        val datetime: String,
        val location: String = "",
        override val createdAt: Long = System.currentTimeMillis()
    ) : StructuredNote(createdAt)

    data class Free(
        val text: String,
        override val tagIds: List<String> = emptyList(),
        override val tagLabels: List<String> = emptyList(),
        override val createdAt: Long = System.currentTimeMillis()
    ) : StructuredNote(createdAt), Tagged
}

data class NoteCollection(val notes: List<StructuredNote>)

data class ResolvedTag(
    val id: String,
    val label: String,
)
