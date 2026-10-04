# EZlator

Android에서 Windows 게임을 실행하는 앱입니다. [Winlator](https://github.com/brunodev85/winlator)와 Winlator Cmod를 바탕으로 만든 포크이며, 처음 쓰는 사람도 바로 게임을 실행할 수 있도록 설정 과정을 줄이는 데 초점을 두었습니다.

An Android app for running Windows games, forked from Winlator / Winlator Cmod, focused on making the first setup simple.

> EZlator는 Microsoft, Valve와 관련이 없으며 승인받은 제품이 아닙니다. Windows는 Microsoft Corporation의, Steam은 Valve Corporation의 상표입니다.

## 주요 기능

- **첫 실행 안내**: "아무것도 몰라요" 한 번으로 시스템 파일, 구성요소, 그래픽 드라이버를 받고 컨테이너(ARM64EC, X86_64)를 자동으로 만듭니다.
- **게임 추가**: + 버튼으로 exe를 고르면 아이콘을 뽑아 타일로 등록합니다. 타일 이미지와 배경 커버를 직접 지정할 수 있습니다.
- **레이어 단계 (EZ 모드)**: Latest(최신, Proton 11.0-2) / Stable(안정, 10.0-4) / Legacy(레거시, 9.0) 세 단계를 컨테이너 화면에서 설치하고, 게임 상세창의 레이어 드롭다운에서 단계·아키텍처(ARM64EC, X86_64)를 골라 옮깁니다. 게임별 구성요소를 바꾸면 CUSTOM으로 표시되고 되돌릴 수 있습니다. 게임이 바로 꺼지면 설치되지 않은 단계를 한 번씩 권합니다.
- **세이브**: 모든 컨테이너가 하나의 세이브 폴더(AppData, Documents, Saved Games, 공용 문서, 스팀 userdata)를 함께 써서 레이어를 바꿔도 세이브가 이어집니다. 설정에서 전체 백업·복원, 길게 누르기 메뉴에서 게임별 세이브 추출을 할 수 있습니다.
- **스팀**: 로그인(비밀번호, Steam Guard, QR), 보유 게임 목록(캐시·당겨서 새로고침), 백그라운드 다운로드(알림, 일시정지/재개/정지, 게임 중 자동 일시정지), 설치된 게임 업데이트 확인.
- **게임 화면**: 간단 HUD(엔진·FPS·시작 중 표시), 전체화면(창 테두리 잘라내고 화면 채우기), 터치패드/터치스크린 모드, 일시정지 시 재개 버튼, 게임이 끝나면 자동으로 나가기.
- **프로세스 정리**: 게임을 끝낼 때 게임 → Wine → wineserver → box64/FEX → PRoot 순서로 남은 프로세스를 모두 정리합니다.
- **드라이브**: D: 내장 EZlator 폴더, E: 외장 EZlator 폴더, Y: 내장 메모리 전체, X: 외장 메모리 전체.
- **한국어 지원**: 한국어 UI(일부), 한글 글꼴 연결(Windows 한글 글꼴 이름·파일 대체), 유저 번역 패치용 DLL 오버라이드(winhttp, version), 바로가기별 언어 로캘.
- **내보내기 폴더**: 로그, 프로필, 백업, 세이브 추출은 `Download/.EZlator/` 아래에 저장됩니다.

## 요구 사항

- Android 8.0 이상, arm64-v8a 기기
- targetSdk 28로 빌드됩니다. 앱 데이터 폴더의 실행 파일(box64, FEXCore, Wine)을 실행하기 위한 설정이라 Google Play에는 올릴 수 없고, 이 저장소의 Releases에서 APK를 받아 직접 설치합니다.
- 테스트 기기: Lenovo TB323FU (Android 16)

## 설치

1. [Releases](../../releases)에서 최신 `EZlator-x.y.z.apk`를 받아 설치합니다.
2. 앱을 처음 열면 안내창에서 "아무것도 몰라요" 또는 "해본적 있어요"를 고릅니다.
3. 받기가 끝나면 + 버튼으로 게임의 exe를 추가합니다.

앱을 삭제해도 내장·외장 메모리의 `EZlator` 폴더와 다운로드 폴더의 게임·백업·로그는 지워지지 않습니다.

## 빌드

| 항목 | 값 |
|---|---|
| Android Gradle Plugin | 8.8.0 |
| Gradle | 8.10.2 |
| JDK | 17 |
| compileSdk / minSdk / targetSdk | 34 / 26 / 28 |
| NDK | 27.0.12077973 |

```
./gradlew assembleRelease
```

release 서명 키는 저장소에 포함되지 않습니다. `app/testkey_untrusted.jks`는 배포용이 아닙니다.

`app/src/main/assets/wincomponents/`의 Windows 구성요소 파일(`opengl.tzst`, `ddraw.tzst` 제외)은 저장소에 포함되지 않습니다. 이 저장소만으로 빌드한 APK에는 Win Components를 Native(Windows)로 바꿀 때 쓰는 파일이 들어가지 않습니다.

## AI 사용 안내

이 앱의 개발 과정(소스 코드 수정, 문서와 릴리즈 노트 작성)에서 AI 도구(Anthropic의 Claude)를 사용했습니다. 결과물은 개발자가 직접 적용하고 Lenovo TB323FU(Android 16)에서 실행해 확인했습니다.

This app was developed with the help of an AI tool (Anthropic's Claude) for source code changes and for writing documentation and release notes. The results were applied by the developer and checked on a Lenovo TB323FU (Android 16).

## 라이선스

- 앱 코드: MIT (원본 Winlator의 [LICENSE](LICENSE)를 따름)
- 포함된 LGPL 구성요소(PulseAudio, libsndfile, GNU libltdl)의 안내와 소스 제공 방식: [licenses/README.md](licenses/README.md)
- 전체 제3자 구성요소 목록: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 크레딧

Winlator (brunodev85), Winlator Cmod (coffincolors, Pipetto-crypto), Winlator Glibc (longjunyu2), Box64, FEX, Wine / Proton, DXVK, VKD3D-Proton, Mesa Turnip, JavaSteam 등 많은 프로젝트 위에서 만들어졌습니다. 자세한 목록은 THIRD_PARTY_NOTICES.md와 앱의 정보 화면에 있습니다.
