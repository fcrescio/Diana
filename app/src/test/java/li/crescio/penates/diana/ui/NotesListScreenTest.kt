package li.crescio.penates.diana.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.notes.ThoughtDocument
import li.crescio.penates.diana.notes.ThoughtOutline
import li.crescio.penates.diana.notes.ThoughtOutlineSection
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotesListScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun doneTodo_showsStrikethrough() {
        val item = TodoItem(
            text = "Finished task",
            status = "done",
            tagIds = listOf("tag"),
        )
        composeTestRule.setContent {
            NotesListScreen(
                todoItems = listOf(item),
                appointments = emptyList(),
                notes = emptyList(),
                thoughtDocument = null,
                logs = emptyList(),
                showTodos = true,
                showAppointments = false,
                showThoughts = false,
                onTodoCheckedChange = { _, _ -> },
                onTodoEdit = {},
                onTodoDelete = {},
                onAppointmentDelete = {},
                onTodoMove = {},
                onAppointmentMove = {},
            )
        }

        composeTestRule.onNodeWithText("Finished task").assertIsDisplayed()
        val annotated = composeTestRule.onNodeWithText("Finished task")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text].first()
        assertTrue(annotated.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun thoughtOutline_updatesMarkdownOnSelection() {
        val markdown = """
            # Overview

            Intro details.

            ## Deep Dive

            More details.

            # Next Steps

            What to do.
        """.trimIndent()
        val document = ThoughtDocument(
            markdownBody = markdown,
            outline = ThoughtOutline(
                listOf(
                    ThoughtOutlineSection(
                        title = "Overview",
                        level = 1,
                        anchor = "overview",
                        children = listOf(
                            ThoughtOutlineSection("Deep Dive", 2, "deep-dive")
                        ),
                    ),
                    ThoughtOutlineSection("Next Steps", 1, "next-steps"),
                )
            )
        )

        composeTestRule.setContent {
            NotesListScreen(
                todoItems = emptyList(),
                appointments = emptyList(),
                notes = emptyList(),
                thoughtDocument = document,
                logs = emptyList(),
                showTodos = false,
                showAppointments = false,
                showThoughts = true,
                onTodoCheckedChange = { _, _ -> },
                onTodoEdit = {},
                onTodoDelete = {},
                onAppointmentDelete = {},
                onTodoMove = {},
                onAppointmentMove = {},
            )
        }

        val markdownNode = composeTestRule.onNodeWithTag("thought-markdown")
        markdownNode.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Text,
                listOf(
                    AnnotatedString(
                        "# Overview\n\nIntro details.\n\n## Deep Dive\n\nMore details."
                    )
                )
            )
        )

        composeTestRule.onNodeWithText("Next Steps").performClick()

        markdownNode.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Text,
                listOf(AnnotatedString("# Next Steps\n\nWhat to do."))
            )
        )
    }
}
