---
status: scoped_fix_in_progress
reported: 2026-11-03
scope: future manual-sync screen layout refinement
---

# Keep the top screen text readable

The owner reports text obscured at the top of the manual-sync screen beneath the Health App app bar on the target phone. The owner initially deferred this while proceeding with performance measurement, then authorized continued handling of the marked top boundary. A narrowly scoped fixed outer-padding correction is in progress; see [current fix scope and evidence](../2026-11-04-top-content-layout/report.md). Other layout changes remain deferred.

The private screenshot is not copied into this public repository. It contains account-adjacent health-sync information. Do not reproduce its dates, timestamps, dataset coverage, file counts or identifying attachment references here. This report is not a performance measurement or a new P0 acceptance result.

## Reproduce before choosing a fix

- Distinguish initial entry and a fully scrolled-to-top screen from content intentionally passing behind a fixed app bar during scrolling. The report alone does not establish an inset defect or its cause.
- Inspect the native Activity app bar, content window/system insets, and the Compose scroll container together. Current source boundaries are `MainActivity.onCreate` and `HealthHomeScreen`, whose first elements are the screen title and reporting-window text.
- Check cold launch, return to the app, scroll-to-top, rotation and saved scroll position; use synthetic state for public evidence.
- Repeat with light/dark mode, large system font/display sizes, and supported system navigation modes. Do not change the owner's phone settings remotely or infer its exact configuration from the screenshot.

## Acceptance for the later layout task

- At the top of the screen, the title and reporting-window text are completely readable and do not overlap the app bar or system bars.
- Status cards, actions and consent text remain reachable and readable with large fonts, scrolling and restored screen state.
- Scope the actual fix after reproduction; do not preselect app-bar removal, a second toolbar, fixed padding or an inset policy from this report alone.
- Preserve permission/consent gates, explicit upload continuation, lifecycle cancellation, private Drive checks and local-history behavior.
- Verify with synthetic automated UI/lifecycle checks and retain actual target-device validation as a distinct result. A future PR requires a separate review session and findings/fixes/rechecks in its PR comments, as required by AGENTS.md.

Performance diagnosis remains a separate follow-up using the private safe-summary procedure. Device summaries are not copied here; no latency improvement or optimization is established by this layout correction. This item does not change the existing device/account/P0 acceptance gates.
