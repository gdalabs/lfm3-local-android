# Preview validation

Version **0.1.1-lfm3-preview** uses the refreshed Chat, Models and Settings UI with local CommonMark rendering. Package IDs and prior preview signing identities are preserved for updates. The release APK is non-debuggable but uses Android's development signing identity; this is not a production-signed release.

- ARM64 release and matching debug instrumentation APKs compiled.
- JVM: **39 tests**, zero failures, errors or skips, including Markdown, reasoning presentation and backwards-compatible history decoding.
- Lint release: **0 errors, 1 warnings**.
- Packaged checks: no Internet permission, ARM64-only native libraries, 16 KiB ELF/ZIP alignment, pinned model size/SHA-256 and source-matching license assets.
- App, instrumentation and previous release signing certificates match.
- Four English UI previews render the actual Compose source. These are fixed-content layout previews, not physical-device captures or inference evidence. See [renderer](tools/ui-preview).

The inference runtime source is unchanged in this UI update. Earlier host validation is marked inherited in [VALIDATION.json](VALIDATION.json); it was not repeated as a new phone test.

Actual execution of this version on an Android phone, device performance, thermal/battery measurements and production signing remain pending. No universal refusal-removal or accuracy guarantee is claimed.

Exact artifact sizes and SHA-256 values are in [VALIDATION.json](VALIDATION.json) and release SHA256SUMS.
