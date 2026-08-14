# iSaver

[English](README.md) | [中文](README.zh-CN.md)

iSaver is an Android file manager for users who need direct access to the real Android filesystem. Root mode enables the complete file-management toolset, while non-Root mode provides read-only access to directories the app can open.

Current public build: `0.1.3`.

## Features

- Root permission gate with explicit retry and exit actions.
- Non-Root read-only browsing for directories available to the app.
- Recent, locations, and Root browser tabs.
- Built-in common Android storage locations plus virtual folders for organizing real file and directory references.
- List/grid presentation, stable natural sorting, deep search, history, and persistent tasks.
- Portrait/landscape dual-pane browsing with independent history and cross-pane copy/move.
- Default open/open-with, private read-only sharing, text/image preview, text editing, Hex view, comparison, and checksums.
- Typed Root copy, move, rename, create, recoverable replace, trash/restore, and non-recursive permission editing.
- ZIP, TAR, TAR.GZ, and 7Z creation plus safe ZIP, TAR, TAR.GZ, 7Z, and RAR browsing/extraction.
- Application-layer virtual-view folders for organizing real file and directory references.

Remote SFTP, FTPS, and FTP code is still experimental and is not exposed as a supported public feature in this release.

## Product Documents

- [Product requirements (Chinese)](docs/product/iSaver_PRD_需求说明书.md)
- [System design (Chinese)](docs/product/iSaver_SDD_系统设计文档.md)
- [Complete modern Root file manager specification (Chinese)](docs/product/iSaver_现代Root文件管理器完整产品与技术规格.md)
- [M7.1 virtual-view unification and acceptance (Chinese)](docs/product/iSaver_M7.1_虚拟视图位置统一改造需求与技术落地.md)

## Requirements

- Android 10 or later.
- A working Root solution that provides `su` is required for write operations and protected paths.
- Root authorization granted to iSaver is recommended for the complete feature set.

iSaver does not provide Root access and does not include SAF or Shizuku integration. Non-Root mode is intentionally read-only and remains subject to Android application sandbox permissions.

## Install

Installable APKs are published from GitHub Releases:

- [Latest release](https://github.com/Iamxpp/iSaver-File-Manager/releases/latest)

Until private release signing is configured, release assets are debug-signed development builds and are named `iSaver-<tag>-debug.apk`.

## Build

The project requires JDK 21 and these Android SDK packages:

- Android SDK Platform 35
- Android SDK Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1

Run the local gates from PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Device Verification

Root save/share behavior should be verified on a rooted test device:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify_device_root_transfer.ps1 -Serial d51f42ac -AutoGrantKernelSU
```

The expected result is:

```text
RootStreamTransferInstrumentedTest: OK
PASS: device root transfer verification completed
```

The primary Root test device is a Xiaomi 9 running Android 11/API 30. Xiaomi 17 issues are validated through user-provided logs when the device is not available over ADB. API 29, 33, and 35 have non-Root compatibility coverage. See [the compatibility matrix](docs/testing/android-compatibility-matrix.md) for the exact scope.

Root and integration tests use dedicated paths under `/data/local/tmp` and isolated iSaver folders in shared storage. Never use real application data as disposable test fixtures.

## Security

iSaver executes narrowly typed Root operations and does not expose a generic shell executor to UI or ViewModel code. File publication uses private incoming cache files, identity-bound staging, and no-overwrite finalization.

Read [SECURITY.md](SECURITY.md) before reporting a vulnerability. Do not attach private paths, files, databases, ADB keys, signing keys, or full device logs to a public issue.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Every behavior change should start with a failing test and pass the relevant Gradle and device gates before merge.
