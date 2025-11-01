package li.crescio.penates.diana.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import li.crescio.penates.diana.R
import li.crescio.penates.diana.notes.Memo
import li.crescio.penates.diana.persistence.MemoRepository
import li.crescio.penates.diana.player.Player
import java.text.DateFormat
import java.util.Date

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
    var memoPendingMenu by remember { mutableStateOf<Memo?>(null) }
    var memoForTextDialog by remember { mutableStateOf<Memo?>(null) }
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
            val sortedMemos = remember(memos) { memos.sortedByDescending { it.createdAt } }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .weight(1f)
            ) {
                items(sortedMemos, key = { "${it.createdAt}_${it.text}_${it.audioPath}" }) { memo ->
                    val formattedDate = remember(memo.createdAt) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(memo.createdAt))
                    }

                    ListItem(
                        headlineContent = { Text(formattedDate) },
                        supportingContent = {
                            if (memo.text.isNotBlank()) {
                                Text(
                                    memo.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { memoPendingMenu = memo }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.more_actions)
                                    )
                                }
                                DropdownMenu(
                                    expanded = memoPendingMenu == memo,
                                    onDismissRequest = { memoPendingMenu = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.read_text)) },
                                        onClick = {
                                            memoForTextDialog = memo
                                            memoPendingMenu = null
                                        }
                                    )
                                    if (memo.audioPath != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.play)) },
                                            onClick = {
                                                player.play(memo.audioPath)
                                                memoPendingMenu = null
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.reprocess)) },
                                        onClick = {
                                            onReprocess(memo)
                                            memoPendingMenu = null
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { memoPendingMenu = memo },
                                onLongClick = { memoPendingDeletion = memo }
                            )
                    )
                }
            }
        }
    }

    val memoToShow = memoForTextDialog
    if (memoToShow != null) {
        AlertDialog(
            onDismissRequest = { memoForTextDialog = null },
            title = { Text(stringResource(R.string.read_text)) },
            text = { Text(memoToShow.text) },
            confirmButton = {
                TextButton(onClick = { memoForTextDialog = null }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
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

