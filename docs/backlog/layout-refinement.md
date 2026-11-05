---
status: scoped_fix_owner_top_pass_other_device_pending
reported: 2026-11-03
scope: future manual-sync screen layout refinement
---

# Keep the top screen text readable

The owner reports text obscured at the top of the manual-sync screen beneath the Health App app bar on the target phone. The owner initially deferred this while proceeding with performance measurement, then authorized continued handling of the marked top boundary. Fixed outer padding alone failed the initial-frame pixel check. Native geometry confirmed a different initial content boundary from the recreated Activity. The correction combines the existing body heading, no duplicate native title bar, explicit safe drawing insets, early fitting-policy normalization, and one initial inset dispatch after listener installation; see [current fix scope and evidence](../2026-11-04-top-content-layout/report.md). API35/36 full verification passed with 57 Android tests and 20 host checks per API; a separate fresh-context review approved the exact tested code after directly checking original captures and restored scroll position. 2026-11-05 소유자는 PR14 APK 업데이트 후 최초 진입/상단 복귀의 제목·날짜 문구가 정상이라고 보고했다([PR14 코멘트6647681407](https://github.com/nyeonu/health-app/pull/14#issuecomment-6647681407)). 이 두 표시 관찰만 user-reported PASS이며, 회전/navigation mode·TalkBack·대형 글꼴 등 나머지 실제 기기 수용과 기존 P0/계정 검증은 미확인이다. 원본 화면·건강값·개인 시각은 복사하지 않는다. 다른 layout 변경은 계속 보류한다.

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
