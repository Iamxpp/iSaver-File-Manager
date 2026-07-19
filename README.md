# iSaver

iSaver is a Root-only Android file manager inspired by the locations model in iOS Files. It groups common Android and application storage paths into readable entries while preserving access to the real Root filesystem.

## Release Scope

Version 0.1.0 includes:

- Root permission gate with explicit retry and exit actions.
- Recent, locations, and Root browser tabs.
- Built-in WeChat path candidates and custom absolute locations.
- List/grid presentation, search, stable sorting, and folder creation.
- Single-file `ACTION_SEND` and `content://` `ACTION_VIEW` save flow.
- ZIP creation and safe ZIP, TAR, TAR.GZ, 7Z, and RAR browsing/extraction.

Remote SFTP, FTPS, and FTP support is not part of 0.1.0. The incomplete remote implementation remains disabled and has no user-visible entry.

## Requirements

- Android 10 or later.
- A working Root solution that provides `su`.
- Root authorization granted to iSaver.

iSaver does not provide Root access and does not include a SAF, Shizuku, or non-Root fallback.

## Build

The project requires JDK 21 and these Android SDK packages:

- Android SDK Platform 35
- Android SDK Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1

Run the local gates from PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Device Verification

The primary Root test device is a Xiaomi 9 running Android 11/API 30. API 29, 33, and 35 have non-Root compatibility coverage. See [the compatibility matrix](docs/testing/android-compatibility-matrix.md) for the exact scope.

Root and integration tests use dedicated paths under `/data/local/tmp/isaver-test`. Never use real application data as disposable test fixtures.

## Security

iSaver executes narrowly typed Root operations and does not expose a generic shell executor to UI or ViewModel code. File publication uses private incoming cache files, identity-bound staging, and no-overwrite finalization.

Read [SECURITY.md](SECURITY.md) before reporting a vulnerability. Do not attach private paths, files, databases, ADB keys, signing keys, or full device logs to a public issue.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Every behavior change must start with a failing test and pass the relevant Gradle and device gates before merge.
