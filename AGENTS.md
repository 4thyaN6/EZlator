# AGENTS.md

EZlator는 Winlator / Winlator Cmod 기반의 Android 앱으로, Android(arm64-v8a)에서 Windows 게임을 실행합니다.
이 저장소에서는 Claude가 코드를 작성하고, Codex(GPT)가 PR을 검수합니다.

## 역할 분담

- **Codex(GPT)는 검수만 합니다.** 코드를 직접 수정하거나 커밋·PR을 만들지 말고, PR 리뷰 코멘트로 의견만 남깁니다.
- 지적 사항은 Claude가 반영해 같은 PR에 다시 push합니다.

## Review guidelines

리뷰는 **한국어**로 작성합니다.

### 무엇을 지적할지

다음에 해당하는 **실제 문제(P0/P1)만** 지적하세요. 취향 차이, 이름 짓기, 사소한 스타일, 근거 없는 추측은 지적하지 않습니다.

- **정확성**: 크래시, NullPointerException, 잘못된 로직, 경쟁 상태, 리소스·파일 핸들 누수
- **프로세스 정리**: 게임 종료 시 게임 → Wine → wineserver → box64/FEX → PRoot 순서의 정리가 깨지거나 좀비 프로세스가 남는 변경
- **네이티브/JNI** (`app/src/main/cpp`): 메모리 해제 누락, 잘못된 JNI 참조 관리(Local/Global ref), 스레드 간 JNIEnv 오용, 버퍼 오버런
- **데이터 손실**: 세이브 폴더, 컨테이너, 백업·복원, `Download/.EZlator/` 경로를 잘못 지우거나 덮어쓰는 변경
- **호환성**: minSdk 26 / targetSdk 28 / arm64-v8a 전제를 깨는 변경. targetSdk는 앱 데이터 폴더의 실행 파일 실행 때문에 28로 고정이며, 올리라고 제안하지 마세요.
- **보안**: 비밀값·서명 키·토큰 커밋, Steam 로그인 정보 노출, 외부 입력으로 인한 경로 조작
- **라이선스**: 새 제3자 코드나 바이너리를 추가하면서 `THIRD_PARTY_NOTICES.md`나 `licenses/`를 갱신하지 않은 경우, LGPL 구성요소(PulseAudio, libsndfile, GNU libltdl)의 소스 제공 조건을 깨는 변경

### 지적하지 않을 것

- 이 PR이 바꾸지 않은 기존 코드의 문제 (꼭 필요하면 "기존 문제"라고 표시해 한 번만 언급)
- 저장소에 포함되지 않은 파일(`app/src/main/assets/wincomponents/`의 구성요소, release 서명 키)이 없다는 점
- 대규모 리팩터링 제안

### 형식

- 문제마다 파일·줄을 짚고, **무엇이 어떤 상황에서 잘못되는지**와 고치는 방향을 짧게 적습니다.
- 심각한 문제가 없으면 "심각한 문제 없음"이라고만 남겨 주세요. 같은 지적을 반복하지 마세요.
