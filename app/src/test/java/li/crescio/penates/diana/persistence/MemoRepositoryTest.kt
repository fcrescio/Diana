package li.crescio.penates.diana.persistence

import kotlinx.coroutines.runBlocking
import li.crescio.penates.diana.notes.Memo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class MemoRepositoryTest {
    private lateinit var file: File
    private lateinit var repository: MemoRepository

    @Before
    fun setup() {
        file = File.createTempFile("memos", ".txt")
        repository = MemoRepository(file)
    }

    @After
    fun teardown() {
        file.delete()
    }

    @Test
    fun addAndLoadMemos() = runBlocking {
        repository.addMemo(Memo("one"))
        repository.addMemo(Memo("two", "audio"))
        val memos = repository.loadMemos()
        assertEquals(listOf("one", "two"), memos.map { it.text })
        assertEquals(listOf(null, "audio"), memos.map { it.audioPath })
    }

    @Test
    fun addMemoCreatesMissingParentDirectories() = runBlocking {
        val root = createTempDirectory(prefix = "memo_repo").toFile()
        try {
            val nestedFile = File(root, "nested/memos.txt")
            val nestedRepository = MemoRepository(nestedFile)

            nestedRepository.addMemo(Memo("nested memo"))

            val memos = nestedRepository.loadMemos()
            assertEquals(listOf("nested memo"), memos.map { it.text })
        } finally {
            root.deleteRecursively()
        }
    }
}

