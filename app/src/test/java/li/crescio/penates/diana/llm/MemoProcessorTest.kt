package li.crescio.penates.diana.llm

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import li.crescio.penates.diana.notes.Memo
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Locale

class MemoProcessorTest {
    @Test
    fun process_updatesSummaries_andLogs() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        fun completion(updated: String): String {
            val message = JSONObject().put("content", "{\"updated\":\"$updated\"}")
            val choice = JSONObject().put("message", message)
            val choices = JSONArray().put(choice)
            return JSONObject().put("choices", choices).toString()
        }
        server.enqueue(MockResponse().setBody(completion("todo updated")).setResponseCode(200))
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
        verify(exactly = 3) { logger.log(any(), any()) }

        server.shutdown()
    }

    @Test
    fun process_non2xxStatus_throwsIOException() = runBlocking {
        System.setProperty("net.bytebuddy.experimental", "true")

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
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
            assertEquals("HTTP 500 Server Error", e.message)
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
