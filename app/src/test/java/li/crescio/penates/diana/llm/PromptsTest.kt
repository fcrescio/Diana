package li.crescio.penates.diana.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.io.IOException

class PromptsTest {
    private fun load(path: String): String {
        val stream = this::class.java.classLoader?.getResourceAsStream(path)
            ?: throw IOException("Resource $path not found")
        return stream.bufferedReader().use { it.readText().trim() }
    }
    @Test
    fun forLocale_returnsItalianPrompts() {
        val prompts = Prompts.forLocale(Locale("it"))
        assertEquals(load("llm/prompts/it/todo.txt"), prompts.todo)
        assertEquals(load("llm/prompts/it/appointments.txt"), prompts.appointments)
        assertEquals(load("llm/prompts/it/thoughts.txt"), prompts.thoughts)
        assertEquals(load("llm/prompts/it/system.txt"), prompts.systemTemplate)
        assertEquals(load("llm/prompts/it/user.txt"), prompts.userTemplate)
    }

    @Test
    fun forLocale_returnsFrenchPrompts() {
        val prompts = Prompts.forLocale(Locale("fr"))
        assertEquals(load("llm/prompts/fr/todo.txt"), prompts.todo)
        assertEquals(load("llm/prompts/fr/appointments.txt"), prompts.appointments)
        assertEquals(load("llm/prompts/fr/thoughts.txt"), prompts.thoughts)
        assertEquals(load("llm/prompts/fr/system.txt"), prompts.systemTemplate)
        assertEquals(load("llm/prompts/fr/user.txt"), prompts.userTemplate)
    }

    @Test
    fun forLocale_returnsDefaultPrompts() {
        val prompts = Prompts.forLocale(Locale("en"))
        assertEquals(load("llm/prompts/en/todo.txt"), prompts.todo)
        assertEquals(load("llm/prompts/en/appointments.txt"), prompts.appointments)
        assertEquals(load("llm/prompts/en/thoughts.txt"), prompts.thoughts)
        assertEquals(load("llm/prompts/en/system.txt"), prompts.systemTemplate)
        assertEquals(load("llm/prompts/en/user.txt"), prompts.userTemplate)
    }
}

