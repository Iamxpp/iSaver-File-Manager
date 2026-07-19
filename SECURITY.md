# Security Policy

## Supported Version

Security fixes currently target the latest 0.1.x code on `main`.

## Reporting

Use GitHub's private vulnerability reporting or send a private report to the repository owner. Do not open a public issue for an unpatched vulnerability.

Include the affected version, Android version, Root environment, reproduction steps, expected impact, and the smallest redacted logs needed to reproduce the problem. Do not include real user files, complete private paths, credentials, tokens, ADB keys, signing keys, or device backups.

## Root Boundary

iSaver assumes the device owner intentionally grants Root. Its security boundary prevents accidental command injection, path confusion, overwrite, and unsafe cleanup by iSaver; it cannot defend against another malicious process that already has unrestricted Root access.

Version 0.1.0 does not expose remote server functionality.
