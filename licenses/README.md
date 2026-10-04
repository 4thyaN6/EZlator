# LGPL 구성요소와 소스 제공

EZlator의 APK에는 GNU Lesser General Public License(LGPL) 2.1 이상으로 배포되는 구성요소가 들어 있습니다. 이 폴더와 GitHub Release로 LGPL 2.1이 요구하는 라이선스 전문과 소스 코드를 제공합니다.

## 1. 포함된 LGPL 구성요소

| 구성요소 | 버전 | APK 안의 위치 | 라이선스 |
|---|---|---|---|
| PulseAudio (libpulse, libpulsecommon, libpulsecore, libpulseaudio) | 13.0 | `lib/arm64-v8a/` | LGPL-2.1+ |
| PulseAudio 모듈 (module-native-protocol-unix, libprotocol-native) | 13.0 | `assets/pulseaudio.tzst` | LGPL-2.1+ |
| libsndfile | 1.0.28 | `lib/arm64-v8a/` | LGPL-2.1+ |
| GNU libltdl (libtool) | 2.4.3a | `lib/arm64-v8a/` | LGPL-2.1+ |

- 위 구성요소는 모두 원본 Winlator의 [brunodev85/pulseaudio-android](https://github.com/brunodev85/pulseaudio-android) 저장소(`libsndfile`, `libtool`, `pulseaudio-module`, 빌드 스크립트)로 빌드된 것입니다.
- PulseAudio는 공식 저장소의 v13.0 태그 커밋(`200618b32f0964a479d69c9b6e5073e6931c370a`)을 기반으로 빌드되었습니다.
- 빌드 도구: Android NDK r21e (clang 9.0.9)
- `assets/pulseaudio.tzst`의 `module-aaudio-sink`는 같은 저장소의 `pulseaudio-module` 폴더에서 빌드되었습니다.

## 2. 라이선스 전문

- `LGPL-2.1.txt`: GNU Lesser General Public License 2.1 원문입니다.
- 원문은 https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt 의 내용을 바꾸지 않고 그대로 넣은 것입니다.

## 3. 소스 코드 제공 방식

- APK를 올리는 **같은 GitHub Release**에 `EZlator-<버전>-lgpl-sources.zip`을 함께 첨부합니다.
- 이 압축 파일에는 다음이 들어갑니다.
  - `pulseaudio-android/`: brunodev85/pulseaudio-android 저장소 전체(libsndfile, libtool, pulseaudio-module, 빌드 스크립트)
  - `pulseaudio-13.0.tar.xz`: PulseAudio 13.0 공식 배포본
  - `LGPL-2.1.txt`: 라이선스 원문
  - `SOURCES.txt`: 구성요소별 버전, 출처 주소, 저장소 커밋, 배포본 해시
- 바이너리와 같은 곳에서 같은 조건으로 소스를 받을 수 있게 하는 방식입니다(LGPL 2.1 제6조의 "같은 장소에서 동등한 접근" 방식).

## 4. 교체(재링크) 가능성

- LGPL 구성요소는 모두 별도의 공유 라이브러리(.so) 또는 별도 파일로 들어 있으며, 앱 코드와 정적으로 합쳐져 있지 않습니다.
- 앱 코드 전체가 이 저장소에 MIT로 공개되어 있으므로, 사용자는 수정한 라이브러리로 교체해서 APK를 다시 빌드할 수 있습니다.

## 5. 남은 확인 사항

- PulseAudio 바이너리의 버전 문자열은 `13.0-dirty`입니다. 빌드 당시 v13.0 태그 커밋 위에 커밋되지 않은 수정이 있었다는 뜻입니다.
- pulseaudio-android 저장소의 빌드 스크립트(`build.sh`, `main-build.sh`)에는 PulseAudio 소스를 고치는 명령(`sed -i`, `patch`, `git apply`)이 없습니다. 빌드 결과 라이브러리를 복사하는 줄(`main-build.sh` 79·80번째 줄)만 있습니다. 따라서 그 수정 내용은 공개된 저장소에 포함되어 있지 않습니다.
- 이 수정 내용이 필요하시면 저장소 이슈로 요청해 주십시오. 원 빌드 작성자(brunodev85)에게 확인하거나, 공개된 v13.0 소스로 같은 구성의 라이브러리를 다시 빌드해 교체하는 방법으로 대응합니다.
