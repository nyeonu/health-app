# ADR-001: Isolate P0 logic and use private Drive file authorization

## Status
Accepted

## Date
2026-10-31

## Ticket
P0

## Context
The approved P0 requires a Kotlin/Compose app, real foreground Health Connect reads, deterministic CSV, and least-privilege private Drive upload. The repository initially has no Android implementation; the execution environment has JDK 21 but no Android SDK or configured Google account.

## Decision
Use an Android app module with a pure Kotlin JVM core for normalization, CSV, sync and Drive protocol decisions. Use the supported stable AGP 8.13.2/Gradle 8.13/Kotlin 2.2.20 baseline, Health Connect 1.1.0 and Google Identity Services AuthorizationClient with drive.file, explicit opt-in and app-created private folders. Keep access tokens in memory and perform foreground manual sync only.

## Alternatives considered
### Android-only module
Simpler project setup, but couples pure transformation tests to SDK availability. Rejected because device-independent tests are a repository requirement.

### Broad Drive scope with arbitrary folder access
Allows existing folder traversal but expands authorization. Rejected for the PoC; a private app-created folder keeps drive.file viable and avoids Picker infrastructure.

### AGP 9 baseline
New built-in Kotlin configuration is available, but would add migration behavior unrelated to validating the health-data path. Prefer an explicitly pinned supported stable baseline for this PoC.

## Consequences and costs
Two modules and fake-transport protocol tests add build wiring; mocks do not prove account integration. Private folder sharing/ownership must be checked on reuse, and ambiguous targets fail. Uploads are idempotent per file, not transactionally atomic across a day's files. Re-evaluate for scheduled sync, arbitrary folder selection or remote commands. Accepted after independent P0 implementation conformance passed on 2026-10-31. Device/cloud gates remain separately pending; this is not a production-release approval.

## References
- [Specification index](../P0/spec.md)
- [Execution plan](../P0/plan.md)
- [Health Connect setup](https://developer.android.com/health-and-fitness/health-connect/get-started)
- [Google authorization](https://developer.android.com/identity/authorization)
