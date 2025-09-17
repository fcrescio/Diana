package li.crescio.penates.diana.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import li.crescio.penates.diana.R
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.persistence.MemoRepository
import li.crescio.penates.diana.player.Player

@Composable
fun MemoArchiveScreen(
    memoRepository: MemoRepository,
    player: Player,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onReprocess: (Memo) -> Unit
) {
    var memos by remember(memoRepository) { mutableStateOf<List<Memo>>(emptyList()) }

    LaunchedEffect(memoRepository) {
        memos = memoRepository.loadMemos()
    }

    Column(modifier = modifier.fillMaxSize()) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    if (memo.audioPath != null) {
                        Button(onClick = { player.play(memo.audioPath) }) {
                            Text(stringResource(R.string.play))
                        }
                    }
                    Text(
                        memo.text,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp)
                    )
                    Button(onClick = { onReprocess(memo) }) {
                        Text(stringResource(R.string.reprocess))
                    }
                }
            }
        }
    }
}

