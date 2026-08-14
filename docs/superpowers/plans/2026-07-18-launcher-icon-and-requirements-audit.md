# iSaver Launcher Icon and Requirements Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the default Android launcher appearance with the approved iSaver blue-folder adaptive icon, verify it on the Xiaomi 9, and produce an evidence-based audit of every current PRD/SDD milestone requirement.

**Architecture:** Keep the icon entirely in Android resources: one adaptive icon combines a cold-white background with a vector foreground derived from the existing Compose `FolderGlyph`, while the manifest explicitly declares normal and round launch icons. Audit requirements independently from the icon change by tracing each PRD section and milestone to production code, automated tests, and device evidence, classifying gaps without changing unrelated behavior.

**Tech Stack:** Android XML resources, Kotlin instrumentation tests, Jetpack Compose project tooling, Gradle, Android Lint, ADB/SU on Xiaomi 9, Markdown traceability report.

---

### Task 1: Lock the launcher icon contract

**Files:**
- Create: `app/src/androidTest/java/com/isaver/filemanager/LauncherIconInstrumentedTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

- [ ] **Step 1: Write the failing resource test**

Add an instrumentation test that loads `ApplicationInfo`, requires non-zero normal and round icon resource IDs, asserts their resource names are `mipmap/ic_launcher` and `mipmap/ic_launcher_round`, and confirms both drawables resolve.

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb -s d51f42ac push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/isaver-debug.apk
adb -s d51f42ac shell su -c "pm install -r /data/local/tmp/isaver-debug.apk"
adb -s d51f42ac push app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk /data/local/tmp/isaver-debug-test.apk
adb -s d51f42ac shell su -c "pm install -r /data/local/tmp/isaver-debug-test.apk"
adb -s d51f42ac shell am instrument -w -r -e class com.isaver.filemanager.LauncherIconInstrumentedTest com.isaver.filemanager.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL because the application currently declares neither `android:icon` nor `android:roundIcon`.

- [ ] **Step 3: Add the minimal icon resources**

Create a cold-white-to-pale-blue background and a centered two-layer blue folder foreground whose geometry and alpha match `FolderGlyph`. Use adaptive icon XML for API 26+ and XML fallback resources for the project's API 29 minimum; do not introduce raster duplication or third-party assets.

- [ ] **Step 4: Declare both launcher icon variants**

Add `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"` to `<application>` without changing permissions or intent filters.

- [ ] **Step 5: Run the focused test to verify GREEN**

Rebuild, Root-install both APKs, rerun `LauncherIconInstrumentedTest`, and require `OK (1 test)`.

### Task 2: Verify the icon visually on Xiaomi 9

**Files:**
- Create locally only: `captures/launcher-icon-2026-07-18.png` (ignored)

- [ ] **Step 1: Confirm the target device**

Run `adb devices -l`, query model/API, and require `adb -s d51f42ac shell su -c id` to return UID 0.

- [ ] **Step 2: Install the current Debug APK**

Use the Root `pm install -r` flow if MIUI blocks ordinary ADB installation, then remove staged APKs from `/data/local/tmp`.

- [ ] **Step 3: Inspect package icon declarations**

Use `dumpsys package com.isaver.filemanager` and the instrumentation contract to verify the installed package resolves both resources.

- [ ] **Step 4: Capture and inspect the launcher**

Return to the MIUI launcher, capture a screenshot, and visually verify the icon uses the selected light background with the blue folder, remains centered, and is not clipped by the MIUI mask.

### Task 3: Build the requirements traceability audit

**Files:**
- Create: `docs/audits/2026-07-18-project-completion-audit.md`

- [ ] **Step 1: Inventory production and test evidence**

List all source/test files, Gradle dependencies, manifest entries, Room schema, native helper commands, and recent milestone commits. Search for the concrete symbols corresponding to PRD sections 5.1 through 5.10 and SDD milestones M0 through M6.

- [ ] **Step 2: Classify every requirement**

For each functional section and milestone, record `已实现`, `部分实现`, `未实现`, or `待外部验收`, with direct file/symbol/test evidence. Distinguish implementation presence from automated coverage and from Xiaomi 9 end-to-end acceptance.

- [ ] **Step 3: Identify contradictions and missing acceptance evidence**

Compare code against the PRD/SDD current-state notes, especially archive cancellation/progress, remote upload/download UI, remote rename/delete/reconnect, recent-project coverage, Android version matrix, and open-source preparation. Do not mark a feature complete merely because an interface or adapter exists.

- [ ] **Step 4: Write prioritized next work**

Summarize blockers in priority order: incomplete user workflows first, missing safety behavior second, external-server/device compatibility evidence third, and release documentation last.

### Task 4: Run final verification and publish the slice

**Files:**
- Modify only files listed above unless verification exposes an icon-specific defect.

- [ ] **Step 1: Run fresh automated gates**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

- [ ] **Step 2: Run focused device instrumentation**

Root-install the freshly built APK/test APK and rerun `LauncherIconInstrumentedTest`; retain the exact test count and exit status.

- [ ] **Step 3: Review repository safety**

Run `git diff --check`, `git status --short --ignored`, inspect every staged path, and verify that captures, APKs, logs, `local.properties`, credentials, and device data remain ignored/uncommitted.

- [ ] **Step 4: Commit and push**

Commit the icon resources, resource test, and audit as one verified slice with a focused `feat:` message, then push `develop/m1-root-browsing` without rewriting history.
