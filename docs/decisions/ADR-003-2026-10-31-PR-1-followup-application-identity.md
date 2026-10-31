# ADR-003: Use com.ny.my.health as the Android application identity

## Status
Accepted

## Date
2026-10-31

## Ticket
PR-1-followup

## Context
The user explicitly requested a new applicationId/namespace and consistent source/core/test/documentation packages in PR1 comment6538195319. Android application identity affects installation, grants and OAuth registration; it is not a cosmetic label.

## Decision
Use com.ny.my.health for the app and com.ny.my.health.core for core code. Move matching source/test directories and update references without changing algorithms, CSV, OAuth scope or runtime features.

## Alternatives considered
Keeping the previous identity or changing only the display label does not satisfy the explicit request. A compatibility bridge, automatic Drive migration or extra V1 functionality is outside scope.

## Consequences and costs
Android treats this as another app, not an in-place update. Install the new app and re-grant Health Connect and Google consent. The user may remove the previous test app after considering its local state; remote Drive files are not automatically removed. Register a Google Android client for the new package plus the actual artifact certificate SHA-1. Existing OAuth clients and prior drive.file visibility must not be assumed to transfer. Inspect old private exports manually and preserve or clean them up deliberately; no automatic delete/migration is introduced.

## References
- [User request](https://github.com/nyeonu/health-app/pull/1#issuecomment-6538195319)
- [Follow-up specification](../PR-1-followup/spec.md)
