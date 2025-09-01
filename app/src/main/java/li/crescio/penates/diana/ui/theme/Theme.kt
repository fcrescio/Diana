package li.crescio.penates.diana.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable

@Composable
fun DianaTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface { content() }
    }
}
