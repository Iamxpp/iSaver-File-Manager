# MIUI SELinux Cache Stream Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish incoming files from iSaver's internal cache on MIUI without granting Root direct path access to app-private data, while preserving the existing atomic no-overwrite transfer protocol.

**Architecture:** A process-local `IncomingStreamRegistry` issues a 60-second, one-shot 256-bit capability for one validated `CachedIncomingFile`. An exported provider accepts only Root/Shell and returns a read-only descriptor for that capability; the Root data layer pipes Android `content read` into a fixed native `copy-publish-stdin` subcommand, which retains stage identity, exact-size, fsync, and `RENAME_NOREPLACE` guarantees.

**Tech Stack:** Kotlin, Android ContentProvider/ParcelFileDescriptor, coroutines/Flow, libsu, C/NDK, JUnit/Robolectric, Android instrumentation, ADB/SU on Xiaomi 9 API 30.

---

## File Structure

- Create `app/src/main/java/com/iamxpp/isaver/data/root/RootTransferSource.kt`: typed one-shot content stream capability used only at the Root publish boundary.
- Create `app/src/main/java/com/iamxpp/isaver/transfer/IncomingStreamRegistry.kt`: token issue, expiry, atomic consume, validation, and revocation.
- Create `app/src/main/java/com/iamxpp/isaver/transfer/IncomingStreamProvider.kt`: Binder/ContentProvider adapter with Root/Shell-only reads.
- Create `app/src/test/java/com/iamxpp/isaver/transfer/IncomingStreamRegistryTest.kt`: deterministic registry security and concurrency tests.
- Create `app/src/androidTest/java/com/iamxpp/isaver/transfer/IncomingStreamProviderInstrumentedTest.kt`: real descriptor bridge and replay tests.
- Create `app/src/androidTest/java/com/iamxpp/isaver/transfer/RootStreamTransferInstrumentedTest.kt`: actual libsu/native publish test on the Root device.
- Modify `app/src/main/AndroidManifest.xml`: register the narrow stream provider.
- Modify `app/src/main/java/com/iamxpp/isaver/transfer/IncomingFileCache.kt`: expose one synchronous identity validator shared by the provider and suspend API.
- Modify `app/src/main/java/com/iamxpp/isaver/data/root/RootFileSystem.kt`: replace path-based cache publication with the typed stream source.
- Modify `app/src/main/java/com/iamxpp/isaver/data/root/RootTransferHelper.kt`: construct the fixed `content read | copy-publish-stdin` pipeline.
- Modify `app/src/main/java/com/iamxpp/isaver/data/root/LibsuRootFileSystem.kt`: dispatch the stream command while retaining stage/reconciliation logic.
- Modify `app/src/main/cpp/isaver_fs_helper.c`: replace the private-path source open with exact-length stdin ingestion.
- Modify `app/src/main/java/com/iamxpp/isaver/transfer/RootFileTransferRepository.kt`: issue/revoke one capability per candidate publish window.
- Modify `app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt`: own and connect the registry, provider, repository, and Root filesystem.
- Modify adjacent JVM tests named in each task.
- Modify PRD/SDD 3.3 and the superseded picker plan after runtime behavior is green.

### Task 1: One-Shot Stream Capability Registry

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/data/root/RootTransferSource.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/transfer/IncomingStreamRegistry.kt`
- Create: `app/src/test/java/com/iamxpp/isaver/transfer/IncomingStreamRegistryTest.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/transfer/IncomingFileCache.kt`
- Modify: `app/src/test/java/com/iamxpp/isaver/transfer/IncomingFileCacheTest.kt`

- [ ] **Step 1: Write RED registry tests**

Create deterministic tests covering token shape, exact URI, single consumption, expiry, revocation, invalid cache, and two-thread consumption:

```kotlin
@Test fun `issue creates a 60 second one shot root source`() {
    val registry = registry(now = { 1_000L }, valid = { true })
    val source = registry.issue(cached()).getOrThrow()

    assertEquals(
        "content://com.iamxpp.isaver.incoming-stream/incoming/${"ab".repeat(32)}",
        source.contentUri,
    )
    assertEquals(4L, source.expectedSizeBytes)
    assertNotNull(registry.consume(source.token, nowMillis = 60_999L))
    assertNull(registry.consume(source.token, nowMillis = 60_999L))

    val expired = registry.issue(cached()).getOrThrow()
    assertNull(registry.consume(expired.token, nowMillis = 61_000L))
}

@Test fun `expired revoked invalid and raced capabilities never reveal a file`() = runBlocking {
    val invalid = registry(valid = { false })
    assertTrue(invalid.issue(cached()).isFailure)

    val revoked = registry(valid = { true })
    val revokedSource = revoked.issue(cached()).getOrThrow()
    revoked.revoke(revokedSource)
    assertNull(revoked.consume(revokedSource.token))

    val raced = registry(valid = { true })
    val source = raced.issue(cached()).getOrThrow()
    val results = List(2) { async(Dispatchers.Default) { raced.consume(source.token) } }.awaitAll()
    assertEquals(1, results.count { it != null })
}
```

Use an injected token factory returning `ByteArray(32) { 0xab.toByte() }`, and assert construction rejects any token factory result that is not exactly 32 bytes.

- [ ] **Step 2: Run the registry test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.transfer.IncomingStreamRegistryTest"
```

Expected: compilation fails because `IncomingStreamRegistry` and `RootTransferSource` do not exist.

- [ ] **Step 3: Add the minimal typed capability and registry**

Create the Root boundary model:

```kotlin
data class RootTransferSource internal constructor(
    val contentUri: String,
    val expectedSizeBytes: Long,
    internal val token: String,
)
```

Implement the registry with synchronized map access:

```kotlin
class IncomingStreamRegistry internal constructor(
    private val authority: String,
    private val validate: (CachedIncomingFile) -> Boolean,
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val randomBytes: () -> ByteArray = {
        ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
    },
) {
    private data class Entry(val cached: CachedIncomingFile, val expiresAt: Long)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun issue(cached: CachedIncomingFile): Result<RootTransferSource> = runCatching {
        require(validate(cached))
        val bytes = randomBytes()
        require(bytes.size == TOKEN_BYTES)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expiresAt = Math.addExact(nowMillis(), TTL_MILLIS)
        synchronized(lock) { check(entries.putIfAbsent(token, Entry(cached, expiresAt)) == null) }
        RootTransferSource(
            contentUri = "content://$authority/incoming/$token",
            expectedSizeBytes = cached.sizeBytes,
            token = token,
        )
    }

    fun consume(token: String, nowMillis: Long = this.nowMillis()): CachedIncomingFile? {
        if (!TOKEN.matches(token)) return null
        val entry = synchronized(lock) { entries.remove(token) } ?: return null
        return entry.cached.takeIf { nowMillis < entry.expiresAt && validate(it) }
    }

    fun revoke(source: RootTransferSource) { synchronized(lock) { entries.remove(source.token) } }

    companion object {
        const val TTL_MILLIS = 60_000L
        private const val TOKEN_BYTES = 32
        private val TOKEN = Regex("[0-9a-f]{64}")
    }
}
```

- [ ] **Step 4: Share one synchronous cache validator**

Refactor `IncomingFileCache.validate` without changing its behavior:

```kotlin
internal fun validateNow(cached: CachedIncomingFile): Boolean {
    val incoming = runCatching { incomingDir.canonicalFile }.getOrNull() ?: return false
    val canonical = runCatching { cached.file.canonicalFile }.getOrNull() ?: return false
    if (canonical.parentFile != incoming || !cached.file.exists()) return false
    val identity = runCatching { Os.lstat(cached.file.path) }.getOrNull() ?: return false
    return OsConstants.S_ISREG(identity.st_mode) &&
        identity.st_dev == cached.appCachePath.device &&
        identity.st_ino == cached.appCachePath.inode &&
        identity.st_size == cached.sizeBytes
}

suspend fun validate(cached: CachedIncomingFile): Boolean =
    withContext(ioDispatcher) { validateNow(cached) }
```

Extend `IncomingFileCacheTest` to prove replacement with a same-sized different inode is rejected by both validation entry points.

- [ ] **Step 5: Run focused tests and verify GREEN**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.transfer.IncomingStreamRegistryTest" --tests "com.iamxpp.isaver.transfer.IncomingFileCacheTest"
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit the registry slice**

```powershell
git add app/src/main/java/com/iamxpp/isaver/data/root/RootTransferSource.kt app/src/main/java/com/iamxpp/isaver/transfer/IncomingStreamRegistry.kt app/src/main/java/com/iamxpp/isaver/transfer/IncomingFileCache.kt app/src/test/java/com/iamxpp/isaver/transfer/IncomingStreamRegistryTest.kt app/src/test/java/com/iamxpp/isaver/transfer/IncomingFileCacheTest.kt
git commit -m "feat: add one-shot incoming stream capabilities"
```

### Task 2: Root/Shell-Only ContentProvider

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/transfer/IncomingStreamProvider.kt`
- Create: `app/src/androidTest/java/com/iamxpp/isaver/transfer/IncomingStreamProviderInstrumentedTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt`
- Modify: `app/src/test/java/com/iamxpp/isaver/ISaverApplicationTest.kt`

- [ ] **Step 1: Write RED application ownership and manifest tests**

Add to `ISaverApplicationTest`:

```kotlin
@Test fun applicationOwnsSingleIncomingStreamRegistry() {
    assertSame(application.incomingStreamRegistry, application.incomingStreamRegistry)
}
```

Create an instrumentation test that issues a real capability, reads it via shell, proves replay is empty/fails, and proves the app UID is rejected:

```kotlin
@Test fun shellReadsExactlyOnceWhileAppUidIsRejected() {
    val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
    val cached = fixture(app.cacheDir, "private-payload".toByteArray())
    val source = app.incomingStreamRegistry.issue(cached).getOrThrow()

    val shellOutput = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("content read --uri ${source.contentUri}")
        .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    assertArrayEquals("private-payload".toByteArray(), shellOutput)

    val replay = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("content read --uri ${source.contentUri}")
        .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    assertFalse(replay.contentEquals("private-payload".toByteArray()))

    val second = app.incomingStreamRegistry.issue(cached).getOrThrow()
    assertThrows(FileNotFoundException::class.java) {
        app.contentResolver.openFileDescriptor(Uri.parse(second.contentUri), "r")
    }
    app.incomingStreamRegistry.revoke(second)
}
```

Also query `PackageManager` and assert `${context.packageName}.incoming-stream` is exported and owned by `IncomingStreamProvider`.

- [ ] **Step 2: Run tests and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.ISaverApplicationTest"
.\gradlew.bat assembleDebugAndroidTest
```

Expected: compilation fails because the application property/provider do not exist.

- [ ] **Step 3: Register the provider**

Add inside `<application>`:

```xml
<provider
    android:name=".transfer.IncomingStreamProvider"
    android:authorities="${applicationId}.incoming-stream"
    android:exported="true"
    android:grantUriPermissions="false" />
```

Do not add intent filters, path grants, permissions, or metadata.

- [ ] **Step 4: Implement the narrow provider adapter**

Implement only the read path:

```kotlin
class IncomingStreamProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val caller = Binder.getCallingUid()
        if (caller != ROOT_UID && caller != SHELL_UID) deny()
        val app = context?.applicationContext as? ISaverApplication ?: deny()
        if (mode != "r" || uri.authority != "${app.packageName}.incoming-stream") deny()
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "incoming") deny()
        val cached = app.incomingStreamRegistry.consume(segments[1]) ?: deny()
        return try {
            ParcelFileDescriptor.open(cached.file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            deny()
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun deny(): Nothing = throw FileNotFoundException("Stream unavailable")

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
    }
}
```

The message is deliberately identical for caller, URI, token, expiry, replay, and file-validation failures.

- [ ] **Step 5: Wire the singleton registry**

Add to `ISaverApplication`:

```kotlin
internal val incomingStreamRegistry: IncomingStreamRegistry by lazy {
    IncomingStreamRegistry(
        authority = "$packageName.incoming-stream",
        validate = incomingFileCache::validateNow,
    )
}
```

- [ ] **Step 6: Run JVM and device provider tests**

Build, install through the known MIUI Root path, then run only the non-Activity class:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.ISaverApplicationTest"
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb -s d51f42ac push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/isaver-debug.apk
adb -s d51f42ac shell su -c "pm install -r -t /data/local/tmp/isaver-debug.apk"
adb -s d51f42ac push app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk /data/local/tmp/isaver-debug-androidTest.apk
adb -s d51f42ac shell su -c "pm install -r -t /data/local/tmp/isaver-debug-androidTest.apk"
adb -s d51f42ac shell am instrument -w -r -e class com.iamxpp.isaver.transfer.IncomingStreamProviderInstrumentedTest com.iamxpp.isaver.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: JVM and provider tests pass; first shell read equals fixture bytes, replay and app-UID access do not.

- [ ] **Step 7: Commit the provider slice**

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt app/src/main/java/com/iamxpp/isaver/transfer/IncomingStreamProvider.kt app/src/test/java/com/iamxpp/isaver/ISaverApplicationTest.kt app/src/androidTest/java/com/iamxpp/isaver/transfer/IncomingStreamProviderInstrumentedTest.kt
git commit -m "feat: expose one-shot root stream provider"
```

### Task 3: Typed Root Stream Command

**Files:**
- Modify: `app/src/main/java/com/iamxpp/isaver/data/root/RootFileSystem.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/data/root/RootTransferHelper.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/data/root/LibsuRootFileSystem.kt`
- Modify: `app/src/test/java/com/iamxpp/isaver/data/root/RootTransferHelperTest.kt`
- Modify: `app/src/test/java/com/iamxpp/isaver/data/root/RootTransferStagingTest.kt`

- [ ] **Step 1: Write RED command and staging tests**

Replace path-source expectations with the desired fixed pipeline:

```kotlin
@Test fun `copy command pipes one safely quoted capability into fixed stdin helper`() {
    val command = helper.copyPublish(
        original = "/original",
        canonical = "/canonical",
        stage = stage,
        final = "报告 'final'.pdf",
        parentId = RootFileIdentity(1, 2),
        source = RootTransferSource(
            contentUri = "content://com.iamxpp.isaver.incoming-stream/incoming/${"ab".repeat(32)}",
            expectedSizeBytes = 37,
            token = "ab".repeat(32),
        ),
        timeoutMillis = 1_250,
    )
    assertTrue(command.startsWith("set -o pipefail\n'/system/bin/content' 'read' '--uri'"))
    assertTrue(command.contains("| '/system/bin/timeout' '-s' 'KILL' '1.250'"))
    assertTrue(command.contains("'copy-publish-stdin'"))
    assertFalse(command.contains("/data/user/0"))
    assertFalse(command.contains("'copy-publish' "))
}
```

Update `RootTransferStagingTest` to call `transferFromStream(source(), target, name)` and expect `prepare-stage`, `copy-publish-stdin`, and the same reconciliation/cleanup counts as before.

- [ ] **Step 2: Run tests and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.data.root.RootTransferHelperTest" --tests "com.iamxpp.isaver.data.root.RootTransferStagingTest"
```

Expected: compilation fails because the stream API and command do not exist.

- [ ] **Step 3: Replace the Root filesystem boundary**

Change the interface to:

```kotlin
suspend fun transferFromStream(
    source: RootTransferSource,
    targetDirectory: RootPath,
    finalName: EntryName,
): OperationResult<DirectoryEntry> = unsupportedTransfer()
```

Remove `AppCachePath` and the separate size parameter from this boundary. Keep `AppCachePath` inside `CachedIncomingFile` for app-side identity validation and orphan ownership.

- [ ] **Step 4: Construct the fixed pipe command**

Change `RootTransferHelper.copyPublish` to build the left and right fixed commands independently and emit:

```kotlin
fun copyPublish(
    original: String,
    canonical: String,
    stage: TransferStage,
    final: String,
    parentId: RootFileIdentity,
    source: RootTransferSource,
    timeoutMillis: Long,
): String {
    val contentCommand = listOf(
        "/system/bin/content", "read", "--uri", source.contentUri,
    ).joinToString(" ") { RootCommandCodec.quote(it) }
    val publishCommand = timeoutCommand(
        timeoutDuration(timeoutMillis),
        "copy-publish-stdin",
        original,
        canonical,
        stage.name,
        final,
        parentId.device,
        parentId.inode,
        stage.identity.device,
        stage.identity.inode,
        source.expectedSizeBytes,
    )
    return "set -o pipefail\n$contentCommand | $publishCommand"
}
```

Every item continues through `RootCommandCodec.quote`; no URI/path is concatenated unquoted.

- [ ] **Step 5: Thread the stream source through Libsu**

Rename the override to `transferFromStream`, use `source.expectedSizeBytes` for post-verification, and pass `source` into the helper. Keep `prepareWritableDirectory`, Root stage creation, non-cancellable dispatch, lost-result reconciliation, final stat/identity verification, and error mapping unchanged.

- [ ] **Step 6: Run focused tests and verify GREEN**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.data.root.RootTransferHelperTest" --tests "com.iamxpp.isaver.data.root.RootTransferStagingTest"
```

Expected: all selected tests pass and command assertions contain no private cache path.

- [ ] **Step 7: Commit the Kotlin Root boundary slice**

```powershell
git add app/src/main/java/com/iamxpp/isaver/data/root/RootFileSystem.kt app/src/main/java/com/iamxpp/isaver/data/root/RootTransferHelper.kt app/src/main/java/com/iamxpp/isaver/data/root/LibsuRootFileSystem.kt app/src/test/java/com/iamxpp/isaver/data/root/RootTransferHelperTest.kt app/src/test/java/com/iamxpp/isaver/data/root/RootTransferStagingTest.kt
git commit -m "refactor: publish root files from typed streams"
```

### Task 4: Exact-Length Native stdin Publication

**Files:**
- Modify: `app/src/main/cpp/isaver_fs_helper.c`
- Modify: `app/src/test/java/com/iamxpp/isaver/data/root/RootTransferHelperTest.kt`

- [ ] **Step 1: Add the RED allowlist assertion**

Add a source-level contract test that reads the C file and proves the new allowlisted command is present while the obsolete private-path command is absent from `main`:

```kotlin
@Test fun `native helper allowlists only stdin publication`() {
    val sourceFile = listOf(
        File("app/src/main/cpp/isaver_fs_helper.c"),
        File("src/main/cpp/isaver_fs_helper.c"),
    ).first(File::isFile)
    val source = sourceFile.readText()
    val main = source.substringAfter("int main(int argc").substringBeforeLast("}")
    assertTrue(main.contains("copy-publish-stdin"))
    assertFalse(main.contains("strcmp(argv[1], \"copy-publish\")"))
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.data.root.RootTransferHelperTest.native helper allowlists only stdin publication"
```

Expected: assertion fails because `main` still allowlists `copy-publish`.

- [ ] **Step 3: Replace source-open arguments with stdin arguments**

Implement `copy_publish_stdin` with exactly eleven arguments:

```c
static int copy_publish_stdin(int argc, char **argv) {
    if (argc != 11 || !stage_name_ok(argv[4]) || !basename_ok(argv[5])) return X_USAGE;
    unsigned long long parent_device, parent_inode, stage_device, stage_inode, expected_size;
    if (!parse_identity(argv, 6, &parent_device, &parent_inode) ||
        !parse_identity(argv, 8, &stage_device, &stage_inode) ||
        !parse_u64(argv[10], &expected_size)) return X_USAGE;

    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    int stage_fd = open_stage(parent_fd, argv[4], stage_device, stage_inode);
    if (stage_fd < 0) { close(parent_fd); return -stage_fd; }
    return copy_stdin_and_publish(parent_fd, stage_fd, argv[4], argv[5], expected_size);
}
```

Refactor the existing payload creation/publish block into `copy_stdin_and_publish`. Its read loop must use `STDIN_FILENO`, never read more than the remaining declared bytes, treat early EOF as `X_SOURCE_CHANGED`, and read one final probe byte after the declared size:

```c
while (copied < expected_size) {
    size_t wanted = (size_t) ((expected_size - copied) < sizeof(buffer)
        ? (expected_size - copied) : sizeof(buffer));
    ssize_t count;
    do { count = read(STDIN_FILENO, buffer, wanted); } while (count < 0 && errno == EINTR);
    if (count <= 0) { result = X_SOURCE_CHANGED; break; }
    result = write_all(payload_fd, buffer, (size_t) count, &copied);
    if (result != 0) break;
}
if (result == 0) {
    unsigned char extra;
    ssize_t count;
    do { count = read(STDIN_FILENO, &extra, 1); } while (count < 0 && errno == EINTR);
    if (count != 0) result = count < 0 ? X_SOURCE_UNREADABLE : X_SOURCE_CHANGED;
}
```

Retain 0600 payload verification, fsync, `renameat2(RENAME_NOREPLACE)`, stage identity cleanup, parent fsync, and published `device:inode:size` output. Remove all source path/device/inode parsing and the `copy-publish` branch from `main`.

- [ ] **Step 4: Build every ABI and run the contract test**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.data.root.RootTransferHelperTest"
.\gradlew.bat assembleDebug
```

Expected: tests and NDK builds for arm64-v8a, armeabi-v7a, x86, and x86_64 pass.

- [ ] **Step 5: Commit the native stream slice**

```powershell
git add app/src/main/cpp/isaver_fs_helper.c app/src/test/java/com/iamxpp/isaver/data/root/RootTransferHelperTest.kt
git commit -m "feat: publish exact native stdin streams"
```

### Task 5: Capability Lifecycle Per Publish Attempt

**Files:**
- Modify: `app/src/main/java/com/iamxpp/isaver/transfer/RootFileTransferRepository.kt`
- Modify: `app/src/test/java/com/iamxpp/isaver/transfer/RootFileTransferRepositoryTest.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt`

- [ ] **Step 1: Write RED lifecycle tests**

Extend repository tests with issuer/revoker spies:

```kotlin
@Test fun `collision consumes and revokes one fresh capability per candidate`() = runTest {
    val issued = mutableListOf<RootTransferSource>()
    val revoked = mutableListOf<RootTransferSource>()
    val fs = FakeFs(mutableListOf(failure(ErrorCode.ALREADY_EXISTS), success("a (1).txt")))
    val states = repository(
        fs = fs,
        issue = { OperationResult.Success(source(issued.size).also(issued::add)) },
        revoke = revoked::add,
    ).transfer(fakeCached(), draft("a.txt"), path("/target")).toList()

    assertEquals(2, issued.size)
    assertEquals(issued, revoked)
    assertEquals(listOf(issued[0], issued[1]), fs.sources)
    assertTrue(states.last() is TransferState.Success)
}

@Test fun `issue failure never crosses the root boundary`() = runTest {
    val fs = FakeFs(mutableListOf())
    val terminal = repository(
        fs = fs,
        issue = { OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法读取分享文件") },
    ).transfer(fakeCached(), draft("a.txt"), path("/target")).last()
    assertEquals(ErrorCode.SOURCE_UNREADABLE, (terminal as TransferState.Failure).code)
    assertTrue(fs.sources.isEmpty())
}

private fun source(index: Int): RootTransferSource {
    val token = (index + 1).toString(16).padStart(64, '0')
    return RootTransferSource(
        contentUri = "content://com.iamxpp.isaver.incoming-stream/incoming/$token",
        expectedSizeBytes = 1L,
        token = token,
    )
}
```

Also cover exception, cancellation before Root dispatch, definite failure, and uncertain result; every issued source is revoked in `finally`, and no result automatically reuses it.

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.transfer.RootFileTransferRepositoryTest"
```

Expected: compilation fails because repository has no issue/revoke dependencies and fake filesystem still uses path sources.

- [ ] **Step 3: Issue exactly one source inside each candidate loop**

Change constructor dependencies:

```kotlin
class RootFileTransferRepository(
    private val fileSystem: RootFileSystem,
    private val nameResolver: TargetNameResolver,
    private val issueSource: (CachedIncomingFile) -> OperationResult<RootTransferSource>,
    private val revokeSource: (RootTransferSource) -> Unit,
    private val cleanupCache: suspend (CachedIncomingFile) -> Boolean,
)
```

Immediately before `Publishing`, issue and scope the capability:

```kotlin
val source = when (val issued = issueSource(cached)) {
    is OperationResult.Success -> issued.value
    is OperationResult.Failure -> {
        emit(TransferState.Failure(issued.code, safeFailureMessage(issued.code)))
        return@flow
    }
}
try {
    emit(TransferState.Publishing(candidate, attempt))
    rootWriteStarted = true
    result = withContext(NonCancellable) {
        fileSystem.transferFromStream(source, targetDirectory, candidate)
    }
} finally {
    revokeSource(source)
}
```

Keep collision policy, cancellation semantics, cache ownership, and cleanup warnings unchanged.

- [ ] **Step 4: Wire issuer/revoker in the application**

Construct the repository with:

```kotlin
issueSource = { cached ->
    incomingStreamRegistry.issue(cached).fold(
        onSuccess = { OperationResult.Success(it) },
        onFailure = {
            OperationResult.Failure(
                ErrorCode.SOURCE_UNREADABLE,
                "无法读取分享文件",
                "Incoming stream capability could not be issued",
            )
        },
    )
},
revokeSource = incomingStreamRegistry::revoke,
```

- [ ] **Step 5: Run repository and transfer-state regressions**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.transfer.RootFileTransferRepositoryTest" --tests "com.iamxpp.isaver.transfer.TransferViewModelTest"
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit the lifecycle slice**

```powershell
git add app/src/main/java/com/iamxpp/isaver/transfer/RootFileTransferRepository.kt app/src/test/java/com/iamxpp/isaver/transfer/RootFileTransferRepositoryTest.kt app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt
git commit -m "fix: stream private cache into root publishes"
```

### Task 6: Actual Root Stream Integration

**Files:**
- Create: `app/src/androidTest/java/com/iamxpp/isaver/transfer/RootStreamTransferInstrumentedTest.kt`
- Modify only owning production files if this test reveals a verified defect.

- [ ] **Step 1: Write the Root integration test**

The test must use a dedicated directory and never inspect WeChat data:

```kotlin
@Test fun privateCachePublishesThroughProviderWithoutRootPathAccess() = runBlocking {
    assumeTrue(deviceHasRoot())
    root("rm -rf $TARGET && mkdir -p $TARGET")
    try {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val cached = fixture(app.cacheDir, "%PDF-1.4\niSaver stream\n%%EOF\n".toByteArray())
        val source = app.incomingStreamRegistry.issue(cached).getOrThrow()
        val result = app.rootFileSystem.transferFromStream(
            source,
            RootPath.parse(TARGET).getOrThrow(),
            EntryName.parse("测试 报告.pdf").getOrThrow(),
        )
        app.incomingStreamRegistry.revoke(source)

        assertTrue(result is OperationResult.Success)
        assertEquals(cached.sizeBytes, (result as OperationResult.Success).value.sizeBytes)
        assertEquals(cached.sizeBytes.toString(), root("stat -c %s '$TARGET/测试 报告.pdf'").trim())
        assertTrue(root("find '$TARGET' -maxdepth 1 -name '.isaver-*' -print").isBlank())
    } finally {
        root("rm -rf $TARGET")
    }
}
```

Add a second test that precreates `测试 报告.pdf`, then uses the real repository to verify collision behavior:

```kotlin
@Test fun collisionKeepsOriginalAndPublishesFreshCapabilityToNumberedName() = runBlocking {
    assumeTrue(deviceHasRoot())
    root("rm -rf $TARGET && mkdir -p $TARGET && printf original > '$TARGET/测试 报告.pdf'")
    try {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val payload = "replacement".toByteArray()
        val cached = fixture(app.cacheDir, payload)
        val terminal = app.transferRepository.transfer(
            cached,
            OutputNameDraft("测试 报告", "pdf"),
            RootPath.parse(TARGET).getOrThrow(),
        ).last()

        assertEquals("测试 报告 (1).pdf", (terminal as TransferState.Success).name.value)
        assertEquals("original", root("cat '$TARGET/测试 报告.pdf'"))
        assertEquals(payload.size.toString(), root("stat -c %s '$TARGET/测试 报告 (1).pdf'").trim())
        assertTrue(root("find '$TARGET' -maxdepth 1 -name '.isaver-*' -print").isBlank())
    } finally {
        root("rm -rf $TARGET")
    }
}
```

Add a native stream-length matrix using fresh capabilities for each case:

```kotlin
@Test fun stdinLengthMismatchIsDefiniteAndLeavesNoStage() = runBlocking {
    assumeTrue(deviceHasRoot())
    root("rm -rf $TARGET && mkdir -p $TARGET")
    try {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val cached = fixture(app.cacheDir, "four".toByteArray())
        listOf(3L, 5L).forEachIndexed { index, declared ->
            val issued = app.incomingStreamRegistry.issue(cached).getOrThrow()
            val mismatched = issued.copy(expectedSizeBytes = declared)
            val result = app.rootFileSystem.transferFromStream(
                mismatched,
                RootPath.parse(TARGET).getOrThrow(),
                EntryName.parse("mismatch-$index.bin").getOrThrow(),
            )
            app.incomingStreamRegistry.revoke(issued)
            assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
        }
        assertTrue(root("find '$TARGET' -mindepth 1 -maxdepth 1 -print").isBlank())
    } finally {
        root("rm -rf $TARGET")
    }
}
```

Add exact zero-byte success:

```kotlin
@Test fun emptyStreamPublishesAnEmptyRegularFile() = runBlocking {
    assumeTrue(deviceHasRoot())
    root("rm -rf $TARGET && mkdir -p $TARGET")
    try {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val cached = fixture(app.cacheDir, byteArrayOf())
        val source = app.incomingStreamRegistry.issue(cached).getOrThrow()
        val result = app.rootFileSystem.transferFromStream(
            source,
            RootPath.parse(TARGET).getOrThrow(),
            EntryName.parse("empty.bin").getOrThrow(),
        )
        app.incomingStreamRegistry.revoke(source)
        assertTrue(result is OperationResult.Success)
        assertEquals("0", root("stat -c %s '$TARGET/empty.bin'").trim())
    } finally {
        root("rm -rf $TARGET")
    }
}
```

The shorter and longer declared sizes respectively exercise extra-byte and early-EOF/interrupted-stream cleanup. Existing `RootTransferStagingTest` continues to cover timeout, cancellation, parent/stage replacement, symlink rejection, and uncertain outcomes.

- [ ] **Step 2: Run and verify failure before final fixes**

Install the app/test APKs through Root and run:

```powershell
adb -s d51f42ac shell am instrument -w -r -e class com.iamxpp.isaver.transfer.RootStreamTransferInstrumentedTest com.iamxpp.isaver.test/androidx.test.runner.AndroidJUnitRunner
```

Expected before the complete bridge: test fails at the actual publish boundary; after Tasks 1–5 it must pass.

- [ ] **Step 3: Verify direct Root cache access remains denied**

Have the test or ADB record only the exit status, not the cache path contents:

```powershell
adb -s d51f42ac shell su -c "head -c 1 /data/user/0/com.iamxpp.isaver/cache/incoming/*.tmp >/dev/null 2>&1"; $LASTEXITCODE
```

Expected: non-zero, while the provider-backed integration test passes.

- [ ] **Step 4: Commit the integration test**

```powershell
git add app/src/androidTest/java/com/iamxpp/isaver/transfer/RootStreamTransferInstrumentedTest.kt
git commit -m "test: verify root stream publishing on device"
```

### Task 7: Documentation, UI Regression, and Xiaomi 9 Acceptance

**Files:**
- Modify: `E:/PROJECT/Android_files/项目文档/iSaver_PRD_需求说明书.md`
- Modify: `E:/PROJECT/Android_files/项目文档/iSaver_SDD_系统设计文档.md`
- Modify: `docs/superpowers/plans/2026-07-13-ios-share-save-picker.md`
- Modify: `app/src/main/res/values/themes.xml`
- Add: `app/src/androidTest/java/com/iamxpp/isaver/ui/theme/ThemeConfigurationInstrumentedTest.kt`

- [ ] **Step 1: Update PRD/SDD stream semantics**

Keep version 3.3/date 2026-07-16 and replace “Root directly reads internal cache” with:

```text
来源 Uri 仍先复制到 iSaver 内部 UUID cache。发布时 iSaver 为该已验证 cache 生成 60 秒、一次性、Root/Shell-only 的流能力；固定 ContentProvider 通过 Binder 文件描述符把字节交给 native helper stdin。不得把明文中转到共享存储，不得 chmod/chown，不得向 Root 层暴露任意应用私有路径。
```

Document exact-size stdin validation, token replay rejection, one fresh capability per collision attempt, and existing uncertain-outcome policy. Preserve the already-approved inline three-tab save UI and Force Dark opt-out.

- [ ] **Step 2: Run the full repository gates**

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug assembleDebugAndroidTest
git diff --check
```

Expected: all commands exit 0, no test/lint/build failures, and no whitespace errors.

- [ ] **Step 3: Run non-Activity instrumentation classes**

Install through the MIUI Root path and run the provider, Root stream, theme, parser/manifest, and repository integration classes. Then run the complete suite; if MIUI blocks Compose Activity launch, use the established host-triggered Activity wake only for those classes and report the teardown limitation separately.

- [ ] **Step 4: Exercise the exact ACTION_VIEW UI and save workflow**

```powershell
adb -s d51f42ac shell su -c "rm -rf /data/local/tmp/isaver-inline-save-test && mkdir -p /data/local/tmp/isaver-inline-save-test"
adb -s d51f42ac shell am force-stop com.iamxpp.isaver
adb -s d51f42ac shell am start -W -a android.intent.action.VIEW -d content://com.iamxpp.isaver.debug-share/report.pdf -t application/pdf --grant-read-uri-permission -n com.iamxpp.isaver/.MainActivity
```

Using iSaver, enter the isolated target from Browse or an existing temporary custom location and tap “存储”. Verify:

- Views/custom locations and all three tabs remain visible before target selection.
- Only “存储” appears in the top right during save mode.
- “测试 报告” and “pdf” are dark and visible in system night mode.
- The inline bar is immediately above the bottom tabs and no more than 112dp.
- Root stat reports exactly one `测试 报告.pdf` with 37 bytes and matching fixture bytes.
- A second save creates `测试 报告 (1).pdf` without changing the first.

- [ ] **Step 5: Clean isolated state and inspect relevant logs**

```powershell
adb -s d51f42ac shell su -c "rm -rf /data/local/tmp/isaver-inline-save-test"
adb -s d51f42ac logcat -d -t 300 | Select-String "FATAL EXCEPTION|AndroidRuntime|com.iamxpp.isaver"
```

Expected: test directory and temporary stages are gone, and no iSaver fatal exception exists. Do not add screenshots, XML dumps, or logs to Git.

- [ ] **Step 6: Commit the verified theme/docs slice**

```powershell
git add app/src/main/res/values/themes.xml app/src/androidTest/java/com/iamxpp/isaver/ui/theme/ThemeConfigurationInstrumentedTest.kt docs/superpowers/plans/2026-07-13-ios-share-save-picker.md
git diff --cached --check
git commit -m "fix: complete inline root save workflow"
```

The external PRD/SDD are verified separately because they are outside this repository.

- [ ] **Step 7: Review and push**

```powershell
git status --short --ignored
git log --oneline --decorate -15
git diff --check
git push origin develop/m1-root-browsing
```

Expected: only intentional source/test/docs changes are tracked; build output, local properties, device data, screenshots, logs, credentials, and ADB keys remain ignored; push succeeds without force.
