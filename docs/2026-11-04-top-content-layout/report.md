# 상단 본문 가림과 스크롤 경계 교정

## 현재 범위

기준 master는 `c8e72795d5c14ad375ee0087f005a0d80d65b44c`다. 소유자는 상단 가림 수정과 별도 세션의 PR 검토·교정·재검토를 요청했다. 사용자 화면·건강 값·날짜/시각·첨부 참조를 공개 문서로 복사하지 않는다.

최초 본문이 native 제목바 뒤에 가리는 문제와 재생성 후 스크롤 손실을 다룬다. 기존 본문 제목을 사용하고 no-title window, 명시적 edge-to-edge 및 Compose safeDrawing을 적용한다. 시스템 안전 영역 안쪽24dp 여백을 스크롤 바깥에 유지한다. 일반 스크롤 경계의 문장 일부 잘림 전체를 없앤다고 주장하지 않는다.

실제 원본에서 최초 native content/Compose owner는 창 전체, 재생성 후에는 시스템 inset만큼 줄어드는 차이를 확인했다. SideEffect만 적용한 후보는 이 상태에서 여백이 중복 적용되어 폐기했다. 공식 [AOSP15 PhoneWindow](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/com/android/internal/policy/PhoneWindow.java), [ActivityThread](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/ActivityThread.java) 및 설치 AndroidX 바이트코드에는 보존된 Window의 기본 content-inset 적용이 등록된 뒤 edge enforcement 초기화가 이어지는 경로가 있다. 이는 관측과 일치하는 소스 근거이며 실제 OS의 private 필드를 읽어 확정한 것은 아니다.

현재 후보는 `enableEdgeToEdge()` 전에 `WindowCompat.setDecorFitsSystemWindows(window, false)`로 보존된 창의 fitting 정책을 맞추고, 정규화된 창에서 최초 inset을 listener 설치 뒤 단발 SideEffect로 전달한다. owner별 nonSaveable flag는 root inset이 있을 때 dispatch한 뒤에만 켠다. 매 recomposition에 반복 전달하지 않으며 null일 때 자동 재시도를 보장한다고 주장하지 않는다. 지연·픽셀 offset 보정·재스크롤·커스텀 scroll 복원 알고리즘은 사용하지 않는다. 기존 implemented 스펙·의존성·권한은 바꾸지 않는다. 동의/권한/명시적 업로드 계속/중단/로컬 이력/진단 복사 계약을 유지한다.

## 회귀 검증

새 native 테스트2개는 실제 MainActivity에서 실행한다.

- `nativeScreenKeepsContentBoundaryWhileScrollingAndReturningToTop`
- `nativeScreenKeepsContentBoundaryAfterRecreationAtScrolledPosition`

안전한 본문 container가 실제 system bar/cutout 안인지 확인한 뒤 그 안쪽 상·하단24dp를 검사한다. 최초/상단 복귀 제목과 대상 문구 전체 높이, 실제 긴 화면 스크롤, 재생성 전후 동일 안전 영역·정확한 비영 scroll offset·펼친 상태를 검증한다. 첫 화면은 접힌 상태로 캡처하고, 넓은 창의 스크롤/복원은 상세 설정을 펼쳐 하단 정적 문구로 이동해 검증한다. 외부 권한/삭제/인증 동작을 호출하지 않는다.

공식 본문/Window 캡처 쌍의 drawing 복구·scroll/좌표 불변, 여백 surface 픽셀, 제목 foreground/background와 동일 영역 픽셀을 검사한다. native 제목바 포함 창 캡처와 Compose 합성 갤러리는 구분한다. 캡처 전후 geometry snapshot은 하나의 원자적 프레임으로 취급하지 않는다. 단언 삭제·timeout 완화·실패 무시로 통과시키지 않는다.

임시 진단 브랜치의 global-layout sampler는 공개 ViewRootForTest로 value/max/viewport/inset 숫자만 읽으며 최대12회 후 또는 detach 때 제거한다. layout 이후 관찰이므로 첫 measure 내부를 직접 보장하지 않는다. 이 sampler/attach 관찰자는 최종 기능 테스트에 포함하지 않는다. 최종 복원 테스트는 직접 Activity를 재생성하고 원래 단언을 실행한다.

## 실행 및 독립 리뷰 이력

| 소스/실행 | 확인한 결과와 한계 |
| --- | --- |
| 수정 전 `af4cde1`, CI41910732134 | Android57개 중 새 경계2개 실패, 기존55개 통과, 오류/skip0. host 미실행. 당시 PNG는 정확한 initial/복원 프레임 증거에서 제외한다. |
| `a3d262e` CI41911618576 / `5e8dbc8` CI41913326597 | Android57개·host20개 통과했지만 실제 이미지와 단언 상태 불일치를 독립 리뷰가 발견했다. 그림 상태의 증거로 승계하지 않으며 Important/REQUEST CHANGES를 보존했다. |
| `cbed5d9`, CI41928069684 | attempt1은57개 중3개 focus timeout, attempt2는57개 중 초기 여백 픽셀1개 실패. 오류/skip0, host 미실행. |
| `3a49b0a`, CI41932282264 | 실제 native 경계 차이와 Pixel Launcher ANR dialog 확인.57개 중3개 focus 실패, host 미실행. |
| `c3aca36` CI41935229440 / `b679692` CI41938627239 | API35 최초/스크롤/복귀의 root/Window3쌍 픽셀 일치와 문구 표시를 확인했지만57개 중 복원1511→1320으로1개 실패. 오류/skip0, host 미실행. API36은 userdata 공간 부족으로 부팅 전 실패·테스트 미실행. |
| 진단 `abc4044`, subset CI41941553195 | API35는6개 중 복원1개 실패. API36은6개 중 focus/복원2개 실패이며 focus 화면은 System UI ANR였다. 둘 다 오류/skip0, host 미실행. |
| 진단 `66694ee`, subset CI41942086574 | API35는6개 중 복원 안전 영역 중복128..2337→256..2274로1개 실패. 실제 owner 높이2209와 max1702/value1511을 관찰했다. 이 후보를 최종 구현으로 사용하지 않는다. |
| 진단 `793f12b`, subset CI41944722470 | API35는6개 중 복원1개 실패. native owner/frame은0,0/1080×2400/padding0으로 정상화됐지만 value1320/max1511로 위치 손실은 남았다. 단독 후보를 해결 완료로 표시하지 않는다. |
| `bddfc29` 전체 CI41945124863 | fitting 단독 소스의 전체 실행이며 결합 후보의 통과 근거로 세지 않는다. |
| 현재 결합 후보의 기능 테스트 | 임시 관찰자가 없는 소스로 정확한 offset/안전 영역/픽셀 단언을 유지하고 로컬 전체 검증 및 API35/36 각각 Android57개·host20개 검증·독립 재검토를 진행한다. |

원본 JVM CI41938627257의 XML14파일은109개(core41/app68), 실패/오류/skip0이다. 별도 fresh-context 리뷰어가 직접 읽었다. fitting 단독 후보의 로컬 전체 검증1분17초 및 임시 관찰자 제거 후1분14초는 통과했다. 결합 후보의 실제 결과는 후속으로 기록한다. 이전 취소/superseded 실행은 성공으로 세지 않는다.

별도 세션들은 전체 diff와 원본을 검토했고 캡처 불일치, scroll 복원 손실, outer attach의 listener 미설치 순서를 지적했다. 환경 중단 후 새 fresh-context 세션에서 이전 보고서/PR 코멘트 결론을 승계하지 않고 전체를 다시 검토했다. 각 지적·수정·정확한 head·재검토 및 최종 처분은 [PR #14](https://github.com/nyeonu/health-app/pull/14) 코멘트에 보존한다. 새 원본 전에는 Important 해결 완료나 APPROVE로 표시하지 않는다.

## CI와 남은 확인

API35/36 Google APIs·Pixel6 합성 환경을 각각 실행한다. API36 이미지의 최소 userdata 크기는2048M 설정으로 줄지 않아, disposable runner의 이 Kotlin/JVM 빌드에 미사용인 .NET/NDK 공간을 확보한다. 실제 부팅 성공을 확인했다. Launcher/System UI ANR는 앱 결함과 구분해 원본을 보존하며 가상 기기에4코어/4096M RAM을 명시한다. 확인된 Launcher만 테스트 전 정리하며 Health App/System UI의 ANR를 숨기거나 실패를 재시도하지 않는다. [action 입력 계약](https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/action.yml)을 확인했다.

로컬 emulator/KVM은 없어 OS 실행은 CI에서 검증한다. API35/36 합성 결과는 실제 Galaxy Android16 설치·같은 상단 영역·회전/navigation mode·TalkBack을 대신하지 않는다. 해당 기기 확인과 기존 P0/계정/별도 키 백업 미확인은 그대로 유지한다. 업로드 성능 개선은 별도 후속이다.
