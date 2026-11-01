---
status: deferred
requested_by: user
scope: future profiling and evidence-based sync optimization
---

# Deferred: investigate slow manual synchronization

The user reports that upload/synchronization takes too long and asks to identify the bottleneck and consider parallel execution or other speed improvements **as later work**. No optimization, instrumentation, new dependency, concurrency change or performance acceptance target is authorized by this note. Continue the current P0 device acceptance first unless the user reprioritizes.

## Current evidence and limits

One supplied success screen shows roughly 11 seconds between the app's displayed attempt and success fields. This is an end-to-end UI interval that can include Google authorization and user wait before upload continuation, Health Connect reads, normalization/CSV generation and Drive requests. It is not a measurement of upload-only latency, a repeatable benchmark, or evidence of which stage is slow. Exact private timestamps and health data are not retained here. No p50/p95, device CPU/memory, network latency or battery baseline has been collected for this request.

## Investigate when this work starts

- Measure authorization/user-wait separately from actual sync execution.
- Separate Health Connect type/page reads, record/sample filtering, normalization/CSV encoding, Drive folder/file lookup and actual create/PATCH transfer timings.
- Establish repeated comparable runs with controlled permission/data-volume/network/device conditions. Use synthetic fixtures for public CI; keep real-device diagnostics private and exclude values, identifiers, URLs, tokens and raw payloads.
- Locate the dominant stage before choosing a fix. Repeated metadata resolution, unnecessary rewrites, batching and bounded parallelism are candidates to evaluate, not established causes or chosen designs.
- Compare the selected change against the same baseline and retain meaningful regression checks. Agree measurable success criteria when starting; no arbitrary latency SLA or concurrency level is set by this backlog item.

## Preserve existing correctness and privacy

Any future change must preserve fresh permission checks, private-owner target validation, stable same-date file identities, explicit partial outcomes, no implicit write replay, cancellation propagation, the per-page/sample caps, account separation and redacted errors. Parallel requests must not introduce concurrent same-target creation, duplicate files, misleading all-success status or bypassed privacy checks. Broader raw-history reading can still exhaust P0 limits for a small export day; do not silently truncate or call that a successful export to improve timing.

Start through the performance-optimization measurement workflow and the applicable be-workflow specification/plan process. This document is a backlog note, not an approved specification, execution plan, or architecture decision.
