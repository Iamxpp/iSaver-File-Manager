# Contributing

## Development Rules

- Keep the first release Root-only. Do not add SAF, Shizuku, or a non-Root fallback without an approved product change.
- Keep Root calls behind typed `RootSession` and `RootFileSystem` interfaces.
- Never concatenate user paths into shell commands or expose arbitrary commands to UI/domain code.
- Do not add local delete, move, overwrite, recursive chmod, or recursive chown operations to the initial release.
- Use `/data/local/tmp/isaver-test` for Root fixtures and never modify real WeChat or other application data.

## Workflow

1. Add a failing regression or behavior test.
2. Confirm the expected failure.
3. Implement the smallest safe change.
4. Run focused tests, then the relevant suite.
5. Run Lint, build the APK, and verify affected Root/UI workflows on the Xiaomi 9 when applicable.
6. Review `git diff --check` and the complete staged file list.

## Sensitive Files

Never commit APKs, build output, `.artifacts/`, `local.properties`, keystores, signing configuration, credentials, ADB keys, device databases, screenshots containing private data, or full logcat dumps.
