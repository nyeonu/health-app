# ADR-002: Use an explicit PATCH-capable Drive HTTP client

## Status
Accepted

## Date
2026-10-31

## Ticket
PR-1-followup

## Context
The PR reviewer requested replacing HttpsURLConnection PATCH delegation and adding real transport-level tests. The existing fake Drive tests exercise protocol decisions, not the concrete HTTP client. Platform-specific PATCH behavior must not be assumed from a JVM or undocumented implementation.

## Decision
Use pinned OkHttp 5.3.0 for the P0 Drive transport while preserving DriveTransport and GoogleDriveTransport's production constructor. Add app-module JVM tests against a local TLS server to verify wire PATCH, cancellation and security invariants. Keep production HTTPS trust, host/path restrictions, no redirects, timeout/body limits, redacted errors and explicit-only write retries.

## Alternatives considered
- Keeping URLConnection would leave documented method support and client behavior unresolved across platforms.
- Google-specific HTTP method override would couple the transport to another server contract without improving standard PATCH interoperability; an explicit supported client follows the review request.
- A new transport module is unnecessary for this bounded correction: app JVM tests can execute the Android-free transport with the existing SDK and no hardware, while core tests remain SDK-independent.

## Consequences and costs
The APK gains a maintained HTTP client dependency and test-only server/TLS dependencies. Their graph requires an audit. A local TLS test is not an actual Google-account upload, Android UI or Samsung device validation. Account consent and actual private create/update gates remain open. Independent conformance and final quality/security review passed against source fea0058 on 2026-10-31; final security review is recorded in the follow-up report. Device/account gates remain separate and open.

## References
- [Follow-up specification](../PR-1-followup/spec.md)
- [Follow-up plan](../PR-1-followup/plan.md)
- [PR review and user comments](https://github.com/nyeonu/health-app/pull/1)
- [OkHttp official source and documentation](https://github.com/square/okhttp)
