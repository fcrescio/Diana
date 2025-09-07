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
import org.junit.Test
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
}
