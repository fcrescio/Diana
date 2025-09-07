package li.crescio.penates.diana.llm

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmLoggerTest {
    @Test
    fun log_keepsLatestEntries_andEmitsThroughFlow() = runBlocking {
        val logger = LlmLogger(maxLogs = 2)
        val emitted = mutableListOf<String>()
        val job = launch {
            logger.logFlow.take(3).toList(emitted)
        }
        yield()

        logger.log("req1", "res1")
        logger.log("req2", "res2")
        logger.log("req3", "res3")
        job.join()

        val expectedEntry1 = "REQUEST: req1\nRESPONSE: res1"
        val expectedEntry2 = "REQUEST: req2\nRESPONSE: res2"
        val expectedEntry3 = "REQUEST: req3\nRESPONSE: res3"

        assertEquals(listOf(expectedEntry2, expectedEntry3), logger.entries())
        assertEquals(listOf(expectedEntry1, expectedEntry2, expectedEntry3), emitted)
    }
}
