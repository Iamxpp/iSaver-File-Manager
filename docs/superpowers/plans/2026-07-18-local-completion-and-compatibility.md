# iSaver Local Completion and Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete iSaver's local 0.1.0 user flows for recent items, file information, archive browsing/extraction, read-only/protected locations, and produce an API 29/30/33/35 runtime compatibility matrix.

**Architecture:** Keep directory browsing, recent items, archives, locations, and share transfer in separate ViewModels with typed callbacks. Add an identity-bound extraction staging API to `RootFileSystem` and the allowlisted native helper so cancellation can clean only the hidden directory created by the current extraction, then atomically publish the whole directory. Reuse existing iOS Files-style Compose components and Root-only gate.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, MVVM, coroutines/Flow, Room, DataStore, libsu, C native helper, JUnit4, Robolectric, Compose UI Test, UIAutomator, ADB/SU, Android Emulator.

---

### Task 1: Recent activity model and repository contract

**Files:**
- Modify: `app/src/main/java/com/isaver/filemanager/recent/RecentRepository.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/recent/RecentRepositoryTest.kt`
- Verify: `app/src/main/java/com/isaver/filemanager/data/local/RecentItemEntity.kt`

- [ ] **Step 1: Write failing repository tests**

Add tests proving compressed and extracted activities round-trip without a Room schema change:

```kotlin
@Test fun `record compressed stores archive activity`() = runTest {
    repository.recordCompressed(path("/archives/output.zip"), "output.zip")
    assertEquals(RecentActivity.COMPRESSED, repository.observeRecent().first().single().activity)
    assertEquals(RecentItemType.ARCHIVE, repository.observeRecent().first().single().type)
}

@Test fun `record extracted stores destination directory activity`() = runTest {
    repository.recordExtracted(path("/extract/output"), "output")
    val item = repository.observeRecent().first().single()
    assertEquals(RecentActivity.EXTRACTED, item.activity)
    assertEquals(RecentItemType.DIRECTORY, item.type)
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.isaver.filemanager.recent.RecentRepositoryTest"
```

Expected: compilation fails because `COMPRESSED`, `EXTRACTED`, `recordCompressed`, and `recordExtracted` do not exist.

- [ ] **Step 3: Implement the minimal repository API**

Extend the enum and add typed convenience methods:

```kotlin
enum class RecentActivity { ACCESSED, SAVED, COMPRESSED, EXTRACTED }

suspend fun recordCompressed(canonicalPath: RootPath, displayName: String) =
    record(canonicalPath, displayName, null, RecentItemType.ARCHIVE, RecentActivity.COMPRESSED)

suspend fun recordExtracted(canonicalPath: RootPath, displayName: String) =
    record(canonicalPath, displayName, null, RecentItemType.DIRECTORY, RecentActivity.EXTRACTED)
```

- [ ] **Step 4: Verify GREEN**

Run the focused repository test and `RecentItemDaoRoomTest`. Expected: both suites pass and Room schema stays at version 2.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/isaver/filemanager/recent/RecentRepository.kt app/src/test/java/com/isaver/filemanager/recent/RecentRepositoryTest.kt
git commit -m "feat: model complete recent activities"
```

### Task 2: Recent ViewModel and real Recent screen

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/ui/recent/RecentUiState.kt`
- Create: `app/src/main/java/com/isaver/filemanager/ui/recent/RecentViewModel.kt`
- Create: `app/src/main/java/com/isaver/filemanager/ui/recent/RecentScreen.kt`
- Create: `app/src/test/java/com/isaver/filemanager/ui/recent/RecentViewModelTest.kt`
- Create: `app/src/androidTest/java/com/isaver/filemanager/ui/recent/RecentScreenTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/ISaverHomeScreen.kt`
- Modify: `app/src/androidTest/java/com/isaver/filemanager/ui/ISaverHomeScreenTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Cover database-empty, valid item, unavailable item, type change, four-way click routing, and bounded refresh:

```kotlin
@Test fun `unavailable recent item remains visible and cannot open`() = runTest {
    recentFlow.value = listOf(recent("/gone", RecentItemType.FILE))
    fileSystem.statResult = failure(ErrorCode.NOT_FOUND)
    val viewModel = viewModel()
    advanceUntilIdle()
    assertEquals("项目不可用", viewModel.state.value.items.single().status)
    assertFalse(viewModel.open(viewModel.state.value.items.single()))
}
```

- [ ] **Step 2: Verify ViewModel RED**

Run the new test class. Expected: compilation fails because the recent UI types do not exist.

- [ ] **Step 3: Implement recent state and probing**

Use a state shape with explicit routing data:

```kotlin
data class RecentUiItem(
    val item: RecentItem,
    val availability: RecentAvailability,
)

sealed interface RecentAvailability {
    data object Checking : RecentAvailability
    data class Available(val entry: DirectoryEntry) : RecentAvailability
    data class Unavailable(val reason: String) : RecentAvailability
}
```

Observe `RecentRepository`, probe with a four-permit semaphore on the injected dispatcher, call `markAvailability`, and expose callbacks for directory, archive, and ordinary file destinations.

- [ ] **Step 4: Verify ViewModel GREEN**

Run `RecentViewModelTest`; expected all tests pass.

- [ ] **Step 5: Write failing Compose tests**

Assert empty state only for an empty list, four activity labels, unavailable disabled semantics, refresh, list/grid, and click callback.

- [ ] **Step 6: Implement `RecentScreen`**

Reuse `FilesPageHeader`, `FileListRow`, `FileGridCell`, and existing display/sort preferences. Map labels exactly:

```kotlin
private fun RecentActivity.label() = when (this) {
    RecentActivity.ACCESSED -> "已访问"
    RecentActivity.SAVED -> "已保存"
    RecentActivity.COMPRESSED -> "已压缩"
    RecentActivity.EXTRACTED -> "已解压"
}
```

Replace `RecentEmptyScreen` in `ISaverHomeScreen` with the real screen and keep the bottom tabs visible.

- [ ] **Step 7: Verify Compose GREEN and commit**

Run both recent test classes and `ISaverHomeScreenTest`, then commit as `feat: add real recent projects page`.

### Task 3: Protected path policy and read-only custom locations

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/domain/RootPathRiskPolicy.kt`
- Create: `app/src/test/java/com/isaver/filemanager/domain/RootPathRiskPolicyTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/LocationHomeUiState.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/LocationHomeViewModel.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/LocationHomeScreen.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/ui/LocationHomeViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/isaver/filemanager/ui/LocationHomeScreenTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ISaverApplication.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/ISaverApplicationTest.kt`

- [ ] **Step 1: Write and run protected-path RED tests**

Assert `/system`, `/system/bin`, `/vendor`, `/product`, `/boot` are protected while `/system2`, `/vendor_backup`, and `/storage/emulated/0` are not. Expected RED: `RootPathRiskPolicy` is missing.

- [ ] **Step 2: Implement component-boundary policy and verify GREEN**

```kotlin
object RootPathRiskPolicy {
    private val protectedRoots = setOf("/system", "/vendor", "/product", "/boot")
    fun isProtected(path: RootPath): Boolean = protectedRoots.any { root ->
        path.value == root || path.value.startsWith("$root/")
    }
}
```

- [ ] **Step 3: Write read-only location and single-refresh RED tests**

Prove a readable/non-writable directory is persisted, an unreadable directory is rejected, `revalidateCustomLocation(id)` probes only one row, and its transient state is `Checking`.

- [ ] **Step 4: Implement read-only persistence and single-item revalidation**

Remove the writable rejection from `mutate`; retain the readable/directory checks. Add per-item generation tracking so a late result cannot overwrite a newer edit/removal.

- [ ] **Step 5: Write and implement Compose protection tests**

Assert “只读”, “系统保护区域 · 只读”, and “重新校验” are visible with exact content descriptions; protected and read-only locations remain openable for browsing.

- [ ] **Step 6: Enforce write prohibition centrally**

Update `validateTransferTarget` so it returns `NOT_WRITABLE` before Root write validation when `RootPathRiskPolicy.isProtected(path)` is true. Browser and extraction target eligibility must call the same policy.

- [ ] **Step 7: Verify and commit**

Run domain, location ViewModel, location Compose, and application tests; commit as `feat: support protected read-only locations`.

### Task 4: File information and long-press compression selection

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/ui/FileInfoDialog.kt`
- Create: `app/src/androidTest/java/com/isaver/filemanager/ui/FileInfoDialogTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/BrowserUiState.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/BrowserViewModel.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/BrowserScreen.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/files/FilesComponents.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/ui/BrowserViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/isaver/filemanager/ui/BrowserScreenTest.kt`

- [ ] **Step 1: Write file routing and selection RED tests**

Prove ordinary click sets `fileInfo`, archive click emits an archive-open event, long press selects readable files/directories, selection-mode click toggles, and unreadable/symlink/OTHER items cannot be selected.

- [ ] **Step 2: Implement minimal Browser state behavior**

Add:

```kotlin
val fileInfo: DirectoryEntry? = null
val archiveToOpen: DirectoryEntry? = null
val selectionMode: Boolean get() = selectedEntries.isNotEmpty()
```

Provide `openEntry`, `selectEntry`, `dismissFileInfo`, `consumeArchiveOpen`, and `clearSelection`. Do not perform archive I/O in `BrowserViewModel`.

- [ ] **Step 3: Verify state GREEN**

Run focused `BrowserViewModelTest` methods and the whole class.

- [ ] **Step 4: Write Compose RED tests**

Assert long click enters selection, selected count and clear action appear, normal file click opens the info dialog, all required fields render, unreadable metadata says “不可读”, and directory compression source is selectable.

- [ ] **Step 5: Implement combined click and information UI**

Change `FileListRow`/`FileGridCell` to accept `onLongClick` and use `combinedClickable`. Render the info dialog without content reads. Show protected banner when the current path is protected and force create/extract actions disabled.

- [ ] **Step 6: Verify and commit**

Run `FileInfoDialogTest`, `BrowserScreenTest`, and file component tests; commit as `feat: add file info and selection mode`.

### Task 5: Archive tree and full-page archive browser

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/archive/ArchiveTree.kt`
- Create: `app/src/test/java/com/isaver/filemanager/archive/ArchiveTreeTest.kt`
- Create: `app/src/main/java/com/isaver/filemanager/ui/archive/ArchiveUiState.kt`
- Create: `app/src/main/java/com/isaver/filemanager/ui/archive/ArchiveViewModel.kt`
- Create: `app/src/main/java/com/isaver/filemanager/ui/archive/ArchiveScreen.kt`
- Create: `app/src/test/java/com/isaver/filemanager/ui/archive/ArchiveViewModelTest.kt`
- Create: `app/src/androidTest/java/com/isaver/filemanager/ui/archive/ArchiveScreenTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/ISaverHomeUiState.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/ISaverHomeViewModel.kt`

- [ ] **Step 1: Write archive-tree RED tests**

Cover synthetic intermediate folders, direct children, `.tar.gz` display name stripping, directory-first natural sorting, empty archives, and back navigation.

- [ ] **Step 2: Implement `ArchiveTree` and verify GREEN**

Keep original safe relative paths and expose only direct children of a normalized prefix:

```kotlin
data class ArchiveNode(val name: String, val path: String, val directory: Boolean, val sizeBytes: Long?)
fun ArchiveListing.children(prefix: String): List<ArchiveNode>
```

- [ ] **Step 3: Write ArchiveViewModel RED tests**

Assert loading, inspect success/failure, nested navigation, back, search, five formats, stale request suppression, and `chooseExtractionTarget` transition.

- [ ] **Step 4: Implement ArchiveViewModel and verify GREEN**

Inject `inspect`, `extract`, recent-record callbacks, and an IO dispatcher. Keep `ArchiveState` progress intact; do not store Root shell commands.

- [ ] **Step 5: Write and implement Archive Compose tests**

Use existing headers/rows/grid. Assert title, format, nested folder navigation, file metadata, error, progress, cancel, non-cancellable finalization text, and “解压” action.

- [ ] **Step 6: Extend home destination tests and commit**

Add `HomeDestination.Archive(source, sourceTab)` and `HomeDestination.ExtractionTarget(source, sourceTab)` with SavedState containing only Root paths/names, never private cache paths. Commit as `feat: add archive browsing UI`.

### Task 6: Identity-bound extraction staging primitives

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/data/root/ExtractionStage.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/data/root/RootFileSystem.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/data/root/RootTransferHelper.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/data/root/LibsuRootFileSystem.kt`
- Modify: `app/src/main/cpp/isaver_fs_helper.c`
- Create: `app/src/test/java/com/isaver/filemanager/data/root/ExtractionStageTest.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/data/root/RootTransferHelperTest.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/data/root/LibsuRootFileSystemTest.kt`
- Modify: `scripts/verify_root_transfer_helper.ps1`

- [ ] **Step 1: Write typed API and command RED tests**

Define the wished-for API in tests:

```kotlin
suspend fun prepareExtractionStage(parent: RootPath): OperationResult<ExtractionStage>
suspend fun createExtractionDirectory(stage: ExtractionStage, relativePath: String): OperationResult<Unit>
suspend fun transferIntoExtractionStage(
    stage: ExtractionStage,
    relativeParent: String,
    source: RootTransferSource,
    finalName: EntryName,
): OperationResult<Unit>
suspend fun commitExtractionStage(stage: ExtractionStage, finalName: FolderName): OperationResult<DirectoryEntry>
suspend fun cleanupExtractionStage(stage: ExtractionStage): OperationResult<Unit>
```

Assert names match `.isaver-extract-<uuid>`, relative paths reject absolute/NUL/`..`, and every helper command includes original/canonical parent plus device/inode identities. `transferIntoExtractionStage` must consume a fresh one-time source token per file and must never accept an arbitrary destination Root path.

- [ ] **Step 2: Verify Kotlin RED and implement data types/commands**

Run focused tests, confirm missing APIs, then implement only the typed models and fixed command encoding. Verify GREEN before native behavior.

- [ ] **Step 3: Add failing native helper fixture checks**

Extend `verify_root_transfer_helper.ps1` to require: prepare stage, nested directory creation, safe payload placement, no-overwrite commit, collision preservation, symlink rejection, cancellation cleanup, identity-swap rejection, and no stage residue.

- [ ] **Step 4: Implement fixed native subcommands**

Add only `prepare-extract-stage`, `mkdir-extract`, `copy-extract-stdin`, `commit-extract-stage`, and `remove-extract-stage`. Traverse with held directory FDs and `openat/fstatat/unlinkat`; reject symlinks; bind parent/stage identities; read each fresh one-time capability through the fixed stdin pipeline; use `renameat2(RENAME_NOREPLACE)`; never expose arbitrary remove or rename.

- [ ] **Step 5: Verify native GREEN**

Build the helper and run:

```powershell
.\gradlew.bat app:assembleDebug
.\scripts\verify_root_transfer_helper.ps1 -Serial d51f42ac
```

Expected: all extraction-stage cases pass and `/data/local/tmp/isaver-test/root-transfer-helper` is cleaned.

- [ ] **Step 6: Implement libsu mapping and verify**

Map fixed helper exits to structured errors, preserve `OUTCOME_UNCERTAIN` on lost commit result, and make cleanup non-cancellable only at the Repository boundary. Run all root filesystem tests.

- [ ] **Step 7: Commit**

Commit as `feat: add safe extraction staging primitives`.

### Task 7: Archive extraction flow, progress, cancellation, and recent records

**Files:**
- Modify: `app/src/main/java/com/isaver/filemanager/archive/ArchiveModels.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/archive/ArchiveRepository.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/archive/LocalArchiveEngine.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/archive/ArchiveRepositoryTest.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/archive/LocalArchiveEngineTest.kt`
- Modify: `app/src/androidTest/java/com/isaver/filemanager/archive/ArchiveRootInstrumentedTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ISaverApplication.kt`

- [ ] **Step 1: Write repository RED tests**

Prove extraction writes only to staging, commits once, returns the final directory entry, collision uses `(1)`, cancellation invokes stage cleanup, failure leaves no visible directory, and uncertain commit does not cleanup by guessed path.

- [ ] **Step 2: Verify RED and refactor extraction to staging**

Replace direct target publishing with one stage session. Emit:

```kotlin
sealed interface ArchiveState {
    data class Running(val progress: ArchiveProgress) : ArchiveState
    data object Cleaning : ArchiveState
    data object Finalizing : ArchiveState
    data class Success(val output: DirectoryEntry, val format: ArchiveFormat, val entryCount: Long, val expandedBytes: Long) : ArchiveState
    data class Failure(val code: ErrorCode, val message: String) : ArchiveState
}
```

Use `NonCancellable` only for identity-bound cleanup; do not swallow caller cancellation until cleanup completes.

- [ ] **Step 3: Verify repository GREEN and cancellation**

Run archive JVM suites. Cancel during local extraction and stage publish; assert no stage remains in fakes.

- [ ] **Step 4: Wire progress and recent callbacks**

On ZIP success call `recordCompressed(output.path, output.name)`; on extraction success call `recordExtracted(output.path, output.name)`. Failure/cancel/uncertain must not call either callback.

- [ ] **Step 5: Extend Root instrumentation**

Create dedicated ZIP/TAR/TAR.GZ/7Z/RAR fixtures under `/data/local/tmp/isaver-archive-test`, inspect all formats, extract representative files, cancel a large fixture, verify content, collisions, and absence of `.isaver-extract-*` after definite terminal states.

- [ ] **Step 6: Verify and commit**

Run archive JVM and Root instrumentation. Commit as `feat: complete archive extraction workflow`.

### Task 8: Activity wiring and end-to-end local flows

**Files:**
- Modify: `app/src/main/java/com/isaver/filemanager/MainActivity.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ISaverApplication.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/ISaverHomeScreen.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/BrowserViewModelFactoryTest.kt`
- Modify: `app/src/test/java/com/isaver/filemanager/ISaverApplicationTest.kt`
- Modify: `app/src/androidTest/java/com/isaver/filemanager/MainActivitySmokeTest.kt`
- Modify: `scripts/verify_release_gates.ps1`

- [ ] **Step 1: Write wiring RED tests**

Assert factories receive recent/archive dependencies, file click routes ordinary/archives correctly, extraction target is cleared on logical tabs, protected/read-only targets disable the top action, and browser success records canonical access.

- [ ] **Step 2: Implement factories and destinations**

Instantiate `RecentViewModel` and `ArchiveViewModel` from `ISaverApplication`; connect recent directory/file/archive clicks; connect Browser archive events; generalize `FilesSaveAction` to a label-bearing primary action used by “存储” and “解压到此处”.

- [ ] **Step 3: Verify JVM/Compose GREEN**

Run factory, Home ViewModel, Home screen, Browser, Recent, Archive, and Location suites.

- [ ] **Step 4: Extend UIAutomator smoke**

Create only `/data/local/tmp/isaver-test/ui-flow` fixtures through ADB/SU. Verify recent tab content, ordinary file info, read-only/protected labels, archive screen, extraction target action, progress terminal, and no FATAL/ANR.

- [ ] **Step 5: Run full Xiaomi 9 release gates**

```powershell
.\scripts\verify_release_gates.ps1 -Serial d51f42ac
```

Expected: exit 0, all named instrumentation `OK`, performance budgets pass, and known fixtures are removed.

- [ ] **Step 6: Commit**

Commit as `feat: wire complete local file workflows`.

### Task 9: API 29/30/33/35 compatibility matrix

**Files:**
- Create: `scripts/verify_android_compatibility.ps1`
- Create: `app/src/androidTest/java/com/isaver/filemanager/CompatibilitySmokeTest.kt`
- Create: `docs/testing/android-compatibility-matrix.md`
- Modify: `.gitignore` only if generated emulator reports need an ignored local directory

- [ ] **Step 1: Write script parser and contract checks**

The script must accept SDK path, require API set `29,33,35`, use only AVD names prefixed `isaver-test-`, reject an existing non-owned AVD, wait on `sys.boot_completed`, install through `adb -s`, run `CompatibilitySmokeTest`, capture API/model/ABI, and always stop only the emulator process it launched.

- [ ] **Step 2: Implement non-Root compatibility smoke**

Use UIAutomator to launch iSaver and assert “请以 Root 权限运行 iSaver”, “重新检测”, and “退出应用”; recreate Activity/process and assert the gate returns without FATAL/ANR.

- [ ] **Step 3: Install missing SDK packages**

Use the SDK from ignored `local.properties`. Install `emulator` and x86_64 images for API 29, 33, and 35 with `sdkmanager`; accept only Android SDK license prompts. Do not change `local.properties` or commit SDK paths.

- [ ] **Step 4: Execute emulator rows**

Run the compatibility script sequentially for API 29, 33, 35. Expected per row: boot completes, APK/test APK install, non-Root gate tests report `OK`, no app FATAL/ANR.

- [ ] **Step 5: Record API 30 Root row**

Use the fresh Task 8 release-gate result for serial `d51f42ac`, including model, Android/API, Root UID, local feature instrumentation, and performance metrics.

- [ ] **Step 6: Write matrix report**

Record each actual image/package version, API, ABI, Root capability, commands, pass/fail, and limitations. Mark stock Emulator rows “非 Root 门禁验收” rather than “Root 功能通过”.

- [ ] **Step 7: Run final repository verification**

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug assembleDebugAndroidTest
git diff --check
git status --short --ignored
```

Expected: all Gradle commands exit 0; only intended source/docs/scripts are unignored; no APK, AVD, image, local path, log, key, or user data is staged.

- [ ] **Step 8: Update source documents and commit**

Synchronize PRD/SDD current implementation status and the completion audit with only verified outcomes. Commit as `test: add Android compatibility matrix`.
