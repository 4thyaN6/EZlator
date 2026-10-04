# GitHub 저장소 등재 정보

저장소를 만들 때 그대로 옮겨 쓰기 위한 정리입니다.

## 1. 기본 정보

| 항목 | 값 |
|---|---|
| 저장소 이름 | `EZlator` |
| 공개 여부 | Public (LGPL 소스 제공과 MIT 고지를 위해 공개 저장소 권장) |
| 한 줄 설명 (About) | Run Windows games on Android - a beginner-friendly fork of Winlator Cmod |
| 한국어 설명 | 안드로이드에서 Windows 게임 실행 - 처음 쓰는 사람을 위한 Winlator Cmod 포크 |
| Topics | `android` `wine` `proton` `winlator` `box64` `fexcore` `dxvk` `vkd3d` `steam` `emulator` |
| 라이선스 | MIT (GitHub 라이선스 선택에서 "MIT"를 고르지 말고, 저장소 루트의 기존 `LICENSE`를 그대로 올림 - 원저작권 표기 유지) |
| 기본 브랜치 | `main` |

## 2. 앱 안에서 저장소 주소를 쓰는 곳

- `app/src/main/res/values/strings.xml`의 `repo_url` = `https://github.com/4thyaN6/EZlator`
- 정보 화면의 "제3자 고지 및 라이선스" 링크가 `repo_url` + `/blob/main/THIRD_PARTY_NOTICES.md`를 엽니다.

## 3. 올릴 파일 / 올리지 않을 파일

**올릴 것**: 소스 전체, `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `licenses/`(`LGPL-2.1.txt` 포함), `docs/`, `branding/`

**올리지 않을 것** (루트 `.gitignore`에 반영됨)

| 구분 | 항목 | 이유 |
|---|---|---|
| 빌드 결과·캐시 | `/build`, `/app/build/`, `/app/release/`, `/app/debug/`, `.gradle`, `/.idea/`, `/.kotlin/`, `.cxx`, `*.apk`, `*.aab`, `/deps.txt` | 빌드할 때마다 다시 생김 |
| 이 PC 전용 설정 | `/local.properties` | SDK 경로만 들어 있음 |
| 서명 키 | `*.jks`, `*.keystore`, `keystore.properties` (`app/testkey_untrusted.jks`만 예외) | 배포 키 유출 방지 |
| Win Components | `app/src/main/assets/wincomponents/*.tzst` (`opengl.tzst`, `ddraw.tzst` 제외) | Microsoft DLL 묶음. 저장소에는 올리지 않음. `wincomponents.json`은 코드가 읽으므로 올림 |
| 개인 작업 파일 | 저장소에 올라가지 않는 로컬 제외 목록(`.git/info/exclude`)에서 처리 | 이 PC 경로가 들어간 작업용 파일 |

## 4. Release 구성 (버전마다)

| 첨부 파일 | 내용 |
|---|---|
| `EZlator-x.y.z.apk` | release 서명 APK |
| `EZlator-x.y.z-lgpl-sources.zip` | LGPL 구성요소 소스 (licenses/README.md 참고) |

Release 본문에는 변경 사항, 요구 사항(Android 8+, arm64), 그리고 "debug 빌드를 쓰던 경우 서명이 달라 삭제 후 설치 필요" 안내를 넣습니다.


