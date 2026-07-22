package com.iamxpp.isaver.transfer

import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootStreamTransferInstrumentedTest {
    @Test
    fun privateCachePublishesThroughProviderWithoutRootPathAccess() = runBlocking {
        val app = app()
        assertDeviceHasRoot(app)
        resetTarget(app)
        val payload = "%PDF-1.4\niSaver stream\n%%EOF".toByteArray()
        val cached = fixture(app, payload)
        try {
            val source = app.incomingStreamRegistry.issue(cached).getOrThrow()
            val result = try {
                app.rootFileSystem.transferFromStream(
                    source = source,
                    targetDirectory = path(TARGET),
                    finalName = EntryName.parse("测试 报告.pdf").getOrThrow(),
                )
            } finally {
                app.incomingStreamRegistry.revoke(source)
            }

            assertTrue("Root stream publish failed: $result", result is OperationResult.Success)
            result as OperationResult.Success
            assertEquals(cached.sizeBytes, result.value.sizeBytes)
            assertEquals(
                payload.decodeToString(),
                root(app, "cat -- ${quote("$TARGET/测试 报告.pdf")}"),
            )
            assertNoStages(app)
        } finally {
            cached.file.delete()
            removeTarget(app)
        }
    }

    @Test
    fun collisionKeepsOriginalAndPublishesFreshCapabilityToNumberedName() = runBlocking {
        val app = app()
        assertDeviceHasRoot(app)
        resetTarget(app)
        root(app, "printf %s original > ${quote("$TARGET/测试 报告.pdf")}")
        val payload = "replacement".toByteArray()
        val cached = fixture(app, payload)
        try {
            val terminal = app.transferRepository.transfer(
                cached = cached,
                outputName = OutputNameDraft("测试 报告", "pdf"),
                targetDirectory = path(TARGET),
            ).last()

            assertEquals("测试 报告 (1).pdf", (terminal as TransferState.Success).name.value)
            assertEquals(
                "original",
                root(app, "cat -- ${quote("$TARGET/测试 报告.pdf")}"),
            )
            assertEquals(
                "replacement",
                root(app, "cat -- ${quote("$TARGET/测试 报告 (1).pdf")}"),
            )
            assertNoStages(app)
        } finally {
            cached.file.delete()
            removeTarget(app)
        }
    }

    @Test
    fun stdinLengthMismatchIsDefiniteAndLeavesNoStage() = runBlocking {
        val app = app()
        assertDeviceHasRoot(app)
        resetTarget(app)
        val cached = fixture(app, "four".toByteArray())
        try {
            listOf(3L, 5L).forEachIndexed { index, declaredSize ->
                val issued = app.incomingStreamRegistry.issue(cached).getOrThrow()
                val result = try {
                    app.rootFileSystem.transferFromStream(
                        source = issued.copy(expectedSizeBytes = declaredSize),
                        targetDirectory = path(TARGET),
                        finalName = EntryName.parse("mismatch-$index.bin").getOrThrow(),
                    )
                } finally {
                    app.incomingStreamRegistry.revoke(issued)
                }

                assertEquals(
                    ErrorCode.SOURCE_UNREADABLE,
                    (result as OperationResult.Failure).code,
                )
            }
            assertTrue(
                root(
                    app,
                    "find ${quote(TARGET)} -mindepth 1 -maxdepth 1 -print",
                ).isBlank(),
            )
        } finally {
            cached.file.delete()
            removeTarget(app)
        }
    }

    @Test
    fun emptyStreamPublishesAnEmptyRegularFile() = runBlocking {
        val app = app()
        assertDeviceHasRoot(app)
        resetTarget(app)
        val cached = fixture(app, byteArrayOf())
        try {
            val source = app.incomingStreamRegistry.issue(cached).getOrThrow()
            val result = try {
                app.rootFileSystem.transferFromStream(
                    source = source,
                    targetDirectory = path(TARGET),
                    finalName = EntryName.parse("empty.bin").getOrThrow(),
                )
            } finally {
                app.incomingStreamRegistry.revoke(source)
            }

            assertTrue(result is OperationResult.Success)
            assertEquals(
                "0",
                root(app, "stat -c %s -- ${quote("$TARGET/empty.bin")}"),
            )
            assertNoStages(app)
        } finally {
            cached.file.delete()
            removeTarget(app)
        }
    }

    @Test
    fun sharedStoragePublishAcceptsEmulatedPermissionsWithoutLeavingStage() = runBlocking {
        val app = app()
        assertDeviceHasRoot(app)
        root(app, "rm -rf -- ${quote(SHARED_TARGET)}; mkdir -p -- ${quote(SHARED_TARGET)}")
        val cached = fixture(app, "shared-storage".toByteArray())
        try {
            val source = app.incomingStreamRegistry.issue(cached).getOrThrow()
            val result = try {
                app.rootFileSystem.transferFromStream(
                    source = source,
                    targetDirectory = path(SHARED_TARGET),
                    finalName = EntryName.parse("shared.txt").getOrThrow(),
                )
            } finally {
                app.incomingStreamRegistry.revoke(source)
            }

            assertTrue("Shared storage publish failed: $result", result is OperationResult.Success)
            assertEquals("shared-storage", root(app, "cat -- ${quote("$SHARED_TARGET/shared.txt")}"))
            assertTrue(
                root(app, "find ${quote(SHARED_TARGET)} -mindepth 1 -maxdepth 1 -name '.isaver-*' -print").isBlank(),
            )
        } finally {
            cached.file.delete()
            root(app, "rm -rf -- ${quote(SHARED_TARGET)}")
        }
    }

    @Test
    fun sharedStorageRootPublishesLongChineseDocumentName() = runBlocking {
        val app = app()
        assertDeviceHasRoot(app)
        val finalPath = "$SHARED_ROOT_TARGET/$LONG_CHINESE_DOCUMENT"
        root(app, "rm -f -- ${quote(finalPath)}")
        cleanupStages(app, SHARED_ROOT_TARGET)
        val cached = fixture(app, "xiaomi17-root-regression".toByteArray())
        try {
            val source = app.incomingStreamRegistry.issue(cached).getOrThrow()
            val result = try {
                app.rootFileSystem.transferFromStream(
                    source = source,
                    targetDirectory = path(SHARED_ROOT_TARGET),
                    finalName = EntryName.parse(LONG_CHINESE_DOCUMENT).getOrThrow(),
                )
            } finally {
                app.incomingStreamRegistry.revoke(source)
            }

            assertTrue("Shared root publish failed: $result", result is OperationResult.Success)
            assertEquals("xiaomi17-root-regression", root(app, "cat -- ${quote(finalPath)}"))
            assertNoStages(app, SHARED_ROOT_TARGET)
        } finally {
            cached.file.delete()
            runCatching { root(app, "rm -f -- ${quote(finalPath)}") }
            runCatching { cleanupStages(app, SHARED_ROOT_TARGET) }
        }
    }

    @Test
    fun sharedStorageCollisionKeepsOriginalAndPublishesNumberedCopy() = runBlocking {
        val app = app()
        assertDeviceHasRoot(app)
        root(app, "rm -rf -- ${quote(SHARED_TARGET)}; mkdir -p -- ${quote(SHARED_TARGET)}")
        root(app, "printf %s original > ${quote("$SHARED_TARGET/shared.txt")}")
        val cached = fixture(app, "replacement".toByteArray())
        try {
            val terminal = app.transferRepository.transfer(
                cached = cached,
                outputName = OutputNameDraft("shared", "txt"),
                targetDirectory = path(SHARED_TARGET),
            ).last()

            assertEquals("shared (1).txt", (terminal as TransferState.Success).name.value)
            assertEquals("original", root(app, "cat -- ${quote("$SHARED_TARGET/shared.txt")}"))
            assertEquals("replacement", root(app, "cat -- ${quote("$SHARED_TARGET/shared (1).txt")}"))
        } finally {
            cached.file.delete()
            root(app, "rm -rf -- ${quote(SHARED_TARGET)}")
        }
    }

    private fun app() = ApplicationProvider.getApplicationContext<ISaverApplication>()

    private suspend fun assertDeviceHasRoot(app: ISaverApplication) {
        val sessionStatus = app.rootSession.check()
        val shellUid = root(app, "id -u")
        assertTrue(
            "Root unavailable in instrumentation: session=$sessionStatus shellUid=$shellUid",
            sessionStatus is RootStatus.Available && shellUid == "0",
        )
    }

    private suspend fun resetTarget(app: ISaverApplication) {
        removeTarget(app)
        root(app, "mkdir -p -- ${quote(TARGET)}")
    }

    private suspend fun removeTarget(app: ISaverApplication) {
        root(app, "rm -rf -- ${quote(TARGET)}")
    }

    private suspend fun assertNoStages(app: ISaverApplication) {
        assertNoStages(app, TARGET)
    }

    private suspend fun assertNoStages(app: ISaverApplication, target: String) {
        assertTrue(
            root(
                app,
                "find ${quote(target)} -maxdepth 1 -name '.isaver-*' -print",
            ).isBlank(),
        )
    }

    private suspend fun cleanupStages(app: ISaverApplication, target: String) {
        root(app, "for p in ${quote(target)}/.isaver-stage-*; do [ -e \"\$p\" ] && rm -rf -- \"\$p\"; done; true")
    }

    private suspend fun root(app: ISaverApplication, command: String): String {
        val session = app.rootSession as LibsuRootSession
        val result = session.shellCoordinator.execute(command)
        assertEquals(
            "Root command failed: exit=${result.exitCode}",
            0,
            result.exitCode,
        )
        return result.stdout.joinToString("\n")
    }

    private fun quote(value: String) = RootCommandCodec.quote(value)

    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private fun fixture(
        app: ISaverApplication,
        bytes: ByteArray,
    ): CachedIncomingFile {
        val file = File(app.cacheDir, "incoming/${UUID.randomUUID()}.tmp")
        check(file.parentFile!!.exists() || file.parentFile!!.mkdirs())
        file.writeBytes(bytes)
        return CachedIncomingFile(
            file = file,
            sizeBytes = bytes.size.toLong(),
            appCachePath = AppCachePath.fromIncomingCacheFile(app.cacheDir, file).getOrThrow(),
        )
    }

    private companion object {
        const val TARGET = "/data/local/tmp/isaver-stream-test"
        const val SHARED_ROOT_TARGET = "/storage/emulated/0"
        const val SHARED_TARGET = "/storage/emulated/0/Download/.isaver-stream-test"
        const val LONG_CHINESE_DOCUMENT = "附件2：作品报告_虚拟货币挖矿流量智能识别.docx"
    }
}
