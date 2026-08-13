# iSaver

[English](README.md) | [中文](README.zh-CN.md)

iSaver is a Root-only Android file manager for users who need direct access to the real Android filesystem. It combines common storage locations, a Root browser, recent items, and a safe share/open save flow in one app.

Current public build: `0.1.2`.

## Features

- Root permission gate with explicit retry and exit actions.
- Recent, locations, and Root browser tabs.
- Built-in common Android storage locations plus virtual folders for organizing real file and directory references.
- List/grid presentation, search, stable sorting, and folder creation.
- Single-file `ACTION_SEND` and `content://` `ACTION_VIEW` save flow.
- Inline save bar with long filename editing and retry after failed saves.
- Root stream publication with identity-bound staging and no-overwrite finalization.
- ZIP creation and safe ZIP, TAR, TAR.GZ, 7Z, and RAR browsing/extraction.

Remote SFTP, FTPS, and FTP code is still experimental and is not exposed as a supported public feature in this release.

## Product Documents

- [Product requirements (Chinese)](docs/product/iSaver_PRD_需求说明书.md)
- [System design (Chinese)](docs/product/iSaver_SDD_系统设计文档.md)
- [Complete modern Root file manager specification (Chinese)](docs/product/iSaver_现代Root文件管理器完整产品与技术规格.md)
- [M7.1 virtual-view unification and acceptance (Chinese)](docs/product/iSaver_M7.1_虚拟视图位置统一改造需求与技术落地.md)

## Requirements

- Android 10 or later.
- A working Root solution that provides `su`.
- Root authorization granted to iSaver.

iSaver does not provide Root access and does not include a SAF, Shizuku, or non-Root fallback.

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

Root and integration tests use dedicated paths under `/data/local/tmp/isaver-test`. Never use real application data as disposable test fixtures.

## Security

iSaver executes narrowly typed Root operations and does not expose a generic shell executor to UI or ViewModel code. File publication uses private incoming cache files, identity-bound staging, and no-overwrite finalization.

Read [SECURITY.md](SECURITY.md) before reporting a vulnerability. Do not attach private paths, files, databases, ADB keys, signing keys, or full device logs to a public issue.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Every behavior change should start with a failing test and pass the relevant Gradle and device gates before merge.
