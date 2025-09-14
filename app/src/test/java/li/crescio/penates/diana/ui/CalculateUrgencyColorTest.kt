package li.crescio.penates.diana.ui

import androidx.compose.ui.graphics.Color
import org.junit.Test
import java.time.LocalDate
import org.junit.Assert.assertEquals

class CalculateUrgencyColorTest {
    private val defaultColor = Color.Gray

    @Test
    fun returnsDefaultColorForBlankDate() {
        val result = calculateUrgencyColor("", defaultColor)
        assertEquals(defaultColor, result)
    }

    @Test
    fun returnsDefaultColorForInvalidDate() {
        val result = calculateUrgencyColor("not-a-date", defaultColor)
        assertEquals(defaultColor, result)
    }

    @Test
    fun returnsRedForOverdueDate() {
        val pastDate = LocalDate.now().minusDays(1).toString()
        val result = calculateUrgencyColor(pastDate, defaultColor)
        assertEquals(Color.Red, result)
    }

    @Test
    fun returnsOrangeForDueSoonDate() {
        val soonDate = LocalDate.now().plusDays(2).toString()
        val result = calculateUrgencyColor(soonDate, defaultColor)
        assertEquals(Color(0xFFFFA500), result)
    }

    @Test
    fun returnsDefaultColorForLaterDate() {
        val laterDate = LocalDate.now().plusDays(10).toString()
        val result = calculateUrgencyColor(laterDate, defaultColor)
        assertEquals(defaultColor, result)
    }
}
