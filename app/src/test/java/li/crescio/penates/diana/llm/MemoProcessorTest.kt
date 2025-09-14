package li.crescio.penates.diana.llm

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.llm.TodoItem
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

class MemoProcessorTest {
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
        fun completion(updated: String): String {
            val message = JSONObject().put("content", "{\"updated\":\"$updated\"}")
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        val todoItem = JSONObject()
            .put("op", "add")
            .put("text", "todo updated")
            .put("status", "not_started")
            .put("tags", JSONArray())
        server.enqueue(MockResponse().setBody(completionTodo(todoItem)).setResponseCode(200))
        server.enqueue(MockResponse().setBody(completion("appointments updated")).setResponseCode(200))
        server.enqueue(MockResponse().setBody(completion("thoughts updated")).setResponseCode(200))
        server.start()

        val logger = mockk<LlmLogger>(relaxed = true)
        val processor = MemoProcessor(
            apiKey = "key",
            logger = logger,
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient()
        )

        val summary = processor.process(Memo("sample"))

        assertEquals("todo updated", summary.todo)
        assertEquals("appointments updated", summary.appointments)
        assertEquals("thoughts updated", summary.thoughts)
        assertTrue(summary.appointmentItems.isEmpty())
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
            .put("tags", JSONArray())
        server.enqueue(MockResponse().setBody(completion(item2)).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
        )

        val initial = MemoSummary(
            todo = "first",
            appointments = "",
            thoughts = "",
            todoItems = listOf(TodoItem("first", "not_started", emptyList())),
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
                TodoItem("first", "not_started", emptyList()),
                TodoItem("second", "not_started", emptyList())
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
            .put("tags", JSONArray())
        val item2 = JSONObject()
            .put("op", "add")
            .put("text", "second")
            .put("status", "not_started")
            .put("tags", JSONArray())
        server.enqueue(MockResponse().setBody(completion(item1)).setResponseCode(200))
        server.enqueue(MockResponse().setBody(completion(item2)).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
        )

        processor.process(Memo("first"), processAppointments = false, processThoughts = false)
        val summary = processor.process(Memo("second"), processAppointments = false, processThoughts = false)

        assertEquals(
            listOf(
                TodoItem("first", "not_started", emptyList()),
                TodoItem("second", "not_started", emptyList())
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
            .put("tags", JSONArray())
        server.enqueue(MockResponse().setBody(completion(dummy)).setResponseCode(200))
        server.start()

        val processor = MemoProcessor(
            apiKey = "key",
            logger = mockk(relaxed = true),
            locale = Locale.ENGLISH,
            baseUrl = server.url("/").toString(),
            client = OkHttpClient(),
        )

        val tricky = "first \"quoted\" \\slash"
        val summary = MemoSummary(
            todo = tricky,
            appointments = "",
            thoughts = "",
            todoItems = listOf(TodoItem(tricky, "not_started", emptyList())),
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
            val itemsArr = JSONArray()
            val itemObj = JSONObject()
                .put("text", tricky)
                .put("status", "not_started")
                .put("tags", JSONArray())
            itemsArr.put(itemObj)
            put("items", itemsArr)
        }.toString()
        assertTrue(content.contains(expectedPrior))

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
            client = OkHttpClient()
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
            client = OkHttpClient()
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
            client = OkHttpClient()
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
            client = OkHttpClient()
        )

        try {
            processor.process(Memo("sample"))
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Invalid JSON", e.message)
        }

        server.shutdown()
    }
}
