package li.crescio.penates.diana.llm

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.llm.TodoItem
import li.crescio.penates.diana.llm.Thought
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Locale
import li.crescio.penates.diana.tags.LocalizedLabel
import li.crescio.penates.diana.tags.TagCatalog
import li.crescio.penates.diana.tags.TagDefinition

class MemoProcessorTest {
    private val defaultCatalog = createCatalog(
        "tag" to "General",
        "planning" to "Planning",
        "reflection" to "Reflection",
    )

    @Test
    fun process_updatesSummaries_andLogs() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun completionTodo(vararg items: JSONObject): String {
            val itemsArr = JSONArray()
            items.forEach { itemsArr.put(it) }
            val content = JSONObject()
                .put("date", "2024-01-01")
                .put("items", itemsArr)
            val message = JSONObject().put("content", content.toString())
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        fun appointmentCompletion(updated: String): String {
            val message = JSONObject().put("content", "{\"updated\":\"$updated\"}")
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        fun thoughtCompletion(markdown: String, sections: JSONArray, items: JSONArray): String {
            val payload = JSONObject()
                .put("updated_markdown", markdown)
                .put("sections", sections)
                .put("items", items)
            val message = JSONObject().put("content", payload.toString())
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        val todoItem = JSONObject()
            .put("op", "add")
            .put("text", "todo updated")
            .put("status", "not_started")
            .put("tags", JSONArray().put("tag"))
        val thoughtSections = JSONArray().put(
            JSONObject()
                .put("title", "Overview")
                .put("level", 1)
                .put("anchor", "overview")
                .put("children", JSONArray())
        )
        val thoughtItems = JSONArray().put(
            JSONObject()
                .put("text", "Review the new plan")
                .put("tags", JSONArray().put("planning"))
        )
        val thoughtMarkdown = "# Overview\n\nDetailed notes."
        server.enqueue(MockResponse().setBody(completionTodo(todoItem)).setResponseCode(200))
        server.enqueue(MockResponse().setBody(appointmentCompletion("appointments updated")).setResponseCode(200))
        server.enqueue(
            MockResponse()
                .setBody(thoughtCompletion(thoughtMarkdown, thoughtSections, thoughtItems))
                .setResponseCode(200)
        )
        server.start()

        val logger = mockk<LlmLogger>(relaxed = true)
        val processor = MemoProcessor(
            apiKey = "key",
            logger = logger,
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        val summary = processor.process(Memo("sample"))

        assertEquals("todo updated", summary.todo)
        assertEquals("appointments updated", summary.appointments)
        assertEquals(thoughtMarkdown, summary.thoughts)
        assertTrue(summary.appointmentItems.isEmpty())
        val document = summary.thoughtDocument
        requireNotNull(document)
        assertEquals(thoughtMarkdown, document.markdownBody)
        assertEquals(1, document.outline.sections.size)
        assertEquals("overview", document.outline.sections.first().anchor)
        assertEquals(listOf(Thought("Review the new plan", listOf("planning"))), summary.thoughtItems)
        verify(exactly = 3) { logger.log(any(), any()) }

        server.shutdown()
    }

    @Test
    fun process_appendsTodoItems_afterInitialization() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun completion(vararg items: JSONObject): String {
            val itemsArr = JSONArray()
            items.forEach { itemsArr.put(it) }
            val content = JSONObject()
                .put("date", "2024-01-01")
                .put("items", itemsArr)
            val message = JSONObject().put("content", content.toString())
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        val item2 = JSONObject()
            .put("op", "add")
            .put("text", "second")
            .put("status", "not_started")
            .put("tags", JSONArray().put("tag"))
        server.enqueue(MockResponse().setBody(completion(item2)).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        val initial = MemoSummary(
            todo = "first",
            appointments = "",
            thoughts = "",
            todoItems = listOf(TodoItem("first", "not_started", listOf("tag"))),
            appointmentItems = emptyList(),
            thoughtItems = emptyList()
        )
        processor.initialize(initial)

        val summary = processor.process(
            Memo("second"),
            processAppointments = false,
            processThoughts = false
        )

        assertEquals(
            listOf(
                TodoItem("first", "not_started", listOf("tag")),
                TodoItem("second", "not_started", listOf("tag"))
            ),
            summary.todoItems
        )

        server.shutdown()
    }

    @Test
    fun process_appendsTodoItems() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun completion(vararg items: JSONObject): String {
            val itemsArr = JSONArray()
            items.forEach { itemsArr.put(it) }
            val content = JSONObject()
                .put("date", "2024-01-01")
                .put("items", itemsArr)
            val message = JSONObject().put("content", content.toString())
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        val item1 = JSONObject()
            .put("op", "add")
            .put("text", "first")
            .put("status", "not_started")
            .put("tags", JSONArray().put("tag"))
        val item2 = JSONObject()
            .put("op", "add")
            .put("text", "second")
            .put("status", "not_started")
            .put("tags", JSONArray().put("tag"))
        server.enqueue(MockResponse().setBody(completion(item1)).setResponseCode(200))
        server.enqueue(MockResponse().setBody(completion(item2)).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        processor.process(Memo("first"), processAppointments = false, processThoughts = false)
        val summary = processor.process(Memo("second"), processAppointments = false, processThoughts = false)

        assertEquals(
            listOf(
                TodoItem("first", "not_started", listOf("tag")),
                TodoItem("second", "not_started", listOf("tag"))
            ),
            summary.todoItems
        )

        server.shutdown()
    }

    @Test
    fun process_handlesSpecialCharacters() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun completion(vararg items: JSONObject): String {
            val itemsArr = JSONArray()
            items.forEach { itemsArr.put(it) }
            val content = JSONObject()
                .put("date", "2024-01-01")
                .put("items", itemsArr)
            val message = JSONObject().put("content", content.toString())
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        val dummy = JSONObject()
            .put("op", "add")
            .put("text", "x")
            .put("status", "not_started")
            .put("tags", JSONArray().put("tag"))
        server.enqueue(MockResponse().setBody(completion(dummy)).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        val tricky = "first \"quoted\" \\slash"
        val summary = MemoSummary(
            todo = tricky,
            appointments = "",
            thoughts = "",
            todoItems = listOf(TodoItem(tricky, "not_started", listOf("tag"))),
            appointmentItems = emptyList(),
            thoughtItems = emptyList(),
        )
        processor.initialize(summary)

        val memoText = "memo \"quote\" \\backslash"
        processor.process(Memo(memoText), processAppointments = false, processThoughts = false)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        val obj = JSONObject(body)
        val content = obj.getJSONArray("messages").getJSONObject(1).getString("content")

        assertTrue(content.contains(memoText))
        val expectedPrior = JSONObject().apply {
            put("markdown_body", tricky)
            put("sections", JSONArray())
            val itemsArr = JSONArray()
            val itemObj = JSONObject()
                .put("text", tricky)
                .put("status", "not_started")
                .put("tags", JSONArray().put("tag"))
            itemsArr.put(itemObj)
            put("items", itemsArr)
        }.toString()
        assertTrue(content.contains(expectedPrior))
        assertTrue(content.contains("- tag: General"))
        assertTrue(content.contains("- planning: Planning"))
        val schemaObj = obj
            .getJSONObject("response_format")
            .getJSONObject("json_schema")
        val enumValues = schemaObj
            .getJSONObject("schema")
            .getJSONObject("properties")
            .getJSONObject("items")
            .getJSONObject("items")
            .getJSONObject("properties")
            .getJSONObject("tags")
            .getJSONObject("items")
            .getJSONArray("enum")
        val values = (0 until enumValues.length()).map { enumValues.getString(it) }
        assertEquals(listOf("tag", "planning", "reflection"), values)

        server.shutdown()
    }

    @Test
    fun process_dropsUnknownTags_andLogsFallback() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        val server = MockWebServer()
        try {
            val todoItem = JSONObject()
                .put("op", "add")
                .put("text", "Unknown tag task")
                .put("status", "not_started")
                .put("tags", JSONArray().put("mystery"))
            val todoContent = JSONObject()
                .put("date", "2024-01-02")
                .put("items", JSONArray().put(todoItem))
            val todoResponse = JSONObject()
                .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", todoContent.toString()))))

            val thoughtPayload = JSONObject()
                .put("updated_markdown", "## Notes\n- item")
                .put("sections", JSONArray())
                .put(
                    "items",
                    JSONArray().put(
                        JSONObject()
                            .put("text", "Consider the idea")
                            .put("tags", JSONArray().put("mystery"))
                    ),
                )
            val thoughtResponse = JSONObject()
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject().put("content", thoughtPayload.toString()),
                        ),
                    ),
                )

            server.enqueue(MockResponse().setBody(todoResponse.toString()).setResponseCode(200))
            server.enqueue(MockResponse().setBody(thoughtResponse.toString()).setResponseCode(200))
            server.start()

            val processor = MemoProcessor(
                apiKey = "key",
                logger = mockk(relaxed = true),
                locale = Locale.ENGLISH,
                baseUrl = server.url("/").toString(),
                client = OkHttpClient(),
                initialTagCatalog = createCatalog("tag" to "General"),
            )

            val summary = processor.process(
                Memo("unknown tags"),
                processAppointments = false,
            )

            assertEquals(listOf("tag"), summary.todoItems.first().tagIds)
            assertEquals(listOf("tag"), summary.thoughtItems.first().tagIds)
            verify(atLeast = 2) {
                Log.w("MemoProcessor", match { it.contains("Dropping disallowed tags") })
            }
            verify(atLeast = 2) {
                Log.w("MemoProcessor", match { it.contains("Mapping disallowed tags") })
            }
        } finally {
            server.shutdown()
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun process_populatesThoughtDocumentOutline() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        val childSection = JSONObject()
            .put("title", "Sub Topic")
            .put("level", 2)
            .put("anchor", "custom")
            .put("children", JSONArray())
        val rootSection = JSONObject()
            .put("title", "Main Topic")
            .put("level", 1)
            .put("anchor", "")
            .put("children", JSONArray().put(childSection))
        val sections = JSONArray().put(rootSection)
        val items = JSONArray().put(
            JSONObject()
                .put("text", "Reflect on sub topic")
                .put("tags", JSONArray().put("reflection"))
        )
        val payload = JSONObject()
            .put("updated_markdown", "# Main Topic\n\n## Sub Topic\n\nDetails.")
            .put("sections", sections)
            .put("items", items)
        val message = JSONObject().put("content", payload.toString())
        val choice = JSONObject().put("message", message)
        val body = JSONObject().put("choices", JSONArray().put(choice)).toString()
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        val summary = processor.process(
            Memo("new thought"),
            processTodos = false,
            processAppointments = false,
        )

        val outline = requireNotNull(summary.thoughtDocument).outline
        assertEquals(1, outline.sections.size)
        val root = outline.sections.first()
        assertEquals("main-topic", root.anchor)
        assertEquals(1, root.children.size)
        assertEquals("custom", root.children.first().anchor)
        assertEquals(listOf("reflection"), summary.thoughtItems.first().tagIds)

        server.shutdown()
    }

    @Test
    fun process_non2xxStatus_throwsIOException() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("error")) }
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        try {
            processor.process(Memo("sample"))
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 500"))
        }

        server.shutdown()
    }

    @Test
    fun process_blankContent_throwsIOException() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun completion(content: String): String {
            val message = JSONObject().put("content", content)
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        server.enqueue(MockResponse().setBody(completion(""))
            .setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        try {
            processor.process(Memo("sample"))
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Blank content", e.message)
        }

        server.shutdown()
    }

    @Test
    fun process_missingJson_throwsIOException() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun completion(content: String): String {
            val message = JSONObject().put("content", content)
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        server.enqueue(MockResponse().setBody(completion("plain text")).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        try {
            processor.process(Memo("sample"))
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("No JSON found", e.message)
        }

        server.shutdown()
    }

    @Test
    fun process_invalidJson_throwsIOException() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun completion(content: String): String {
            val message = JSONObject().put("content", content)
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        server.enqueue(MockResponse().setBody(completion("{invalid}")).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        try {
            processor.process(Memo("sample"))
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Invalid JSON", e.message)
        }

        server.shutdown()
    }

    @Test
    fun process_generatesDefaultAnchorsForOutline() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun thoughtCompletion(markdown: String, sections: JSONArray, items: JSONArray): String {
            val payload = JSONObject()
                .put("updated_markdown", markdown)
                .put("sections", sections)
                .put("items", items)
            val message = JSONObject().put("content", payload.toString())
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }

        val sections = JSONArray().put(
            JSONObject()
                .put("title", "Summary")
                .put("level", 1)
                .put("anchor", "")
                .put("children", JSONArray())
        )
        val items = JSONArray().put(
            JSONObject()
                .put("text", "Review findings")
                .put("tags", JSONArray())
        )
        val markdown = "# Summary\n\nFindings"
        server.enqueue(
            MockResponse()
                .setBody(thoughtCompletion(markdown, sections, items))
                .setResponseCode(200)
        )
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
            initialTagCatalog = defaultCatalog,
        )

        val summary = processor.process(
            Memo("summary"),
            processTodos = false,
            processAppointments = false,
        )

        val outline = requireNotNull(summary.thoughtDocument).outline.sections
        assertEquals(1, outline.size)
        assertEquals("summary", outline.first().anchor)

        server.shutdown()
    }

    private fun createCatalog(vararg entries: Pair<String, String>): TagCatalog {
        if (entries.isEmpty()) return TagCatalog(emptyList())
        val tags = entries.map { (id, label) ->
            TagDefinition(id, listOf(LocalizedLabel.create(null, label)))
        }
        return TagCatalog(tags)
    }
}
