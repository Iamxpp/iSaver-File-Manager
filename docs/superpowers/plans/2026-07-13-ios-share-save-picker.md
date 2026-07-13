# iOS-style Share Save Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax.

**Goal:** Make single-file ACTION_SEND and content ACTION_VIEW open a full-screen iOS-style save picker with early private caching, Root-safe destination selection, and independently editable stem/extension fields.

**Architecture:** ShareIntentParser normalizes SEND and VIEW into IncomingShare on IO. TransferViewModel owns the temporary Uri capability, begins IncomingFileCache immediately, and exposes a save-picker state independent of the normal three-tab scaffold. MainActivity dispatches cold/warm intents and renders ShareSavePickerScreen while a request is active.

**Tech Stack:** Android Intent/ContentResolver, Kotlin coroutines/StateFlow, SavedStateHandle, Compose, Room recent items, native Root transfer helper, JVM/Android instrumentation/ADB.

---

### Task 1: SEND and VIEW Parser Contract

**Files:**
- Modify: app/src/main/java/com/iamxpp/isaver/share/ShareIntentParser.kt
- Modify: app/src/test/java/com/iamxpp/isaver/share/ShareIntentParserTest.kt

- [ ] Write RED API29/33/35 tests for VIEW intent.data, SEND EXTRA_STREAM, one-item ClipData, matching extra+clip, conflicting sources, MAIN, SEND_MULTIPLE, file/http, bad Parcelable, provider failure, and content readability without trusting flags.
- [ ] Confirm existing VIEW rejection test fails for the new required behavior.
- [ ] Implement a single URI extractor: VIEW uses data; SEND accepts exactly one content Uri; all other schemes/actions fail typed.
- [ ] Keep metadata queries outside Main dispatcher and never resolve filesystem paths.
- [ ] Run focused parser tests.
- [ ] Commit: feat: accept content view intents

### Task 2: Manifest and Activity Intent Dispatch

**Files:**
- Modify: app/src/main/AndroidManifest.xml
- Modify: app/src/main/java/com/iamxpp/isaver/MainActivity.kt
- Create: app/src/androidTest/java/com/iamxpp/isaver/share/ShareIntentResolutionTest.kt
- Modify: app/src/test/java/com/iamxpp/isaver/MainActivityTest.kt

- [ ] Write RED PackageManager tests proving SEND and content VIEW resolve iSaver while file/http/SEND_MULTIPLE do not.
- [ ] Write RED Activity tests for cold intent once, MAIN normal launch, onNewIntent, history relaunch suppression, and Root-gate pending request retention.
- [ ] Add the narrow VIEW filter and singleTop launch mode.
- [ ] Dispatch initial intent once after ViewModel creation and override onNewIntent with setIntent plus ViewModel handling.
- [ ] Run focused tests and Xiaomi 9 resolution checks.
- [ ] Commit: feat: route opened files into save mode

### Task 3: Editable Output Name Model

**Files:**
- Create: app/src/main/java/com/iamxpp/isaver/transfer/OutputNameDraft.kt
- Create: app/src/test/java/com/iamxpp/isaver/transfer/OutputNameDraftTest.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TransferUiState.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TransferViewModel.kt

- [ ] Write RED tests for report.pdf, archive.tar.gz, .env, name., Chinese/emoji, empty extension, multi-part extension, leading-dot extension rejection, slash/NUL, lone surrogate, dot/dot-dot, and 255 UTF-8 bytes.
- [ ] Implement deterministic split and combine. Stem is required; extension is optional and excludes the separately rendered leading dot.
- [ ] Add setStem and setExtension actions; all Choosing/Saving/Failure states retain drafts.
- [ ] Pass the validated combined name to RootFileTransferRepository instead of the source display name.
- [ ] Run focused output-name and TransferViewModel tests.
- [ ] Commit: feat: edit shared output name and extension

### Task 4: Immediate Private Cache State Machine

**Files:**
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TransferUiState.kt
- Modify: app/src/main/java/com/iamxpp/isaver/transfer/TransferViewModel.kt
- Modify: app/src/test/java/com/iamxpp/isaver/transfer/TransferViewModelTest.kt

- [ ] Write RED tests proving acceptShare starts caching immediately, chooser remains navigable, byte progress updates, save waits for cache, cancel cleans pre-publish cache, provider loss requires reshare, and SavedState contains summary but no Uri/cache path.
- [ ] Add generation tests for new intent before publish and new intent during an already-dispatched uncertain transfer.
- [ ] Refactor ViewModel into Parsing/Caching/Choosing/Validating/Saving terminal states without persisting capabilities.
- [ ] Preserve OUTCOME_UNCERTAIN and source cache; record recent only after Success.
- [ ] Run focused and full JVM tests.
- [ ] Commit: feat: prepare shared files while choosing destination

### Task 5: Application Dependency Graph

**Files:**
- Modify: app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt
- Modify: app/src/main/java/com/iamxpp/isaver/MainActivity.kt
- Modify: app/src/test/java/com/iamxpp/isaver/ISaverApplicationTest.kt

- [ ] Write RED factory tests that the production parser, cache, transfer repository, resolver, and recent recorder are wired once.
- [ ] Add a TransferViewModel factory with SavedStateHandle support and IO dispatcher.
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
- [ ] On Success emit one Activity finish event; do not finish for Failure or Uncertain.
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
- [ ] Run full unit/instrumentation/lint/assemble gates, git diff checks, specification review, and code-quality review.
- [ ] Commit: test: complete share save picker acceptance
