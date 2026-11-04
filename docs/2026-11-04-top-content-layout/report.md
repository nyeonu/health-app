# 제목바 아래 본문 스크롤 경계 수정

## 범위와 근거

소유자는 제목바 바로 아래에 본문 글자의 얇은 조각이 남아 보이는 현상을 다시 지적했고, 수정 진행과 새 세션의 PR 검토·교정·재검토를 요청했다. 사용자 화면·날짜·시각·건강 정보·첨부 참조는 공개 문서에 복사하지 않는다.

master 기준은 `c8e72795d5c14ad375ee0087f005a0d80d65b44c`다. 별도 읽기 전용 진단 세션이 기존 계정 없는 API35 native 합성 캡처에서도 스크롤된 본문의 얇은 글자 조각을 확인했다. 이는 최초 진입의 inset 결함을 입증하지 않는다. 기존 `verticalScroll(...).padding(24.dp)`는 여백도 본문과 함께 이동한다. 이번 수정은 여백을 스크롤 바깥에 배치해 바와 본문 사이 간격을 유지한다.

처음에는 한 줄의 modifier 순서를 바꾸는 국소 버그 수정으로 testing-policy를 적용했다. 이후 원본 재현에서 이것만으로 최초 가림이 해결되지 않음을 확인해 아래 '실제 native 경계 확인 후 교정'으로 보완했다. 기존 implemented 스펙은 변경하지 않는다. 일반적인 스크롤 경계의 문장 잘림 전체를 없애거나 Galaxy 최초 화면 문제를 해결했다고 주장하지 않는다. 동의·권한·수동 계속·취소·이력·진단 복사 계약은 유지한다.

## 검증 기준

- 본문 스크롤 전후와 스크롤된 상태의 Activity 재생성 후에도 상·하단 여백이 실제 뷰포트 밖에 유지된다.
- 상단으로 돌아오면 제목과 대상 문구가 부분 노출 판정에 그치지 않고 전체 높이와 뷰포트 경계 안에서 읽힌다.
- 권한 없는 새 Activity에서 Google 권한 확인은 비활성이고 업로드 계속은 생기지 않는다.
- 기존 밝음/어두움·큰 글꼴 합성 갤러리와 동기화/이력/진단 회귀는 유지한다. native 제목바 포함 화면과 Compose 루트만의 갤러리를 구분한다.

## 현재 실행 기록

수정 전 테스트 커밋은 `af4cde1da1dde2a55ac674238455b3d50bed7e4b`이며 앱/테스트 APK 컴파일은 통과했다. 실제 API35 CI41910732134 원본 XML에서57개 중 새 경계 테스트2개만 같은 상단 여백 단언으로 실패했고 기존55개는 통과했다(오류/skip0). 호스트 검증은 실행되지 않았다. 새 테스트 두 개는 실제 Activity에서 실행되며 합성 native 화면만 캡처한다.

- `nativeScreenKeepsContentBoundaryWhileScrollingAndReturningToTop`
- `nativeScreenKeepsContentBoundaryAfterRecreationAtScrolledPosition`

수정 후 로컬 전체 JVM109개(core41/app68) 실패/오류/skip0, 앱·테스트 APK·lint 검증1분32초 PASS다. 실제 OS CI 결과는 아직 대기다. 테스트 컴파일을 실제 OS 통과로 취급하지 않는다. 수정 전 실패 재현 여부·정확한 head·검토 및 교정·재검토·최종 처분은 [PR #14](https://github.com/nyeonu/health-app/pull/14)에 남긴다.

## 한계와 후속

현재 클라우드 환경에는 로컬 에뮬레이터/KVM이 없다. API35 CI의 합성 검증과 실제 Galaxy Android16 검증은 별개다. Galaxy 설치 후 동일 상단 영역의 관찰은 아직 미확인이다. 실제 사용자 자료나 추가 화면 덤프를 공개 저장소로 수집하지 않는다. API36·navigation mode/실제 회전별 초기 inset 원인은 이번 변경으로 확정하지 않는다.

실제 업로드 성능 개선은 별도 후속이며 이 배치 수정을 속도 개선으로 표현하지 않는다. 기존 P0/계정/TalkBack 미확인도 유지한다.

## 독립 리뷰와 교정

d404c80의 별도 fresh-context 중간 리뷰에서 생산 코드 차단 결함은 발견되지 않았고, 재생성 후 스크롤 값도 대조하자는 Suggestion1개를 받았다. 이를 수용해 로딩 완료 후 비영 스크롤 값을 기록하고 같은 방향/크기 재생성 후1px 허용 오차로 비교한다. 생산 코드는 그대로 보존했으며, 테스트 APK 재컴파일21초 PASS다. 실제 OS에서 추가 단언의 통과 및 새 head 독립 재검토는 아직 대기다. 기존 실패/지적 기록은 PR 코멘트에 보존한다.

## 캡처 증거 보강

독립 리뷰에서 수정 전 native PNG의 initial/recreated 이름과 실제 화면 상태가 맞지 않는 사례를 확인했다. 해당 이미지는 최초 진입/복원 프레임의 정확한 배치 증거로 사용하지 않는다. XML의 같은 경계 단언 실패와 별도로 구분한다. 현재 Activity 창의 frame commit을 기다린 뒤 Window PixelCopy를 수행하도록 테스트만 교정했다. native 앱 바는 포함하고 다른 창/전체 디스플레이 합성 상태에 의존하지 않는다. SDK36 메타데이터에서 Window PixelCopy API26 및 frame callback API29를 확인했으며 캡처는 계정 없는 API35 CI에서만 검증한다. 제품 minSdk28/창 설정/의존성은 유지한다. 테스트 APK 컴파일24초 PASS, 실제 새 캡처와 최신 테스트 단언 검증은 대기다.

## 새 독립 리뷰의 시각 증거 차단 지적

이전 리뷰 세션의 사용량 제한으로 새 fresh-context 세션에서 head5e8dbc8과 전체 diff/원본을 다시 검토했다. CI41913326597 원본은 Android57개·host20개 모두 실패/오류/skip0이지만, 픽셀과 단언한 상단 상태가 불일치해 REQUEST CHANGES(Important1)를 받았다. 이전 캡처5개는 최초/복원 배치가 정확하다는 증거에서 제외한다. 픽셀 일치를 확인하기 전에는 green CI만으로 병합하지 않는다.

설치된 Compose ui-test1.9.2와 AndroidX Test core1.6.1 구현을 대조한 결과 공식 캡처는 drawing 상태를 저장하고 켠 뒤 redraw/PixelCopy를 수행하고 복구한다. 원래 helper에는 drawing scope가 없었다. 당시 상태가 기록되지 않아 확정 원인으로 단정하지 않고, 기존 의존성의 공식 Window 캡처와 Compose-root 캡처를 동일 상태에서 쌍으로 검증하도록 교정했다. Async API의 ListenableFuture 노출 타입이 현재 컴파일 경로에 없어 컴파일20초 실패했고, 새 의존성을 추가하지 않고 기존 coroutine의 Main context에서 공개 suspend helper를 호출하는 방식으로 바꿔 컴파일25초 PASS했다.

새 검증은 root/window/viewport 좌표·밀도·스크롤·drawing 전후 상태만 숫자로 기록한다. 공식 helper는 테스트 스레드에서 기다리고 drawing 상태 복구와 스크롤 불변을 확인한다. 상·하단24dp 오차, 여백 중앙 픽셀의 surface 배경, 상단 제목/대상 영역의 background/foreground, 두 캡처의 해당 영역 픽셀 일치를 검사한다. 새 실제 OS 결과와 캡처의 독립 재검토는 아직 대기다. 생산 modifier 외 추가 창/테마/inset 변경은 하지 않았다.

## 공식 캡처 검증의 포커스 실패

`cbed5d97aa415d23a79ada6a92d446d79f8556da`의 JVM/APK/lint CI41928069820은 성공했고 원본 XML109개는 실패/오류/skip0이다. 같은 head의 emulator CI41928069684 첫 실행은57개 중3개 실패, 오류/skip0이다. 새 native 테스트2개는 공식 캡처 전 `hasWindowFocus()` 대기10초에서 종료됐으며 기존 시스템 클립보드 테스트도 같은 포커스 대기에서 실패했다. 새 캡처·픽셀 단언과 host 검증은 실행되지 않았다.

별도 리뷰 세션도 원본 XML·logcat을 대조했다. Activity의 RESUMED·Displayed는 확인되지만 실제 포커스 소유 창·잠금·전원 상태 증거가 없어 원인은 미확정이다. CI는 부팅 뒤 keyevent82와 화면 timeout 증가를 이미 수행한다. 소스·단언·timeout 변경 없이 같은 head를1회 재실행해 재현성을 확인한다. 첫 실패는 보존하고 Important는 미해결로 유지한다. 현재 코드까지의 별도 시크릿 검사도 발견0건이다.

## 동일 head 재실행에서 확인한 초기 배치 불일치

CI41928069684 attempt2 원본 XML은57개 중1개 실패, 오류/skip0이다. 포커스 실패는 재현되지 않았고 기존 클립보드 테스트도 통과했다. 초기 화면 캡처의 여백 픽셀 단언이 실패했고 host 검증은 미실행이다. initial의 semantic root는 창 전체 크기이고 native 제목바가 root 캡처에도 포함된다. 재생성 후에는 root가 제목바 아래로 이동해 제목/대상 문구가 실제로 보인다. 독립 리뷰어도 원본 이미지를 대조했다. drawing 전후 상태가 모두 true여서 이번 재현을 drawing-disabled 가설로 설명하지 않는다.

아직 native View의 실제 위치/측정 크기가 없어 실제 content fitting과 Compose 좌표 문제를 구분하지 못한다. 제품의 테마/inset 정책을 추측으로 바꾸기 전에 decor/content/ComposeView/owner의 위치·크기·layout 상태와 system bar/cutout inset을 캡처 전후에 기록한다. 두 snapshot을 하나의 원자적 프레임이라고 간주하지 않는다. 포커스 실패가 반복될 때는 teardown 전에 잠금/전원/포커스 숫자와 합성 전체 화면1장을 남기고 원래 예외를 유지한다. 단언과 timeout은 보존하며 Important와 Galaxy 미확인은 그대로 유지한다.

## 실제 native 경계 확인 후 교정

`3a49b0a`의 CI41932282264 원본은57개 중3개 focus timeout, 오류/skip0이며 host 미실행이다. 이번에는 실제 native content/ComposeView/owner도 최초에는 창 전체, 재생성 후에는 제목바 아래에 배치됨을 확인했다. 따라서 cached semantic 좌표만의 문제는 아니다. focus 실패 화면에는 Pixel Launcher ANR dialog가 앱을 가리고 있다. interactive=true, keyguard/locked=false였다. 별도 리뷰어도 원본 로그와 이미지를 확인했다. 같은 head APK/JVM/lint CI41932282110은 성공했다.

이 증거에 따라 중복 native 제목바를 제거하고 기존 본문 제목을 유지하는 국소 교정으로 보완한다. MainActivity는 no-title window와 `enableEdgeToEdge()`를 사용하고 시스템 안전 영역은 `safeDrawingPadding()`으로 처리한다. 바 높이 하드코딩이나 edge-to-edge opt-out은 추가하지 않는다. [공식 inset 적용](https://developer.android.com/develop/ui/compose/system/insets-ui)과 [Android15 정책](https://developer.android.com/about/versions/15/behavior-changes-15)을 확인했다. 새 toolbar·기능·의존성·권한·스크롤 초기화는 추가하지 않는다. 기본 decor 내부 분기 전체나 Galaxy의 원인까지 확정하지 않는다.

회귀 검증은 명시적인 safe-content container가 실제 시스템 bar/cutout 안에 있는지 확인한 뒤 그 안쪽24dp 경계·여백 픽셀·문구 전체 높이·root/Window 픽셀 비교를 유지한다. 재생성 전후 안전 영역과 비영 스크롤 위치도 대조한다. 전체 window와 안전 영역을 혼동해24dp 단언을 제거하지 않는다.

CI는 API35/36에서 각각 전체 Android/host 검증과 별도 artifact를 남긴다. 컴파일 후 connected 테스트 직전에 확인된 `com.google.android.apps.nexuslauncher`만 force-stop한다. Health App 등 임의 앱의 ANR를 숨기거나 실패 단언을 재시도/무시하지 않는다. 앞선 Launcher ANR 원본은 보존한다.

현재 교정의 전체 검증과 독립 재검토는 대기다. Box receiver 추가로 Context 컴파일 오류가 발생했으며 명시적 MainActivity receiver로 수정해 재검증한다. 기존 Important와 실제 Galaxy 미확인은 유지한다. 앞 절의 pending 기록은 각 당시 상태이며 최종 처분은 PR 코멘트와 이 절의 후속 결과로 구분한다.

경계 교정의 로컬 JVM109개·앱/테스트 APK·lint는1분18초 통과했다. 정적 재검토에서 새 Critical/Important는 없었다. 새 Box는 기존 Surface의 최소 높이를 본문까지 전달하도록 명시한다. native 제목바 제거 후 넓어진 화면에서도 비영 스크롤을 실제 검증하도록 상세 설정을 펼친 뒤 하단 정적 문구까지 이동한다. 최초 캡처는 접힌 상태를 유지하고 재생성 후에는 펼친 상태와 스크롤 복원도 확인한다. 화면 동작·단언을 우회하지 않는 fixture 보강이며 별도 리뷰어가 방향을 확인했다. 최신 소스의 OS 검증과 최종 재검토는 여전히 대기다.
