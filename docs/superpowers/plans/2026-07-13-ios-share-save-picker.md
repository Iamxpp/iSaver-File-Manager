# iOS-style Share Save Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax.

**Goal:** Make single-file ACTION_SEND and content ACTION_VIEW open a full-screen iOS-style save picker with early private caching, Root-safe destination selection, and independently editable stem/extension fields.

**Architecture:** ShareIntentParser normalizes SEND and VIEW into IncomingShare on IO. TransferViewModel owns the temporary Uri capability, begins IncomingFileCache immediately, and exposes a save-picker state independent of the normal three-tab scaffold. MainActivity dispatches cold/warm intents and renders ShareSavePickerScreen while a request is active.

**Tech Stack:** Android Intent/ContentResolver, Kotlin coroutines/StateFlow, SavedStateHandle, Compose, Room recent items, native Root transfer helper, JVM/Android instrumentation/ADB.

**Implementation status (2026-07-14):** Tasks 1–3、5–6 已实现并通过 JVM/lint/build、小米 9 Manifest resolution 与 Compose instrumentation；Task 4 的即时缓存、publish 边界、单 queued generation、retry/uncertain reconciliation 和 orphan TTL 已实现。Task 7 仍需补齐旋转/进程死亡/大文件/ENOSPC 的整套端到端矩阵与最终人工微信 PDF 烟测，完成前不得宣称整个分享里程碑发布完成。

---

### Task 1: SEND and VIEW Parser Contract

**Files:**
- Modify: app/src/main/java/com/iamxpp/isaver/share/ShareIntentParser.kt
- Modify: app/src/main/java/com/iamxpp/isaver/share/IncomingShare.kt
- Modify: app/src/test/java/com/iamxpp/isaver/share/ShareIntentParserTest.kt

- [ ] Write RED API29/33/35 tests for VIEW intent.data, SEND EXTRA_STREAM, one-item and multi-item ClipData, matching extra+clip, conflicting sources, MAIN, SEND_MULTIPLE, file/http, bad Parcelable, and content readability without trusting flags.
- [ ] Add RED metadata tests for null/blank DISPLAY_NAME, null/negative SIZE, MIME fallback, Security/runtime provider failures, caller cancellation, and the 2-second asynchronous Parser timeout driven by `CancellationSignal`.
- [ ] Confirm existing VIEW rejection test fails for the new required behavior.
- [ ] Implement a single URI extractor: VIEW uses data; SEND accepts exactly one content Uri; all other schemes/actions fail typed.
- [ ] Implement the bounded asynchronous Parser entry in this task: keep metadata work outside Main, query with `CancellationSignal`, propagate caller `CancellationException`, return typed `PROVIDER_TIMEOUT` after 2 seconds, cancel/ignore late work without promising an uncooperative Provider process stops, and never resolve filesystem paths.
- [ ] Run focused parser tests.
- [ ] Commit: feat: accept content view intents

### Task 2: Manifest and Activity Intent Dispatch

**Files:**
- Modify: app/src/main/AndroidManifest.xml
- Modify: app/src/main/java/com/iamxpp/isaver/MainActivity.kt
- Create: app/src/androidTest/java/com/iamxpp/isaver/share/ShareIntentResolutionTest.kt
- Modify: app/src/test/java/com/iamxpp/isaver/MainActivityTest.kt

- [ ] Write RED PackageManager tests proving SEND and content VIEW resolve iSaver while file/http VIEW and SEND_MULTIPLE do not; separately prove file/http SEND can resolve by MIME but Parser rejects it at runtime.
- [ ] Write RED Activity tests for cold intent once, MAIN normal launch, onNewIntent, history relaunch suppression, and Root-gate pending request retention.
- [ ] Add the narrow content VIEW filter and singleTop launch mode; assert file/http do not resolve only for ACTION_VIEW because ACTION_SEND URI scheme lives in EXTRA_STREAM and is rejected at runtime by Parser.
- [ ] Dispatch initial intent once after ViewModel creation and override onNewIntent with setIntent plus ViewModel handling.
- [ ] Run focused tests and Xiaomi 9 resolution checks.
- [ ] Commit: feat: route opened files into save mode

### Task 3: Editable Output Name Model

**Files:**
- Create: app/src/main/java/com/iamxpp/isaver/transfer/OutputNameDraft.kt
- Create: app/src/test/java/com/iamxpp/isaver/transfer/OutputNameDraftTest.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TransferUiState.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TransferViewModel.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TargetNameResolver.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/RootFileTransferRepository.kt
- Modify: app/src/test/java/com/iamxpp/isaver/transfer/TargetNameResolverTest.kt
- Modify: app/src/test/java/com/iamxpp/isaver/transfer/RootFileTransferRepositoryTest.kt

- [ ] Write RED tests for report.pdf, archive.tar.gz, .env, name., Chinese/emoji, empty extension, multi-part extension, leading-dot extension rejection, slash/NUL, lone surrogate, dot/dot-dot, and 255 UTF-8 bytes.
- [ ] Implement deterministic split and combine. Stem is required; extension is optional and excludes the separately rendered leading dot.
- [ ] Preserve the validated stem/extension boundary through Repository naming; prove explicit `archive` + `tar.gz` retries as `archive (1).tar.gz`, while the default split of `archive.tar.gz` is `archive.tar` + `gz`.
- [ ] Add setStem and setExtension actions; Caching/Choosing/Validating/Saving/Cancelling/Reconciliation/Failure/Uncertain active states retain drafts.
- [ ] Pass validated output-name parts to RootFileTransferRepository instead of a combined name that would require guessing the extension again.
- [ ] Run focused output-name and TransferViewModel tests.
- [ ] Commit: feat: edit shared output name and extension

### Task 4: Immediate Private Cache State Machine

**Files:**
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/IncomingFileCache.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TransferUiState.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TransferViewModel.kt
- Modify: app/src/test/java/com/iamxpp/isaver/transfer/IncomingFileCacheTest.kt
- Modify: app/src/test/java/com/iamxpp/isaver/transfer/TransferViewModelTest.kt

- [ ] Write RED tests proving acceptShare starts caching immediately even behind Root gate, chooser remains navigable after grant, byte progress updates, save waits for cache, cancel/Root-gate exit cleans pre-publish cache, provider loss requires reshare, and SavedState contains summary but no Uri/cache path.
- [ ] Define each `RootFileSystem.transferFromAppCache` call as one non-cancellable in-flight publish window; add RED cancellation and new-intent tests immediately before, during, and between ALREADY_EXISTS attempts.
- [ ] Prove ALREADY_EXISTS advances only when no cancel/new Intent is pending; all other Failure/Uncertain results never replay or change candidate automatically.
- [ ] Prove at most one queued generation is parsed/cached immediately but cannot hide the active request; replacement cleans the old queued cache; Success and non-retryable Failure activate the queue; retryable Failure requires an explicit retry-old versus clean-and-continue choice; Success finishes only without a queue.
- [ ] Prove Uncertain stays visible with cache while the process lives until “已核对并清理缓存后继续”; after process death only an uncertain RequiresReshare summary remains and the unowned file follows the 24-hour orphan TTL.
- [ ] Add orphan-cache RED tests: no cache path is restored after process death and unowned incoming files older than 24 hours are removed on startup.
- [ ] Add RED safety tests for cached writable being only a hint; fresh target symlink/non-writable/canonical-identity changes, Root loss, and cache regular-file/device/inode/size mismatch must block publish without unsafe cleanup or replay.
- [ ] Add RED retry-policy tests: picker-active retryable definite failures retain cache but require explicit retry and fresh validation; exit/non-retryable failure cleans cache; Success cleans with warning semantics; Uncertain retains cache in-process until acknowledgement and becomes a 24-hour orphan after process death.
- [ ] Refactor ViewModel into Parsing/Caching/Choosing/Validating/Saving/Reconciliation terminal states without persisting capabilities; consume the Parser timeout result without adding a second conflicting timeout layer.
- [ ] Preserve OUTCOME_UNCERTAIN and source cache while the process lives; record recent only after Success.
- [ ] Run focused and full JVM tests.
- [ ] Commit: feat: prepare shared files while choosing destination

### Task 5: Hilt Application Dependency Graph

**Files:**
- Modify: build.gradle.kts
- Modify: gradle/libs.versions.toml
- Modify: app/build.gradle.kts
- Modify: app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt
- Modify: app/src/main/java/com/iamxpp/isaver/MainActivity.kt
- Create: app/src/main/java/com/iamxpp/isaver/di/TransferModule.kt
- Modify: app/src/test/java/com/iamxpp/isaver/ISaverApplicationTest.kt

- [ ] Write RED graph tests that the production parser, cache, transfer repository, resolver, recent recorder, IO dispatcher, and TransferViewModel are wired once.
- [ ] Add the Hilt plugin/compiler, `@HiltAndroidApp`, `@AndroidEntryPoint`, a focused TransferModule, and `@HiltViewModel` SavedStateHandle injection without exposing Root operations to Activity/Composable.
- [ ] Dispatch incoming intents before Root grant so private caching can overlap the Root gate; prohibit Root navigation/write until Granted and clean cache when the gate exits.
- [ ] Ensure no Activity/Composable performs Root or ContentResolver work directly.
- [ ] Run application graph and Activity tests.
- [ ] Commit: feat: wire share save dependencies

### Task 6: Full-screen Save Picker Compose UI

**Files:**
- Create: app/src/main/java/com/iamxpp/isaver/ui/ShareSavePickerScreen.kt
- Create: app/src/androidTest/java/com/iamxpp/isaver/ui/ShareSavePickerScreenTest.kt
- Modify: app/src/main/java/com/iamxpp/isaver/ui/ISaverHomeScreen.kt
- Modify: app/src/main/java/com/iamxpp/isaver/MainActivity.kt

- [ ] Write RED Compose tests for hidden three-tab bar, centered compact title, cancel/back, overflow/save, close search spacing, directories enabled/files disabled, item count, two text fields, progress, retry, uncertain, and no Uri/cache semantics.
- [ ] Implement a separate scaffold reusing location/browser rows but not FilesBottomBar.
- [ ] Save is enabled only for cache-ready + valid directory + valid combined name + idle operation.
- [ ] Show Cancelling/Reconciliation after a post-boundary cancel and prevent a queued request from hiding an old Uncertain result.
- [ ] On Success emit one Activity finish event only when no queued request exists; switch to queued request instead when present, and never finish for Failure or Uncertain.
- [ ] Run focused instrumentation via Root pm install.
- [ ] Commit: feat: add ios style share save picker

### Task 7: Xiaomi 9 End-to-End Acceptance

**Files:**
- Create: app/src/androidTest/java/com/iamxpp/isaver/share/ShareSaveEndToEndTest.kt
- Create: scripts/send_isaver_test_file.ps1
- Modify: docs/superpowers/plans/2026-07-13-ios-share-save-picker.md

- [ ] Provide a test-only FileProvider fixture and send both ACTION_SEND and ACTION_VIEW content Uris; never use real WeChat data.
- [ ] Verify cold/warm launch, rotation, process recreation, Root gate, Chinese/space/quote name, edited extension, no extension, duplicate name, large file, cancel, ENOSPC, source loss, and uncertain result.
- [ ] Check cache and target staging residue and confirm only Success updates recent items.
- [ ] Capture picker screenshots and compare header, search, grid/list, save action, and two-field bottom panel with the approved mockup.
- [ ] Perform one final manual WeChat smoke test using a user-selected PDF and a dedicated iSaver test target.
- [ ] Synchronize PRD/SDD with the final implemented state machine and Manifest, then run full unit/instrumentation/lint/assemble gates, git diff checks, specification review, and code-quality review.
- [ ] Commit: test: complete share save picker acceptance
