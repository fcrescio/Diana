package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import li.crescio.penates.diana.R

@Composable
fun SettingsScreen(
    onClearTodos: () -> Unit,
    onClearAppointments: () -> Unit,
    onClearThoughts: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = onBack) { Text(stringResource(R.string.back)) }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Button(
                onClick = onClearTodos,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.clear_todo)) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClearAppointments,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.clear_appointments)) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClearThoughts,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.clear_thoughts)) }
        }
    }
}
