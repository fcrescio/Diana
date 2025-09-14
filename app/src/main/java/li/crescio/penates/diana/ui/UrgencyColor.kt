package li.crescio.penates.diana.ui

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

private const val DUE_SOON_THRESHOLD_DAYS = 3L

fun calculateUrgencyColor(dueDate: String?, defaultColor: Color): Color {
    if (dueDate.isNullOrBlank()) return defaultColor
    val date = runCatching { OffsetDateTime.parse(dueDate).toLocalDate() }
        .recoverCatching { LocalDate.parse(dueDate) }
        .getOrNull() ?: return defaultColor
    val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), date)
    return when {
        daysUntil < 0 -> Color.Red
        daysUntil <= DUE_SOON_THRESHOLD_DAYS -> Color(0xFFFFA500)
        else -> defaultColor
    }
}
