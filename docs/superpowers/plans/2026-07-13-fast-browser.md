# Fast Root Browser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax.

**Goal:** Replace per-entry shell subprocess enumeration with one fixed native list-dir operation, add safe short-lived snapshots, and use one compact header at every directory depth.

**Architecture:** The existing native helper gains a read-only list-dir command using dirfd/readdir/fstatat and an in-process Base64 TSV protocol. Kotlin parses one DirectorySnapshot containing parent metadata and entries; BrowserViewModel displays an LRU snapshot immediately and refreshes in the background. Views and Browser share FilesPageHeader.

**Tech Stack:** Android NDK C, Kotlin coroutines/Flow, libsu, Compose, JUnit, Android instrumentation, ADB/Perfetto.

---

### Task 1: Versioned Native Listing Protocol

**Files:**
- Create: app/src/main/java/com/isaver/filemanager/data/root/DirectorySnapshot.kt
- Create: app/src/main/java/com/isaver/filemanager/data/root/NativeDirectoryListingParser.kt
- Create: app/src/test/java/com/isaver/filemanager/data/root/NativeDirectoryListingParserTest.kt

- [ ] Write RED tests for the V1 parent header, file/directory/other records, spaces, Chinese, quotes, embedded newline, symlink, unknown type, malformed Base64, field count, limits, and one bad record not corrupting valid siblings.
- [ ] Run: .\gradlew.bat testDebugUnitTest --tests com.isaver.filemanager.data.root.NativeDirectoryListingParserTest
- [ ] Confirm failure because parser and DirectorySnapshot do not exist.
- [ ] Implement the smallest strict parser. It accepts exactly the V1 header, decodes UTF-8 with CodingErrorAction.REPORT, validates RootPath, and returns typed protocol failures without logging raw paths.
- [ ] Rerun the focused test and existing DirectoryListingParserTest.
- [ ] Commit: feat: define native directory snapshot protocol

### Task 2: Native list-dir Helper

**Files:**
- Modify: app/src/main/cpp/isaver_fs_helper.c
- Modify: app/src/main/java/com/isaver/filemanager/data/root/RootTransferHelper.kt
- Create: app/src/test/java/com/isaver/filemanager/data/root/RootDirectoryHelperTest.kt
- Create: scripts/verify_root_list_helper.ps1

- [ ] Write RED command-construction tests proving only the fixed helper path and list-dir subcommand are emitted and the path is one safely quoted argument.
- [ ] Add RED device-script assertions for 0/50/200 entries, newline/quote/Chinese names, symlink metadata, no per-entry child process, and output limits.
- [ ] Implement list-dir with open O_DIRECTORY/O_NOFOLLOW, fdopendir/readdir, fstatat AT_SYMLINK_NOFOLLOW, in-C Base64, checked allocation, EINTR handling, and 100,000-item/64-MiB limits.
- [ ] Ensure no generic command, chmod/chown, symlink following, or write syscall is introduced.
- [ ] Run focused JVM tests, four-ABI assembleDebug, then .\scripts\verify_root_list_helper.ps1 -Serial d51f42ac.
- [ ] Commit: feat: enumerate root directories natively

### Task 3: Production RootFileSystem Integration

**Files:**
- Modify: app/src/main/java/com/isaver/filemanager/data/root/RootFileSystem.kt
- Modify: app/src/main/java/com/isaver/filemanager/data/root/LibsuRootFileSystem.kt
- Modify: app/src/test/java/com/isaver/filemanager/data/root/LibsuRootFileSystemTest.kt

- [ ] Write RED tests for readDirectory(path) returning entries and parent capabilities from one helper invocation, native typed exits, timeout, cancellation, malformed output, and no follow-up parent stat.
- [ ] Run the focused LibsuRootFileSystem tests and confirm the missing API/old shell list failure.
- [ ] Add readDirectory returning OperationResult<DirectorySnapshot>; keep list as a compatibility default for narrow fakes while production delegates to native.
- [ ] Remove the production per-entry withRecordEmitter list path after all call sites migrate.
- [ ] Rerun all root data-layer tests.
- [ ] Commit: refactor: use native root directory snapshots

### Task 4: LRU Snapshot and Perceived Loading

**Files:**
- Create: app/src/main/java/com/isaver/filemanager/ui/DirectorySnapshotCache.kt
- Create: app/src/test/java/com/isaver/filemanager/ui/DirectorySnapshotCacheTest.kt
- Modify: app/src/main/java/com/isaver/filemanager/ui/BrowserViewModel.kt
- Modify: app/src/test/java/com/isaver/filemanager/ui/BrowserViewModelTest.kt

- [ ] Write RED tests for 16-entry LRU, 2-second TTL, immediate stale snapshot, background refresh, navigation generation, refresh failure preserving old rows, and no initial storage prefetch.
- [ ] Write RED virtual-time test proving the blocking spinner remains hidden for loads completing before 120ms.
- [ ] Implement the cache with injectable monotonic clock; never persist it or use cached writable as write authorization.
- [ ] Change BrowserViewModel to load only after explicit openRoot, show cached rows with refreshing, and consume parent metadata from DirectorySnapshot.
- [ ] Run focused ViewModel tests.
- [ ] Commit: perf: show cached root directories immediately

### Task 5: One Compact Header Everywhere

**Files:**
- Modify: app/src/main/java/com/isaver/filemanager/ui/files/FilesComponents.kt
- Modify: app/src/main/java/com/isaver/filemanager/ui/LocationHomeScreen.kt
- Modify: app/src/main/java/com/isaver/filemanager/ui/BrowserScreen.kt
- Modify: app/src/androidTest/java/com/isaver/filemanager/ui/files/FilesComponentsTest.kt
- Modify: app/src/androidTest/java/com/isaver/filemanager/ui/BrowserScreenTest.kt

- [ ] Write RED bounds tests: title centerX equals bar centerX; title/back/overflow vertical ranges overlap; search begins immediately after bar; long title remains one line.
- [ ] Replace Browser FilesLargeTitleHeader with shared FilesPageHeader and remove vertical search top padding.
- [ ] Keep root left slot empty and show back only below root.
- [ ] Run focused Compose instrumentation on Xiaomi 9 using Root pm install.
- [ ] Capture a device screenshot for root and cache directory and compare title/search/overflow positions.
- [ ] Commit: fix: align all browser navigation bars

### Task 6: Performance Acceptance

**Files:**
- Create: scripts/benchmark_root_listing.ps1
- Create: app/src/androidTest/java/com/isaver/filemanager/ui/RootBrowserPerformanceTest.kt
- Modify: docs/superpowers/plans/2026-07-13-fast-browser.md

- [x] Build only /data/local/tmp/isaver-perf fixtures with 0/50/200/1000 entries; cleanup in finally.
- [x] Record 20 cold and warm runs for helper, parse/sort, and state-ready first-visible.
- [x] Assert helper process count is O(1), 200-entry cold P95 below 500ms, cache hit below 100ms, and 1000-entry first-visible below 500ms.
- [x] Run focused unit tests, lintDebug, assembleDebug/assembleDebugAndroidTest, git diff checks, and sensitive-file scan.
- [x] Complete specification and code-quality review loops for the fast-browser implementation.
- [x] Commit: perf: complete fast root browser acceptance

#### Xiaomi 9 evidence — 2026-07-14

- Native helper, 20 samples: 200-entry cold P50/P95 `29/31 ms`; warm `29/31 ms`; 1000-entry first run `43/50 ms`.
- App `RootFileSystem.readDirectory`, 20 first-observation samples: 200-entry P50/P95 `25.06/38.14 ms`; repeated warm reads `30.35/39.92 ms`.
- Browser in-process snapshot presentation, 20 samples: cache-hit P50/P95 `1.11/1.36 ms`.
- Browser helper + protocol parse + sort to first non-empty state, 20 unique 1000-entry directories: P50/P95 `413.54/448.12 ms`.
- `strace`: one helper `execve`, zero `clone/clone3/fork/vfork`; fixture and deployed benchmark helper cleanup verified.
- The 1000-entry metric stops when `BrowserUiState.entries` becomes non-empty; a separate installed-APK screenshot confirms Compose renders that state. It is a deterministic state-ready proxy rather than a frame-timing claim.
