# MIUI SELinux Cache Stream Bridge Design

## Status

- Date: 2026-07-16
- Decision: Approved approach A
- Scope: Replace direct Root access to iSaver's internal incoming cache with a one-shot ContentProvider stream bridge.

## Problem

On the Xiaomi 9, the Root shell has `uid=0` but runs under SELinux context `u:r:su:s0`. That context can browse Root directories yet cannot open files labeled as iSaver private app data. A real ACTION_VIEW save therefore reaches the publish boundary and fails when the native helper opens `/data/user/0/com.iamxpp.isaver/cache/incoming/<uuid>.tmp`.

The internal cache remains the required source of truth. The fix must not move plaintext to shared storage, weaken permissions, change ownership, accept arbitrary paths, or bypass the existing atomic publish protocol.

## Architecture

`IncomingFileCache` continues to create and validate UUID cache files in the app's internal cache. A new in-memory `IncomingStreamRegistry` issues a cryptographically random, one-shot token bound to one validated `CachedIncomingFile`, its device/inode identity, and exact byte count.

An exported, read-only `IncomingStreamProvider` uses manifest authority `${applicationId}.incoming-stream` and exposes only `content://${applicationId}.incoming-stream/incoming/<token>`. It rejects callers other than UID 0 or UID 2000 before looking up a token, rejects every unsupported operation and mode, atomically consumes the token, revalidates the cache file, and returns a read-only `ParcelFileDescriptor` for that single file. It never accepts or derives a filesystem path from the URI.

The Root data layer constructs a fixed pipeline: Android's `content read` opens the one-shot URI and writes bytes to stdout, while `isaver-fs-helper copy-publish-stdin` consumes stdin. `set -o pipefail` and fixed absolute executables make failure in either side observable. The URI and token are safely quoted as one shell argument.

## Access Rules

- The provider is exported only because Root/Shell must cross the process boundary.
- `Binder.getCallingUid()` must be exactly root or shell.
- Tokens contain 256 bits from `SecureRandom`, use exactly 64 lowercase hexadecimal characters, live only in memory, and expire 60 seconds after issue using monotonic elapsed time.
- Opening consumes a token before returning the descriptor; replay fails.
- Only mode `r` is accepted.
- `query`, `insert`, `update`, `delete`, `getType`, canonicalization, prefix grants, arbitrary paths, directory access, and enumeration expose no cache capability.
- The provider validates UUID filename, canonical parent, regular-file type, device/inode, and exact size immediately before returning the descriptor.
- Cancellation, expiry, publish completion, and ViewModel cleanup revoke any unconsumed token.
- Tokens, URIs, cache paths, file names, and command text are never logged.

## Native Protocol

`copy-publish-stdin` retains all existing parent and stage identity arguments but replaces source path/device/inode arguments with an exact expected byte count.

The helper:

1. Reopens and validates original/canonical parent descriptors.
2. Reopens the Root-owned `0700` stage by device/inode.
3. Creates the `0600` payload with `O_EXCL | O_NOFOLLOW`.
4. Reads exactly the declared number of bytes from stdin.
5. Fails if stdin ends early or supplies any extra byte.
6. Writes, fsyncs, and validates the payload size.
7. Publishes using `renameat2(RENAME_NOREPLACE)`.
8. Removes only the verified payload/stage on a definite failure.
9. Preserves the existing uncertain-outcome behavior after the publish boundary.

No generic stdin-to-path command is introduced. The subcommand accepts only validated parent identity, stage identity, final basename, and size.

## Kotlin Boundaries

- `IncomingStreamRegistry`: owns token creation, atomic consumption, expiry, revalidation callback, and revocation.
- `IncomingStreamProvider`: Android Binder adapter only; delegates capability decisions to the registry.
- `RootTransferSource`: typed source capability containing the one-shot content URI and expected size, replacing `AppCachePath` at the Root publish boundary.
- `RootFileTransferRepository`: requests a capability immediately before each publish attempt and always revokes it in `finally` when not consumed.
- `LibsuRootFileSystem`: accepts the typed stream source and emits only the fixed pipe command.
- `TransferViewModel`: retains current retry, queue, cancellation, cache ownership, and uncertain-result behavior.

An `ALREADY_EXISTS` result consumes the previous token. A new token is issued only if policy permits another candidate attempt. No capability can be replayed automatically after an unknown result.

## Failure Mapping

- Invalid, expired, replayed, or caller-rejected token: `SOURCE_UNREADABLE` before publication.
- Cache identity or size change: `SOURCE_UNREADABLE`.
- Provider/content process failure before helper receives all bytes: definite source failure and exact stage cleanup.
- Short or extra stdin: `SOURCE_UNREADABLE` and exact stage cleanup.
- Parent/stage identity change: existing structured parent/stage error.
- Collision: `ALREADY_EXISTS`, with a fresh token required for the next candidate.
- Timeout or lost result after helper dispatch: existing reconciliation and `OUTCOME_UNCERTAIN` rules.

## Tests

### JVM

- Token entropy format, single consumption, expiry, explicit revocation, and concurrent open race.
- Caller UID/mode/path rejection without revealing whether a token exists.
- Cache identity and size revalidation.
- Fixed command encoding for hostile names and URIs.
- Collision creates a fresh token; failure, cancellation, and uncertain results do not replay a consumed token.
- Native protocol parser and exit-code mapping.

### Native Host and Device

- Exact bytes publish successfully.
- Empty, short, extra, interrupted, and oversized streams fail without final files or orphan stages.
- Existing target remains unchanged and returns `ALREADY_EXISTS`.
- Spaces, Chinese, quotes, newlines, shell metacharacters, and multipart extensions preserve the final basename.
- Parent/stage replacement and symlink attacks are rejected.

### Xiaomi 9 Acceptance

- Demonstrate direct `su` access to internal cache remains denied.
- Demonstrate the one-shot provider can be read by Root and cannot be replayed.
- ACTION_VIEW shows the inline save UI with visible default stem/extension.
- Saving into an isolated test directory creates exactly the expected file with matching size/content.
- Retry/collision produces the expected non-overwriting name.
- Cache capability and temporary stage are cleaned; no fatal exception appears in relevant logcat.

## Documentation Impact

PRD/SDD 3.3 must replace the assumption that Root directly reads app-private cache. They must document the one-shot provider stream bridge while retaining the internal cache, typed Root operations, atomic publish, no chmod/chown, and no shared plaintext staging requirements.
