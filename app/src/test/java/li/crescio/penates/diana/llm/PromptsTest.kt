package li.crescio.penates.diana.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PromptsTest {
    @Test
    fun forLocale_returnsItalianPrompts() {
        val prompts = Prompts.forLocale(Locale("it"))
        assertEquals("lista di cose da fare", prompts.todo)
        assertEquals("lista degli appuntamenti", prompts.appointments)
        assertEquals("pensieri e note", prompts.thoughts)
        assertEquals("Gestisci un documento {aspect}. Restituisci solo JSON.", prompts.systemTemplate)
        val expectedUserTemplate = "Stato attuale della {aspect}:\n{prior}\n\nData odierna: {today}\n\nNuovo memo:\n{memo}\n\nRestituisci la {aspect} aggiornata nel campo 'updated', nella stessa lingua del nuovo memo."
        assertEquals(expectedUserTemplate, prompts.userTemplate)
    }

    @Test
    fun forLocale_returnsFrenchPrompts() {
        val prompts = Prompts.forLocale(Locale("fr"))
        assertEquals("liste de tâches", prompts.todo)
        assertEquals("liste des rendez-vous", prompts.appointments)
        assertEquals("pensées et notes", prompts.thoughts)
        assertEquals("Vous maintenez un document de {aspect}. Retournez uniquement du JSON.", prompts.systemTemplate)
        val expectedUserTemplate = "État actuel de la {aspect}:\n{prior}\n\nDate du jour: {today}\n\nNouveau mémo:\n{memo}\n\nRetournez la {aspect} mise à jour dans le champ 'updated', dans la même langue que le nouveau mémo."
        assertEquals(expectedUserTemplate, prompts.userTemplate)
    }

    @Test
    fun forLocale_returnsDefaultPrompts() {
        val prompts = Prompts.forLocale(Locale("en"))
        assertEquals("to-do list", prompts.todo)
        assertEquals("appointments list", prompts.appointments)
        assertEquals("thoughts and notes", prompts.thoughts)
        assertEquals("You maintain a {aspect} document. Return only JSON.", prompts.systemTemplate)
        val expectedUserTemplate = "Current {aspect}:\n{prior}\n\nToday's date: {today}\n\nNew memo:\n{memo}\n\nReturn the updated {aspect} in the field 'updated', in the same language as the new memo."
        assertEquals(expectedUserTemplate, prompts.userTemplate)
    }
}

