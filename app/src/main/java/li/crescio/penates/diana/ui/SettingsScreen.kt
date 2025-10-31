package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import li.crescio.penates.diana.R
import li.crescio.penates.diana.session.SessionSettings

data class LlmModelOption(val id: String, @StringRes val labelResId: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sessionName: String,
    settings: SessionSettings,
    llmModels: List<LlmModelOption>,
    onProcessTodosChange: (Boolean) -> Unit,
    onProcessAppointmentsChange: (Boolean) -> Unit,
    onProcessThoughtsChange: (Boolean) -> Unit,
    onShowTagsChange: (Boolean) -> Unit,
    onModelChange: (String) -> Unit,
    onManageTags: () -> Unit,
    onClearTodos: () -> Unit,
    onClearAppointments: () -> Unit,
    onClearThoughts: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.editing_session, sessionName),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.process_todo))
                Spacer(modifier = Modifier.weight(1f))
                Switch(checked = settings.processTodos, onCheckedChange = onProcessTodosChange)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.process_appointments))
                Spacer(modifier = Modifier.weight(1f))
                Switch(checked = settings.processAppointments, onCheckedChange = onProcessAppointmentsChange)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.process_thoughts))
                Spacer(modifier = Modifier.weight(1f))
                Switch(checked = settings.processThoughts, onCheckedChange = onProcessThoughtsChange)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.show_tags))
                Spacer(modifier = Modifier.weight(1f))
                Switch(checked = settings.showTags, onCheckedChange = onShowTagsChange)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.llm_model))
            Spacer(modifier = Modifier.height(8.dp))
            llmModels.forEach { option ->
                val label = stringResource(option.labelResId)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option.id == settings.model,
                            onClick = { onModelChange(option.id) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = option.id == settings.model,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onManageTags,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.manage_tags)) }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onClearTodos,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.clear_todo)) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClearAppointments,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.clear_appointments)) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClearThoughts,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.clear_thoughts)) }
        }
    }
}

