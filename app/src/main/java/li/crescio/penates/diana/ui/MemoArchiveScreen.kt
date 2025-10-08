package li.crescio.penates.diana.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import li.crescio.penates.diana.R
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.persistence.MemoRepository
import li.crescio.penates.diana.player.Player

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MemoArchiveScreen(
    memoRepository: MemoRepository,
    player: Player,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onReprocess: (Memo) -> Unit
) {
    var memos by remember(memoRepository) { mutableStateOf<List<Memo>>(emptyList()) }
    var memoPendingDeletion by remember { mutableStateOf<Memo?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(memoRepository) {
        memos = memoRepository.loadMemos()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.memo_archive)) },
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
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .weight(1f)
            ) {
                items(memos) { memo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { memoPendingDeletion = memo }
                            )
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

    val memoToDelete = memoPendingDeletion
    if (memoToDelete != null) {
        AlertDialog(
            onDismissRequest = { memoPendingDeletion = null },
            title = { Text(stringResource(R.string.delete_memo_title)) },
            text = { Text(stringResource(R.string.delete_memo_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            memoRepository.deleteMemo(memoToDelete)
                            memos = memoRepository.loadMemos()
                            memoPendingDeletion = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoPendingDeletion = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

