# 고정 서명 설정 — 휴대폰과 GitHub Codespaces

같은 서명 키를 빌드마다 재사용하면 SHA-1이 유지됩니다. 현재 공개 저장소의 코드에는 키가 들어 있지 않습니다. 실제 고정 키는 본인의 개인 작업 공간에서 한 번만 만들고 보관합니다. 지금까지 설치한 임시 서명 APK와 키가 다르므로 전환할 때 한 번은 OAuth 등록과 재설치·권한 재허용이 필요합니다. 이후 같은 키·패키지와 설치 가능한 버전을 유지하면 앱 업데이트에 같은 인증서를 사용할 수 있습니다.

## 1. 휴대폰에서 개인 작업 공간 열기

1. [저장소의 작업 브랜치](https://github.com/nyeonu/health-app/tree/feat/p0-android-poc)를 엽니다. 메뉴가 보이지 않으면 브라우저의 데스크톱 사이트 보기를 사용하세요.
2. **Code → Codespaces → Create codespace on feat/p0-android-poc**를 선택합니다. [GitHub 공식 생성 안내](https://docs.github.com/en/codespaces/developing-in-a-codespace/creating-a-codespace-for-a-repository)를 참고하세요. 생성 화면의 계정 사용량·요금 안내를 확인하세요.
3. Codespaces 편집기에서 **Terminal → New Terminal**을 엽니다. 저장소가 열린 터미널에서 아래 명령을 실행합니다.

```bash
bash scripts/create-signing-key.sh
```

비밀번호를 두 번 입력합니다. 화면에 글자가 표시되지 않는 것이 정상입니다. 비밀번호는 본인의 비밀번호 관리자에 보관하세요. JDK의 `keytool`이 없다는 오류라면 이 Ubuntu 기반 터미널에서 JDK를 설치한 후 같은 명령을 다시 실행합니다.

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk-headless
bash scripts/create-signing-key.sh
```

스크립트는 저장소 밖의 `~/.health-app-signing/`에 키와 변환 파일을 생성합니다. 이미 파일이 있으면 덮어쓰지 않습니다. 같은 키를 계속 재사용해야 하므로 다시 생성하거나 지우지 마세요.

## 2. 지금 화면에 Secret 두 개 등록

[Repository secrets](https://github.com/nyeonu/health-app/settings/secrets/actions)에서 **New repository secret**을 각각 누릅니다. **Variables**나 **Codespaces secrets**가 아닌 **Actions의 Repository secrets**입니다.

| Name | Secret에 넣을 값 |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE_BASE64` | 생성된 `healthapp-signing.keystore.base64` 파일의 전체 내용 |
| `ANDROID_SIGNING_PASSWORD` | 키 생성 시 입력한 비밀번호 |

Base64 파일을 편집기에서 열려면 개인 Codespaces 터미널에서 다음 명령을 실행하세요. 열린 파일의 전체 내용을 복사해 첫 번째 Secret 값에 붙여넣습니다. Base64는 암호화가 아니며 이 내용도 키와 똑같이 비밀로 취급하세요. 복사가 끝나면 클립보드를 다른 내용으로 바꾸세요.

```bash
code "$HOME/.health-app-signing/healthapp-signing.keystore.base64"
```

키 파일과 비밀번호는 채팅·PR·코드·스크린샷·Actions 로그로 보내지 않습니다. 스크립트는 PKCS12와 고정 별칭 `healthapp`을 사용하며, 빌드는 위 비밀번호를 저장소/키 비밀번호로 함께 사용합니다.

## 3. 키 백업

Codespaces가 삭제되기 전에 원본 `healthapp-signing.p12`를 개인 저장소에 백업하세요. 터미널에서 아래 폴더를 편집기에 열고 파일 탐색기의 p12 파일 메뉴에서 **Download**를 선택할 수 있습니다. 키 파일은 암호화된 개인 보관함에, 비밀번호는 비밀번호 관리자에 보관하세요. 다운로드 파일과 Base64 파일을 공개 저장소에 추가하지 마세요. 이 키를 잃으면 동일 서명으로 업데이트할 수 없습니다.

```bash
code -r "$HOME/.health-app-signing"
```

백업을 확인한 뒤 사용하지 않는 Codespaces를 중지하세요. 백업 전에는 삭제하지 마세요.

## 4. 고정 키로 APK 빌드

Secret 등록 후 **Actions → Android debug APK**에서 이 설정이 들어 있는 최신 **push 실행**을 선택하고 **Re-run all jobs**를 누릅니다. PR 실행은 서명 키를 사용하지 않으며 APK도 배포하지 않습니다. 예전 커밋 실행은 예전 설정으로 재실행되므로 선택하지 마세요. workflow가 기본 브랜치에 없는 현재 단계에서는 **Run workflow** 버튼이 보이지 않을 수 있습니다. 기본 브랜치에 반영된 후에는 그 버튼으로 지원 브랜치 빌드를 실행할 수도 있습니다.

성공한 실행의 `health-app-p0-debug-apk` ZIP에는 APK와 공개 인증서 SHA-1을 적은 `signing.txt`가 들어 있습니다. 최초 고정 서명 빌드의 SHA-1과 패키지 `com.ny.my.health`를 Android OAuth 클라이언트에 등록하세요. 첫 전환에는 기존 임시 서명 앱의 재설치와 권한 재허용이 필요합니다. 이후 같은 키를 재사용하면 SHA-1을 다시 등록할 필요가 없습니다. 키를 재생성하거나 Secrets를 다른 키로 바꾸면 다시 서명이 바뀝니다.

두 Secret이 모두 없으면 테스트와 빌드는 실행하되 다운로드 APK는 올리지 않습니다. 하나만 있거나 키/비밀번호가 잘못되면 고정 서명 빌드가 실패하며 APK를 배포하지 않습니다. 임시 서명으로 대체해 배포하지 않습니다.

공식 참고: [Android 앱 서명](https://developer.android.com/studio/publish/app-signing), [GitHub Actions Secrets와 Base64 파일](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
