package li.crescio.penates.diana.ui

import li.crescio.penates.diana.notes.StructuredNote
import li.crescio.penates.diana.notes.ThoughtOutlineSection
import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals(setOf("anchor"), anchorTags)

        val fallbackTags = index.tagsFor(ThoughtOutlineSection("Intro", 1, "missing"))
        assertEquals(setOf("title"), fallbackTags)

        assertEquals(setOf("anchor", "title", "free"), index.allTags)
    }
}
