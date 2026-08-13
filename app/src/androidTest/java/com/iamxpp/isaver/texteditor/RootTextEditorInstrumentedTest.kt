package com.iamxpp.isaver.texteditor

import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTextEditorInstrumentedTest {
    @Test
    fun atomicSavePreservesModeAndRejectsStaleVersion() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertTrue(app.rootSession.check() is RootStatus.Available)
        root(app, "rm -rf -- ${quote(TARGET)}; mkdir -p -- ${quote(TARGET)}")
        root(app, "printf 'first\\n' > ${quote(FILE)}; chmod 0640 -- ${quote(FILE)}")
        try {
            val before = root(app, "stat -c '%s:%Y' -- ${quote(FILE)}").split(':')
            val entry = DirectoryEntry(path(FILE), "note.txt", EntryType.FILE, before[0].toLong(), before[1].toLong(), true, true, false)
            val loaded = app.textEditorRepository.load(entry, path(TARGET)) as OperationResult.Success
            val saved = app.textEditorRepository.save(
                loaded.value,
                loaded.value.document.copy(text = "第一行\nsecond\n", lineEnding = LineEnding.CRLF),
            )
            assertTrue("Atomic text save failed: $saved", saved is OperationResult.Success)
            assertEquals(
                "e7acace4b880e8a18c0d0a7365636f6e640d0a",
                root(app, "od -An -tx1 -v -- ${quote(FILE)} | tr -d ' \\n'"),
            )
            assertEquals("640", root(app, "stat -c %a -- ${quote(FILE)}"))
            assertTrue(root(app, "find ${quote(TARGET)} -maxdepth 1 -name '.isaver-edit-*' -print").isBlank())

            root(app, "printf external > ${quote(FILE)}")
            val stale = app.textEditorRepository.save(loaded.value, loaded.value.document.copy(text = "stale"))
            assertEquals(ErrorCode.SOURCE_UNREADABLE, (stale as OperationResult.Failure).code)
            assertEquals("external", root(app, "cat -- ${quote(FILE)}"))
            assertTrue(root(app, "find ${quote(TARGET)} -maxdepth 1 -name '.isaver-edit-*' -print").isBlank())
        } finally {
            root(app, "rm -rf -- ${quote(TARGET)}")
        }
    }

    @Test
    fun sharedStorageSaveUsesBoundedEmulatedFallback() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertTrue(app.rootSession.check() is RootStatus.Available)
        root(app, "rm -rf -- ${quote(SHARED_TARGET)}; mkdir -p -- ${quote(SHARED_TARGET)}")
        root(app, "printf 'shared\\n' > ${quote(SHARED_FILE)}")
        try {
            val stat = root(app, "stat -c '%s:%Y' -- ${quote(SHARED_FILE)}").split(':')
            val entry = DirectoryEntry(path(SHARED_FILE), "note.txt", EntryType.FILE, stat[0].toLong(), stat[1].toLong(), true, true, false)
            val loaded = app.textEditorRepository.load(entry, path(SHARED_TARGET)) as OperationResult.Success
            val saved = app.textEditorRepository.save(loaded.value, loaded.value.document.copy(text = "shared saved\n"))

            val observedContent = root(app, "cat -- ${quote(SHARED_FILE)}")
            val observedStat = root(app, "stat -c '%d:%i:%s:%a:%u:%g' -- ${quote(SHARED_FILE)}")
            val stages = root(app, "find ${quote(SHARED_TARGET)} -maxdepth 1 -name '.isaver-edit-*' -print | wc -l")
            assertTrue(
                "Shared text save failed: $saved; contentMatches=${observedContent == "shared saved"}; stat=$observedStat; stages=$stages",
                saved is OperationResult.Success,
            )
            assertEquals("shared saved", observedContent)
            assertTrue(root(app, "find ${quote(SHARED_TARGET)} -maxdepth 1 -name '.isaver-edit-*' -print").isBlank())
        } finally {
            root(app, "rm -rf -- ${quote(SHARED_TARGET)}")
        }
    }

    private suspend fun root(app: ISaverApplication, command: String): String {
        val result = (app.rootSession as LibsuRootSession).shellCoordinator.execute(command)
        assertEquals("Root command failed: ${result.stderr}", 0, result.exitCode)
        return result.stdout.joinToString("\n")
    }
    private fun quote(value: String) = RootCommandCodec.quote(value)
    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private companion object {
        const val TARGET = "/data/local/tmp/isaver-text-editor-test"
        const val FILE = "$TARGET/note.txt"
        const val SHARED_TARGET = "/storage/emulated/0/Download/.isaver-text-editor-test"
        const val SHARED_FILE = "$SHARED_TARGET/note.txt"
    }
}
