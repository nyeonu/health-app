# Fixed CI signing correction

## Scope and user intent

The user asked how to retain an APK certificate and reported phone-only access to GitHub Actions Repository Secrets. This bounded build-configuration correction implements reuse of an owner-generated PKCS12 signing key, a private Codespaces creation guide, and distribution safeguards. No actual owner key or secret was generated, retrieved or registered by the agent. App behavior, OAuth scopes and production dependencies are unchanged.

## Contract

- The owner generates exactly one PKCS12 key with alias `healthapp` outside the checkout and backs up the encrypted p12 and password privately. The helper refuses to overwrite existing material and does not print key bytes or passwords.
- Two Actions Repository secrets: `ANDROID_SIGNING_KEYSTORE_BASE64` and `ANDROID_SIGNING_PASSWORD`. Base64 is secret material, not encryption. The second secret is used for both PKCS12 store/key password.
- Gradle accepts the fixed keystore path/password via environment only, with explicit rejection of partial configuration or missing files. Local unsigned-for-distribution verification retains normal debug behavior when neither is provided.
- PR verification has no signing-secret environment and produces no downloadable APK. Only curated branch push builds, or manual runs on those branches, can restore the private key and distribute a fixed-key APK. Automatic triggers are limited to master and feat/p0-android-poc.
- No secrets means checks pass with APK publication skipped. Partial/invalid secrets or failed signing stop APK publication; there is no random-key distribution fallback. The key is restored under RUNNER_TEMP with umask 077, is excluded from explicit artifact paths, and is removed in an always-run cleanup step. Signing disables Gradle configuration cache. No signing key is stored in repository files or committed.
- Distribution includes only APK and a public certificate SHA-1 report. Ordinary test/lint reports remain available. The first transition requires matching OAuth registration and reinstall/re-consent; later configured builds reuse the same certificate. Stable signing is for these CI download artifacts, not every unconfigured local build or a Play production-release setup.
- This workflow is absent from the current default branch. Until merged, the user must rerun the latest updated **push** run after Secret registration, rather than relying on the Run workflow button or rerunning historical/PR runs.

## Validation

- YAML parsed and every shell run block plus creation helper passed `bash -n`.
- Preparation-step scenarios executed from the actual workflow block with isolated synthetic inputs: absent secrets -> enabled=false; partial secrets -> failure; invalid Base64 -> failure; valid synthetic key -> enabled=true and restored file mode 0600. No password appears in captured output or summary.
- Temporary synthetic PKCS12 key built the APK twice, including a forced repackaging. `apksigner verify --print-certs` verified that both APK certificates matched the key's exported certificate. The temporary key was deleted automatically. A further transition check verifies that an existing default-signed APK is replaced by fixed signing without requiring its deletion first.
- Partial Gradle configuration fails before signing without exposing the synthetic password.
- Creation-helper overwrite guard executed against an isolated marker file: failed safely and preserved its bytes.
- Repository tasks `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed (33 tests: 27 core + 6 app; zero failures/errors/skips; lint 0 errors/11 existing warnings). Initial configured full build: 42s. Follow-up checks use assemble only for signing transitions; they do not add unit-test counts.
- `git diff --check` passed. Self-review applied testing-policy and security-and-hardening. Secret sources are restricted to guarded signing steps; diagnostic artifacts are explicitly allowlisted. No dependency graph change or new dependency audit. This is self-review, not independent conformance verification.

The local synthetic checks do not prove real Repository Secrets permissions, Codespaces mobile usability, backup completion, a hosted owner-signed artifact or OAuth registration. Those remain user-controlled pending steps. The agent never outputs or receives the owner's secret values. P0 file-content/privacy/same-day-update/ChatGPT validation remains separate; PR 1 remains draft.

## Device report recorded separately

The user reported a successful sync with two confirmed files after the root metadata correction. Five granted/allowed categories are not proof of five populated datasets. This is application-screen evidence; remote contents, independent privacy inspection and idempotent repeat remain pending.

## Official sources

- https://developer.android.com/studio/publish/app-signing
- https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets
- https://docs.github.com/en/codespaces/developing-in-a-codespace/creating-a-codespace-for-a-repository
