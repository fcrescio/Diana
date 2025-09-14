package li.crescio.penates.diana.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import li.crescio.penates.diana.llm.TodoItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class NotesListScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun doneTodo_showsStrikethrough() {
        val item = TodoItem(
            text = "Finished task",
            status = "done",
            tags = listOf("tag"),
        )
        composeTestRule.setContent {
            NotesListScreen(
                todoItems = listOf(item),
                appointments = emptyList(),
                notes = emptyList(),
                logs = emptyList(),
                showTodos = true,
                showAppointments = false,
                showThoughts = false,
                onTodoCheckedChange = { _, _ -> },
                onTodoDelete = {},
                onAppointmentDelete = {}
            )
        }

        composeTestRule.onNodeWithText("Finished task").assertIsDisplayed()
        val annotated = composeTestRule.onNodeWithText("Finished task")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text].first()
        assertTrue(annotated.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }
}
