# Inline Share Save Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the standalone share picker with a compact save mode embedded in the existing three-tab iSaver home, while keeping source names visible under MIUI night mode.

**Architecture:** `MainActivity` always renders `ISaverHomeScreen` after Root is granted. `TransferViewModel` continues to own the incoming file and publish state, while the home/location/browser screens receive a small `SaveAction` model and render `InlineSaveBar` immediately above `FilesBottomBar`; logical tabs clear the target and real browser directories select it.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, coroutines/StateFlow, Hilt, libsu-backed typed file operations, JUnit, Compose UI Test, Android instrumentation, ADB on Xiaomi 9 API 30.

---

## File Structure

- Create `app/src/main/java/com/isaver/filemanager/ui/InlineSaveBar.kt`: compact two-field editor and transfer status/actions.
- Create `app/src/androidTest/java/com/isaver/filemanager/ui/InlineSaveBarTest.kt`: size, value, editability, and error-action UI coverage.
- Modify `app/src/main/java/com/isaver/filemanager/ui/files/FilesComponents.kt`: mutually exclusive overflow/save top-bar action.
- Modify `app/src/main/java/com/isaver/filemanager/ui/LocationHomeScreen.kt`: render save action on the Views header and explicitly color location text.
- Modify `app/src/main/java/com/isaver/filemanager/ui/BrowserScreen.kt`: render save action on every real directory header.
- Modify `app/src/main/java/com/isaver/filemanager/ui/ISaverHomeScreen.kt`: retain all tabs and insert the inline save bar.
- Modify `app/src/main/java/com/isaver/filemanager/transfer/TransferViewModel.kt`: clear stale logical targets.
- Modify `app/src/main/java/com/isaver/filemanager/MainActivity.kt`: remove standalone picker routing and synchronize home navigation with transfer targets.
- Delete `app/src/main/java/com/isaver/filemanager/ui/ShareSavePickerScreen.kt`: remove the superseded standalone product surface.
- Delete `app/src/androidTest/java/com/isaver/filemanager/ui/ShareSavePickerScreenTest.kt`: replace old behavior tests with inline-mode tests.
- Modify `app/src/main/res/values/themes.xml`: disable platform Force Dark.
- Modify adjacent unit/instrumentation tests and the PRD/SDD listed in Task 6.

### Task 1: Clear Targets Outside Real Directories

**Files:**
- Modify: `app/src/test/java/com/isaver/filemanager/transfer/TransferViewModelTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/transfer/TransferViewModel.kt`

- [ ] **Step 1: Write the failing target-clear test**

Add a test that accepts and caches a share, validates `/target`, then leaves the browser:

```kotlin
@Test
fun `clearing target disables save without discarding cached share`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = viewModel(
        dispatcher = dispatcher,
        validateTarget = { OperationResult.Success(it) },
    )
    viewModel.acceptShare(share(displayName = "report.pdf"))
    advanceUntilIdle()
    viewModel.selectTarget(root("/target"))
    advanceUntilIdle()
    assertTrue((viewModel.state.value as TransferUiState.Choosing).canSave)

    viewModel.clearTarget()

    val choosing = viewModel.state.value as TransferUiState.Choosing
    assertNull(choosing.targetDirectory)
    assertFalse(choosing.canSave)
    assertEquals(OutputNameDraft("report", "pdf"), choosing.outputName)
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.isaver.filemanager.transfer.TransferViewModelTest"
```

Expected: compilation fails because `clearTarget()` does not exist.

- [ ] **Step 3: Implement the minimal target reset**

Add next to `selectTarget`:

```kotlin
fun clearTarget() {
    val request = active ?: return
    if (publishInFlight || mutableState.value is TransferUiState.Uncertain) return
    targetValidationJob?.cancel()
    request.targetValidationGeneration++
    request.targetDirectory = null
    request.validatedCanonical = null
    request.targetMessage = null
    render(request)
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Task 1 command again. Expected: all `TransferViewModelTest` tests pass.

- [ ] **Step 5: Commit the target lifecycle slice**

```powershell
git add app/src/test/java/com/isaver/filemanager/transfer/TransferViewModelTest.kt app/src/main/java/com/isaver/filemanager/transfer/TransferViewModel.kt
git commit -m "fix: clear stale share save targets"
```

### Task 2: Build the Compact Save Bar

**Files:**
- Create: `app/src/androidTest/java/com/isaver/filemanager/ui/InlineSaveBarTest.kt`
- Create: `app/src/main/java/com/isaver/filemanager/ui/InlineSaveBar.kt`

- [ ] **Step 1: Write RED Compose tests for content and height**

Create tests that render `TransferUiState.Choosing` with `OutputNameDraft("测试 报告", "pdf")`, assert the two semantic fields contain those values, edit them to `归档` / `tar.gz`, and measure the bar:

```kotlin
@Test
fun choosingShowsCompactVisibleEditableDefaults() {
    var draft by mutableStateOf(OutputNameDraft("测试 报告", "pdf"))
    compose.setContent {
        ISaverTheme {
            InlineSaveBar(
                state = choosing(draft),
                itemCount = 3,
                onStemChange = { draft = draft.copy(stem = it) },
                onExtensionChange = { draft = draft.copy(extension = it) },
            )
        }
    }

    compose.onNodeWithTag("inline-save-stem").assertTextEquals("测试 报告")
    compose.onNodeWithTag("inline-save-extension").assertTextEquals("pdf")
    compose.onNodeWithText("3 个项目").assertIsDisplayed()
    val height = compose.onNodeWithTag("inline-save-bar").fetchSemanticsNode().boundsInRoot.height
    assertTrue("height=$height", height <= with(compose.density) { 112.dp.toPx() })
    compose.onNodeWithTag("inline-save-stem").performTextReplacement("归档")
    compose.onNodeWithTag("inline-save-extension").performTextReplacement("tar.gz")
    compose.runOnIdle { assertEquals(OutputNameDraft("归档", "tar.gz"), draft) }
}
```

Add the disabled/edit-state test:

```kotlin
@Test
fun cachingDisablesFieldsAndShowsStatusWithinCompactHeight() {
    compose.setContent {
        ISaverTheme {
            InlineSaveBar(
                state = TransferUiState.Caching(
                    share = ShareSummary("report.pdf", 10L, "application/pdf"),
                    outputName = OutputNameDraft("report", "pdf"),
                    bytesCopied = 4L,
                ),
                itemCount = 0,
                onStemChange = {},
                onExtensionChange = {},
            )
        }
    }
    compose.onNodeWithTag("inline-save-stem").assertIsNotEnabled()
    compose.onNodeWithTag("inline-save-extension").assertIsNotEnabled()
    compose.onNodeWithText("正在准备文件 · 4 B").assertIsDisplayed()
    val height = compose.onNodeWithTag("inline-save-bar").fetchSemanticsNode().boundsInRoot.height
    assertTrue("height=$height", height <= with(compose.density) { 112.dp.toPx() })
}

private fun choosing(draft: OutputNameDraft) = TransferUiState.Choosing(
    share = ShareSummary("测试 报告.pdf", 37L, "application/pdf"),
    outputName = draft,
    cachedBytes = 37L,
    targetDirectory = RootPath.parse("/target").getOrThrow(),
    canSave = true,
)
```

- [ ] **Step 2: Run the new test and verify RED**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.isaver.filemanager.ui.InlineSaveBarTest
```

Expected: compilation fails because `InlineSaveBar` does not exist.

- [ ] **Step 3: Implement `InlineSaveBar` with a fixed compact layout**

Implement these public inputs:

```kotlin
@Composable
fun InlineSaveBar(
    state: TransferUiState,
    itemCount: Int,
    onStemChange: (String) -> Unit,
    onExtensionChange: (String) -> Unit,
    onRetryTransfer: () -> Unit = {},
    onAcknowledgeUncertain: () -> Unit = {},
    onContinueQueued: () -> Unit = {},
    modifier: Modifier = Modifier,
)
```

Use `Modifier.fillMaxWidth().height(112.dp).background(ISaverCard)`; render a centered count, a 48dp row containing a 32×42dp thumbnail, `BasicTextField` stem, literal dot, and an 88dp extension field, then a one-line status/action row. Both fields must use `ISaverPrimaryText`, `SolidColor(ISaverBlue)`, `ISaverBackground`, and semantic descriptions `文件名` / `扩展名`. Reuse the existing `TransferUiState` mapping from the old picker, but shorten action labels to fit one row.

- [ ] **Step 4: Run the new test and verify GREEN**

Run the Task 2 command again. Expected: both `InlineSaveBarTest` cases pass.

- [ ] **Step 5: Commit the save-bar slice**

```powershell
git add app/src/main/java/com/isaver/filemanager/ui/InlineSaveBar.kt app/src/androidTest/java/com/isaver/filemanager/ui/InlineSaveBarTest.kt
git commit -m "feat: add compact inline save bar"
```

### Task 3: Replace Overflow with Save Across Existing Screens

**Files:**
- Modify: `app/src/androidTest/java/com/isaver/filemanager/ui/files/FilesComponentsTest.kt`
- Modify: `app/src/androidTest/java/com/isaver/filemanager/ui/LocationHomeScreenTest.kt`
- Modify: `app/src/androidTest/java/com/isaver/filemanager/ui/BrowserScreenTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/files/FilesComponents.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/LocationHomeScreen.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/BrowserScreen.kt`

- [ ] **Step 1: Write RED tests for mutually exclusive top actions**

Add `FilesComponentsTest` coverage using the desired model:

```kotlin
compose.setContent {
    FilesTopBar(
        title = "视图",
        onOverflow = {},
        saveAction = FilesSaveAction(enabled = true, onSave = { saved = true }),
    )
}
compose.onNodeWithTag("files-top-bar-save").assertIsEnabled().performClick()
compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
assertTrue(saved)
```

Add the Views disabled-action test:

```kotlin
compose.setContent {
    LocationHomeScreen(
        state = LocationHomeUiState(loading = false),
        displayMode = DisplayMode.LIST,
        onOpenLocation = { _, _ -> },
        onAdd = { _, _ -> },
        onEdit = { _, _, _ -> },
        onRemove = {},
        onRetry = {},
        saveAction = FilesSaveAction(enabled = false, onSave = {}),
    )
}
compose.onNodeWithTag("files-top-bar-save").assertIsNotEnabled()
compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
```

Add the Browser enabled-action test:

```kotlin
var saved = false
compose.setContent {
    BrowserScreen(
        state = state(title = "Download"),
        onEnterDirectory = {},
        onBack = {},
        onRetry = {},
        onLoadMore = {},
        saveAction = FilesSaveAction(enabled = true, onSave = { saved = true }),
    )
}
compose.onNodeWithTag("files-top-bar-save").assertIsEnabled().performClick()
compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
assertTrue(saved)
```

- [ ] **Step 2: Run the three test classes and verify RED**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.isaver.filemanager.ui.files.FilesComponentsTest,com.isaver.filemanager.ui.LocationHomeScreenTest,com.isaver.filemanager.ui.BrowserScreenTest
```

Expected: compilation fails because `FilesSaveAction` and screen parameters do not exist.

- [ ] **Step 3: Implement the shared top-bar save action**

Add:

```kotlin
data class FilesSaveAction(
    val enabled: Boolean,
    val onSave: () -> Unit,
)
```

Thread `saveAction: FilesSaveAction? = null` through `FilesPageHeader` and `FilesTopBar`. In the right 48dp slot render exactly one branch:

```kotlin
if (saveAction == null) {
    HeaderAction(/* existing overflow */)
    overflowMenuContent()
} else {
    TextButton(
        onClick = saveAction.onSave,
        enabled = saveAction.enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.testTag("files-top-bar-save"),
    ) {
        Text(
            "存储",
            color = if (saveAction.enabled) ISaverBlue else ISaverSecondaryText,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
```

Add `saveAction` parameters to `LocationHomeScreen`, its private header, and `BrowserScreen`, then pass the value into `FilesPageHeader`.

- [ ] **Step 4: Make location text colors explicit**

Set all previously uncolored location section/group/empty/loading/error `Text` calls to `ISaverPrimaryText` or `ISaverSecondaryText`; leave interactive buttons on `ISaverBlue`. This is limited to `LocationHomeScreen.kt` and does not alter data or sorting.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the Task 3 command again. Expected: all three test classes pass, including pre-existing overflow menu tests in normal mode.

- [ ] **Step 6: Commit the shared header slice**

```powershell
git add app/src/main/java/com/isaver/filemanager/ui/files/FilesComponents.kt app/src/main/java/com/isaver/filemanager/ui/LocationHomeScreen.kt app/src/main/java/com/isaver/filemanager/ui/BrowserScreen.kt app/src/androidTest/java/com/isaver/filemanager/ui/files/FilesComponentsTest.kt app/src/androidTest/java/com/isaver/filemanager/ui/LocationHomeScreenTest.kt app/src/androidTest/java/com/isaver/filemanager/ui/BrowserScreenTest.kt
git commit -m "feat: replace overflow with save action"
```

### Task 4: Embed Save Mode in the Three-Tab Home

**Files:**
- Modify: `app/src/androidTest/java/com/isaver/filemanager/ui/ISaverHomeScreenTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/ISaverHomeScreen.kt`

- [ ] **Step 1: Write RED home integration tests**

Add a choosing-state test that renders the Views destination with a custom location and asserts all of the following:

```kotlin
compose.onNodeWithText("最近项目").assertIsDisplayed()
compose.onNode(hasText("视图") and hasClickAction()).assertIsSelected()
compose.onNodeWithText("浏览").assertIsDisplayed()
compose.onNodeWithContentDescription("列表项：工作资料").assertIsDisplayed()
compose.onNodeWithTag("inline-save-bar").assertIsDisplayed()
compose.onNodeWithText("测试 报告").assertIsDisplayed()
compose.onNodeWithText("pdf").assertIsDisplayed()
compose.onNodeWithTag("files-top-bar-save").assertIsNotEnabled()
compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
```

Add a browser-destination test where `TransferUiState.Choosing(canSave = true)` makes the top save button enabled, then verify adjacency:

```kotlin
compose.onNodeWithTag("files-top-bar-save").assertIsEnabled()
val saveBar = compose.onNodeWithTag("inline-save-bar").fetchSemanticsNode().boundsInRoot
val tabs = compose.onNodeWithTag("files-bottom-bar").fetchSemanticsNode().boundsInRoot
assertEquals(saveBar.bottom, tabs.top, 1f)
```

- [ ] **Step 2: Run the home test and verify RED**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.isaver.filemanager.ui.ISaverHomeScreenTest
```

Expected: compilation fails because save-mode inputs and `InlineSaveBar` integration are missing.

- [ ] **Step 3: Add save-mode inputs and layout**

Extend `ISaverHomeScreen` with:

```kotlin
transferState: TransferUiState = TransferUiState.Idle,
onSave: () -> Unit = {},
onStemChange: (String) -> Unit = {},
onExtensionChange: (String) -> Unit = {},
onRetryTransfer: () -> Unit = {},
onAcknowledgeUncertain: () -> Unit = {},
onContinueQueued: () -> Unit = {},
```

Derive:

```kotlin
val saveMode = transferState != TransferUiState.Idle
val saveAction = if (saveMode) {
    FilesSaveAction(
        enabled = (transferState as? TransferUiState.Choosing)?.canSave == true &&
            homeState.destination is HomeDestination.Browser,
        onSave = onSave,
    )
} else null
```

Pass it into the Views and Browser screens. After the weighted content and before `FilesBottomBar`, render `InlineSaveBar` with the current browser total count for a browser destination and the flattened visible location count for Views. Add tags `inline-save-bar` and `files-bottom-bar` for adjacency assertions.

- [ ] **Step 4: Run the home test and verify GREEN**

Run the Task 4 command again. Expected: all home tests pass and normal mode still shows overflow without the save bar.

- [ ] **Step 5: Commit the home integration slice**

```powershell
git add app/src/main/java/com/isaver/filemanager/ui/ISaverHomeScreen.kt app/src/androidTest/java/com/isaver/filemanager/ui/ISaverHomeScreenTest.kt
git commit -m "feat: embed save mode in three-tab home"
```

### Task 5: Route Activity Sharing into the Home

**Files:**
- Modify: `app/src/androidTest/java/com/isaver/filemanager/MainActivitySmokeTest.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/MainActivity.kt`
- Delete: `app/src/main/java/com/isaver/filemanager/ui/ShareSavePickerScreen.kt`
- Delete: `app/src/androidTest/java/com/isaver/filemanager/ui/ShareSavePickerScreenTest.kt`

- [ ] **Step 1: Write the failing warm ACTION_VIEW integration test**

From the existing activity rule, start the same `singleTop` activity with the debug Provider:

```kotlin
val intent = Intent(Intent.ACTION_VIEW).apply {
    setClass(compose.activity, MainActivity::class.java)
    setDataAndType(
        Uri.parse("content://com.isaver.filemanager.debug-share/report.pdf"),
        "application/pdf",
    )
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
compose.activity.startActivity(intent)
compose.waitUntil(20_000) {
    compose.onAllNodes(hasText("测试 报告")).fetchSemanticsNodes().isNotEmpty()
}
compose.onNodeWithText("最近项目").assertIsDisplayed()
compose.onNodeWithText("浏览").assertIsDisplayed()
compose.onNodeWithText("应用位置").assertIsDisplayed()
compose.onNodeWithTag("inline-save-bar").assertIsDisplayed()
compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
```

- [ ] **Step 2: Run the activity test and verify RED**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.isaver.filemanager.MainActivitySmokeTest
```

Expected: the share request opens the standalone picker, so the three-tab assertions fail.

- [ ] **Step 3: Remove standalone routing and forced root navigation**

In `MainActivity`:

- Delete the `ShareSavePickerScreen` import and the entire `if (pickerActive) ... else ...` page replacement.
- Delete the `LaunchedEffect(pickerActive)` that calls `browserViewModel.openRoot("/")`.
- When a transfer first becomes active, call `homeViewModel.selectTab(HomeTab.VIEWS)` once per active request key.
- Synchronize targets with navigation: call `transferViewModel.selectTarget(browserState.currentPath)` only while the destination is `HomeDestination.Browser`; call `transferViewModel.clearTarget()` for `HomeDestination.Tab`.
- Always render `ISaverHomeScreen` and pass all transfer callbacks.
- Preserve the existing Success/queued finish behavior and Root-gate cleanup.
- Keep Back handling on real browser destinations; at a tab root, Back cancels the share only after existing uncertainty/publish rules allow it.

- [ ] **Step 4: Delete the obsolete standalone surface**

Delete `ShareSavePickerScreen.kt` and `ShareSavePickerScreenTest.kt`; confirm `rg -n "ShareSavePickerScreen|share-picker" app/src` returns no matches.

- [ ] **Step 5: Run activity and transfer regression tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.isaver.filemanager.transfer.TransferViewModelTest"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.isaver.filemanager.MainActivitySmokeTest,com.isaver.filemanager.ui.ISaverHomeScreenTest,com.isaver.filemanager.ui.InlineSaveBarTest
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit the routing slice**

```powershell
git add app/src/main/java/com/isaver/filemanager/MainActivity.kt app/src/androidTest/java/com/isaver/filemanager/MainActivitySmokeTest.kt app/src/main/java/com/isaver/filemanager/ui/ShareSavePickerScreen.kt app/src/androidTest/java/com/isaver/filemanager/ui/ShareSavePickerScreenTest.kt
git commit -m "fix: keep locations available while saving"
```

### Task 6: Disable MIUI Force Dark and Synchronize Documentation

**Files:**
- Modify: `app/src/androidTest/java/com/isaver/filemanager/MainActivitySmokeTest.kt`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `E:/PROJECT/Android_files/项目文档/iSaver_PRD_需求说明书.md`
- Modify: `E:/PROJECT/Android_files/项目文档/iSaver_SDD_系统设计文档.md`
- Modify: `docs/superpowers/plans/2026-07-13-ios-share-save-picker.md`

- [ ] **Step 1: Write the failing theme attribute test**

Add:

```kotlin
@Test
fun activityThemeDisablesPlatformForceDark() {
    val attributes = compose.activity.theme.obtainStyledAttributes(
        intArrayOf(android.R.attr.forceDarkAllowed),
    )
    try {
        assertFalse(attributes.getBoolean(0, true))
    } finally {
        attributes.recycle()
    }
}
```

- [ ] **Step 2: Run the theme test and verify RED**

Run the Task 5 activity-test command. Expected: `forceDarkAllowed` resolves to true/default and the new assertion fails.

- [ ] **Step 3: Disable Force Dark**

Add inside `Theme.ISaver`:

```xml
<item name="android:forceDarkAllowed">false</item>
```

Keep the existing light parent and `windowLightStatusBar=true`.

- [ ] **Step 4: Update PRD and SDD to version 3.3**

Set the date to `2026-07-15`; replace all normative statements saying the picker is standalone or hides three tabs with the approved inline behavior. Document the 112dp maximum save bar, mutually exclusive save/overflow action, target clearing on logical pages, and Force Dark opt-out. Update acceptance criteria to require Views/custom locations and visible default name fields during saving.

Update the old 2026-07-13 implementation plan status with a note that its standalone UI was superseded by `2026-07-15-inline-save-mode-design.md`; retain it as historical evidence rather than silently rewriting old decisions.

- [ ] **Step 5: Run focused verification and checks**

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.isaver.filemanager.MainActivitySmokeTest
git diff --check
```

Expected: activity tests pass and repository diff has no whitespace errors.

- [ ] **Step 6: Commit repository documentation and theme**

The external PRD/SDD are not inside this Git repository; verify them separately. Commit repository files:

```powershell
git add app/src/main/res/values/themes.xml app/src/androidTest/java/com/isaver/filemanager/MainActivitySmokeTest.kt docs/superpowers/plans/2026-07-13-ios-share-save-picker.md
git commit -m "fix: keep isaver text visible in night mode"
```

### Task 7: Full Verification and Xiaomi 9 Acceptance

**Files:**
- Modify only if a verified failure requires returning to the owning task.

- [ ] **Step 1: Run repository verification**

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Expected: all commands exit 0 with no failed tests or lint errors.

- [ ] **Step 2: Run the complete connected instrumentation suite**

```powershell
adb devices -l
adb -s d51f42ac shell su -c id
.\gradlew.bat connectedDebugAndroidTest
```

Expected: device `d51f42ac` is authorized, Root returns `uid=0(root)`, and all instrumentation tests pass.

- [ ] **Step 3: Install and create an isolated target**

```powershell
adb -s d51f42ac install -r app\build\outputs\apk\debug\app-debug.apk
adb -s d51f42ac shell su -c "mkdir -p /data/local/tmp/isaver-inline-save-test"
```

Use the Views overflow “添加位置” flow to create a temporary custom location named `iSaver 测试` pointing to `/data/local/tmp/isaver-inline-save-test`; do not inspect or modify WeChat files.

- [ ] **Step 4: Exercise debug ACTION_VIEW and capture visual evidence**

```powershell
adb -s d51f42ac shell am force-stop com.isaver.filemanager
adb -s d51f42ac shell am start -W -a android.intent.action.VIEW -d content://com.isaver.filemanager.debug-share/report.pdf -t application/pdf --grant-read-uri-permission -n com.isaver.filemanager/.MainActivity
```

Verify in system night mode: Views and all three tabs remain visible; `iSaver 测试` can be opened; the top action is only “存储”; `测试 报告` and `pdf` are dark and visible; the bar is immediately above the tabs and materially shorter than the old footer. Save the fixture and verify with Root that exactly `测试 报告.pdf` exists in the isolated target.

- [ ] **Step 5: Clean test state and inspect logs**

Remove the temporary custom location through iSaver so only the logical entry is deleted, then:

```powershell
adb -s d51f42ac shell su -c "rm -rf /data/local/tmp/isaver-inline-save-test"
adb -s d51f42ac logcat -d -t 300 | Select-String "FATAL EXCEPTION|AndroidRuntime|com.isaver.filemanager"
```

Expected: isolated test directory is removed and there is no iSaver fatal exception. Do not add screenshots or logs to Git.

- [ ] **Step 6: Review and push the verified slice**

```powershell
git diff --check
git status --short --ignored
git log --oneline --decorate -10
git push origin develop/m1-root-browsing
```

Expected: only intended source, test, theme, and repository documentation files are tracked; ignored local/build/device artifacts remain uncommitted; push succeeds without force.
