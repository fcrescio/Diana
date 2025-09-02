package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.player.Player
import androidx.compose.ui.res.stringResource
import li.crescio.penates.diana.R

@Composable
fun RecordedMemosScreen(
    memos: List<Memo>,
    player: Player,
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

        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(memos) { memo ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Button(onClick = { memo.audioPath?.let { player.play(it) } }) { Text(stringResource(R.string.play)) }
                    Text(
                        memo.text,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }
}
