package li.crescio.penates.diana.ui

import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.ThoughtOutlineSection
import li.crescio.penates.diana.tags.LocalizedLabel
import li.crescio.penates.diana.tags.TagCatalog
import li.crescio.penates.diana.tags.TagDefinition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ThoughtsSectionUtilsTest {
    @Test
    fun extractSectionMarkdown_returnsSectionWithNestedContent() {
        val markdown = """
            # One

            Intro

            ## Detail

            More info.

            # Two

            Follow up.
        """.trimIndent()
        val section = ThoughtOutlineSection("One", 1, "one")

        val result = extractSectionMarkdown(markdown, section)

        assertEquals(
            "# One\n\nIntro\n\n## Detail\n\nMore info.",
            result
        )
    }

    @Test
    fun buildSectionTagIndex_prioritizesAnchorsAndAggregatesTags() {
        val notes = listOf(
            StructuredNote.Memo(
                text = "Anchor note",
                tagIds = listOf("anchor"),
                sectionAnchor = "intro",
                sectionTitle = "Intro",
            ),
            StructuredNote.Memo(
                text = "Title note",
                tagIds = listOf("title"),
                sectionTitle = "Intro",
            ),
            StructuredNote.Free(
                text = "Loose",
                tagIds = listOf("free"),
            ),
        )

        val index = buildSectionTagIndex(notes)

        val anchorTags = index.tagsFor(ThoughtOutlineSection("Intro", 1, "intro"))
        assertEquals(setOf("anchor"), anchorTags.map { it.id }.toSet())

        val fallbackTags = index.tagsFor(ThoughtOutlineSection("Intro", 1, "missing"))
        assertEquals(setOf("anchor", "title"), fallbackTags.map { it.id }.toSet())

        assertEquals(setOf("anchor", "title", "free"), index.allTags.map { it.id }.toSet())
    }

    @Test
    fun buildSectionTagIndex_resolvesCatalogLabelsForLocale() {
        val notes = listOf(
            StructuredNote.Memo(
                text = "Localized",
                tagIds = listOf("anchor"),
                sectionAnchor = "intro",
            )
        )
        val catalog = TagCatalog(
            listOf(
                TagDefinition(
                    id = "anchor",
                    labels = listOf(
                        LocalizedLabel.create("en", "Anchor"),
                        LocalizedLabel.create("fr", "Ancre")
                    )
                )
            )
        )

        val index = buildSectionTagIndex(notes, catalog, Locale.FRENCH)

        val resolved = index.tagsFor(ThoughtOutlineSection("Intro", 1, "intro")).single()
        assertEquals("anchor", resolved.id)
        assertEquals("Ancre", resolved.label)
    }
}
