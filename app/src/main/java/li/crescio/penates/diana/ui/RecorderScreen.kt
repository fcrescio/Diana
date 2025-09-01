package li.crescio.penates.diana.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun RecorderScreen(onFinish: () -> Unit) {
    Button(onClick = onFinish) { Text("Finish Recording") }
}
