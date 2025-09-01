package li.crescio.penates.diana.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ProcessingScreen(status: String, onDone: () -> Unit) {
    Text(status)
}
