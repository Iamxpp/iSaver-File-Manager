# iSaver P0 Release Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore deterministic archive and Activity instrumentation gates before implementing additional release features.

**Architecture:** Keep production semantics unchanged where the failure is caused by an invalid test fixture: archive extraction targets must be existing user-selected directories. Diagnose the Activity timeout from process, logcat, Root state, and Compose idling evidence before deciding whether the fix belongs in the test harness or production startup flow.

**Tech Stack:** Kotlin, JUnit4, AndroidX instrumentation, Jetpack Compose UI Test, libsu, ADB/SU on Xiaomi 9.

---

### Task 1: Correct the archive extraction device fixture

**Files:**
- Modify: `app/src/androidTest/java/com/isaver/filemanager/archive/ArchiveRootInstrumentedTest.kt`
- Verify: `app/src/main/java/com/isaver/filemanager/archive/ArchiveRepository.kt`

- [x] **Step 1: Preserve the existing RED evidence**

Run:

```powershell
adb -s d51f42ac shell am instrument -w -r `
  -e class com.isaver.filemanager.archive.ArchiveRootInstrumentedTest `
  com.isaver.filemanager.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL at ZIP extraction because `/data/local/tmp/isaver-archive-test/extracted` does not exist.

- [x] **Step 2: Create the selected extraction directory in the fixture**

Insert before `archiveRepository.extract(...)`:

```kotlin
root(app, "mkdir -p -- ${quote("$TARGET/extracted")}")
val extractTarget = path("$TARGET/extracted")
```

This matches the PRD contract that the user selects an existing target directory. Do not make `ArchiveRepository` create an arbitrary missing target path.

- [x] **Step 3: Run the focused GREEN test**

Rebuild/install the test APK and rerun the class. Expected: `OK (1 test)`, correct alpha/beta contents, no `.isaver-*` stage.

- [x] **Step 4: Run archive JVM tests**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.isaver.filemanager.archive.*"
```

Expected: all archive JVM tests pass.

### Task 2: Diagnose the MainActivity smoke timeout

**Files:**
- Inspect: `app/src/androidTest/java/com/isaver/filemanager/MainActivitySmokeTest.kt`
- Inspect: `app/src/main/java/com/isaver/filemanager/MainActivity.kt`
- Inspect: `app/src/main/java/com/isaver/filemanager/ui/RootGateViewModel.kt`
- Modify only the file proven responsible by diagnostics.

- [x] **Step 1: Reproduce from a clean process**

Force-stop app/test packages, clear only logcat, run `grantedRootStartsInViewsHome`, and capture bounded logcat containing AndroidJUnitRunner, Root gate, ANR and FATAL lines.

- [x] **Step 2: Check component boundaries**

Verify: test runner starts; Activity reaches `onCreate`; Root check starts/returns; Compose content is committed; location resolution starts; test reaches its 20-second wait. Determine the first missing boundary.

- [x] **Step 3: Form one hypothesis and add a regression test**

If Root startup blocks, add a ViewModel or Activity-level test proving the expected non-blocking state transition. If Compose idling waits on continuous work, isolate the exact Flow/job and add a test proving the UI reaches the Views shell without waiting for background location probing.

- [x] **Step 4: Implement the smallest proven fix**

Do not increase timeouts. Preserve Root-only behavior and do not bypass Root in production.

- [x] **Step 5: Verify both smoke methods**

Expected: `OK (2 tests)` within 30 seconds, followed by no FATAL/ANR in bounded logcat.

### Task 3: Establish the P0 verification command

**Files:**
- Create: `scripts/verify_release_gates.ps1`

- [x] **Step 1: Implement strict device and build checks**

The script must require serial `d51f42ac`, UID 0, run unit/Lint/build gates, Root-install APKs, run named instrumentation groups, invoke the existing performance fixture script, and clean only known test paths in `finally`.

- [x] **Step 2: Fail on instrumentation failure text**

ADB may return process exit code 0 when JUnit reports failures. The script must require `OK (` and reject `FAILURES!!!`, `INSTRUMENTATION_FAILED`, ANR or FATAL output.

- [x] **Step 3: Run the script end to end**

Expected: exit code 0, explicit test summaries, and no remaining APK/helper/test directories.
