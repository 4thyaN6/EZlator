# EZlator 제3자 구성요소 목록 (THIRD_PARTY_NOTICES)

앱 내 크레딧(`MainActivity.showAboutDialog()`)과 이 파일은 같은 목록을 기준으로 함.
라이선스 표기는 저장소 안 파일로 확인한 것과 널리 알려진 프로젝트 라이선스만 적었음.
확인하지 못한 것은 3장 "결정 사항과 미결 사항"에 표기함.

## 1. APK에 포함되는 것

### 1-1. 앱 코드 기반
| 구성요소 | 라이선스 | 근거 |
|---|---|---|
| Winlator (brunodev85) 및 Cmod 포크 | MIT | 저장소 루트 `LICENSE` |

### 1-2. Java/Android 라이브러리 (`app/build.gradle`)
| 구성요소 | 버전 | 라이선스 |
|---|---|---|
| AndroidX appcompat / preference / activity / fragment / recyclerview / swiperefreshlayout 외 AndroidX 전이 라이브러리 | 1.4.0 / 1.2.1 / 1.5.1 / 1.4.0 / 1.2.0 / 1.1.0 (실제 결정 버전) | Apache-2.0 |
| Material Components | 1.6.1 | Apache-2.0 |
| FlexboxLayout | 3.0.0 | Apache-2.0 |
| JavaSteam, javasteam-depotdownloader | 1.8.0 | MIT |
| Bouncy Castle (bcprov-jdk15on) | 1.68 | Bouncy Castle License (MIT 계열) |
| ZXing core | 3.5.3 | Apache-2.0 |
| Apache Commons Compress | 1.20 | Apache-2.0 |
| zstd-jni | 1.5.2-3 | BSD-2-Clause |
| XZ for Java | 1.7 | Public domain |
| MidiSynth (`libs/MidiSynth/MidiSynth.jar`) | - | Apache-2.0 (`libs/MidiSynth/LICENSE.txt`) |

전이 의존성 (`gradlew :app:dependencies --configuration releaseRuntimeClasspath` 결과, 2026-09-30, 총 121개 중 AndroidX 외):

| 구성요소 | 버전 | 라이선스 | 가져오는 곳 |
|---|---|---|---|
| Kotlin 표준 라이브러리 (kotlin-stdlib, -jdk7, -jdk8) | 2.2.21 | Apache-2.0 | JavaSteam |
| kotlinx-coroutines (core, android, slf4j) | 1.10.2 | Apache-2.0 | JavaSteam |
| kotlinx-serialization (core, json) | 1.9.0 | Apache-2.0 | JavaSteam |
| kotlinx-io (core, bytestring) | 0.8.0 | Apache-2.0 | Ktor |
| Ktor client (core, cio, websockets, http, network, tls 등) | 3.3.3 | Apache-2.0 | JavaSteam |
| OkHttp (okhttp, okhttp-android, okhttp-coroutines) | 5.3.2 | Apache-2.0 | JavaSteam |
| Okio | 3.16.4 | Apache-2.0 | OkHttp |
| Protocol Buffers (protobuf-java) | 4.31.1 | BSD-3-Clause | JavaSteam |
| Apache Commons Lang | 3.20.0 | Apache-2.0 | JavaSteam |
| SLF4J API | 2.0.17 | MIT | kotlinx-coroutines-slf4j |
| JetBrains annotations | 23.0.0 | Apache-2.0 | Kotlin |
| Guava listenablefuture | 1.0 | Apache-2.0 | AndroidX |

### 1-2-1. 아이콘·그림
| 구성요소 | 라이선스 | 위치 |
|---|---|---|
| Material Design Icons 경로 2개 ("cloud_download", "refresh") | Apache-2.0 | `res/drawable/icon_steam_library.xml`, `res/drawable/icon_refresh.xml` |
| 앱 아이콘 (네온 조이패드 알약) | EZlator 자체 제작 (앱 코드와 같은 MIT) | `res/mipmap-*/ic_launcher*`, `res/drawable-nodpi/ic_app_logo.png`, `branding/` |

스팀 게임 표지와 프로필 사진은 APK에 들어 있지 않음. 앱이 실행 중에 Steam에서 받아 기기 캐시에만 저장함.

### 1-3. 네이티브 라이브러리
| 구성요소 | 위치 | 라이선스 |
|---|---|---|
| SDL2 (hidapi 포함) | `app/src/main/cpp/sdl2/` 소스로 빌드 | zlib (`sdl2/LICENSE.txt`), hidapi는 BSD/GPL-3/원본 중 선택 |
| PulseAudio 13.0 (libpulse, libpulsecommon, libpulsecore, libpulseaudio) | `app/src/main/jniLibs/arm64-v8a/` | LGPL-2.1+ |
| libsndfile | 같은 위치 | LGPL-2.1+ |
| GNU libltdl | 같은 위치 | LGPL-2.1+ |
| 앱 자체 네이티브 코드 (winlator, evshim) | `app/src/main/cpp/winlator/` | 앱 라이선스(MIT) |

LGPL 라이브러리는 라이선스 전문 제공과 해당 버전 소스 코드를 받을 수 있는 경로 안내가 필요함.

### 1-4. assets 안의 바이너리 (압축을 풀어 파일 정보로 확인함)
| 파일 | 내용 | 라이선스 |
|---|---|---|
| `box86_64/box64-*.tzst`, `wowbox64/*.tzst` | Box64, WOWBox64 | MIT |
| `wincomponents/opengl.tzst` | Mesa gallium WGL | MIT |
| `wincomponents/ddraw.tzst`, `ddrawrapper/cnc-ddraw.tzst` | cnc-ddraw (FunkyFr3sh) | MIT |
| `ddrawrapper/dd7to9.tzst` 의 `ddraw.dll` | DxWrapper Stub (Elisha Riedlinger) | zlib |
| `graphics_driver/extra_libs.tzst` | Mesa freedreno/libGL, vkBasalt | MIT, zlib |
| `graphics_driver/wrapper*.tzst` 의 `libadrenotools.so` | libadrenotools | BSD-2-Clause |
| `pulseaudio.tzst` | PulseAudio 모듈 | LGPL-2.1+ |
| `soundfonts/wt_210k_G.sf2` | SONiVOX EAS GM Wavetable, © 1993 Sonic Network, Inc. | CC BY 3.0 (`wt_210k_G_LICENSE.txt`) |

## 2. 실행 중에 내려받는 것 (APK에는 없음)
| 구성요소 | 받는 곳 | 라이선스 |
|---|---|---|
| imagefs (Ubuntu Focal 기반 rootfs) | 외부 배포처 | 구성 패키지별 (GPL/LGPL 등 혼재) |
| Proton / Wine | GameNative, The412Banner 등 | LGPL-2.1+ |
| DXVK (D8VK 포함) | 카탈로그 | zlib |
| VKD3D-Proton | 카탈로그 | LGPL-2.1 |
| FEXCore | 카탈로그 | MIT |
| Turnip 드라이버 | StevenMXZ, The412Banner | MIT (Mesa) |
| Gothic A1 (HanYang I&C) | Google Fonts 저장소(github.com/google/fonts, `ofl/gothica1`). 앱에 담지 않고 실행 중 내려받음. 기기에서 이름을 바꾼 대체 글꼴 `malgun.ttf`, `malgunbd.ttf`(맑은 고딕)을 만드는 원본 | SIL OFL 1.1 |
| 고운돋움 Gowun Dodum (The Gowun Dodum Project Authors) | Google Fonts 저장소(`ofl/gowundodum`). 실행 중 내려받음. 대체 글꼴 `gulim.ttc`(굴림·굴림체·돋움·돋움체)의 원본 | SIL OFL 1.1 |
| 고운바탕 Gowun Batang (The Gowun Batang Project Authors) | Google Fonts 저장소(`ofl/gowunbatang`). 실행 중 내려받음. 대체 글꼴 `batang.ttc`(바탕·바탕체·궁서·궁서체)의 원본 | SIL OFL 1.1 |
| Noto Sans KR, Noto Serif KR (Adobe) | Google Fonts 저장소(`ofl/notosanskr`, `ofl/notoserifkr`, 가변 글꼴). 실행 중 내려받음. 위 원본에 없는 한자·가나·CJK 기호를 대체 글꼴에 채워 넣는 데 씀 | SIL OFL 1.1 (Noto Sans KR: Reserved Font Name "Source") |
| 위 대체 글꼴 4종 | 앱(`KoreanFontBuilder`)이 기기 안에서 만들고 배포하지 않음. 원본 글꼴을 합치고 글꼴 이름을 Windows 한글 글꼴 이름으로 바꾼 수정본(Modified Version)이며, 원본의 저작권(name ID 0)·라이선스(name ID 13, 14) 기록을 그대로 남기고 Microsoft 글꼴이 아님을 설명(name ID 10)에 적음. 원본의 Reserved Font Name(Source)은 쓰지 않음 | SIL OFL 1.1 |

## 3. 결정 사항과 미결 사항

1. **nGlide, 3dfx 스플래시 DLL** — 결정: 포크 원본(Winlator Cmod)의 배포 방식을 따름.
2. **`libvulkan_wrapper.so` 및 hook 라이브러리** — 결정: 포크 원본(Winlator Cmod)의 배포 방식을 따름.
3. **`libutil_layer.so`** — 결정: 포크 원본(Winlator Cmod)의 배포 방식을 따름.
4. **LGPL 소스 제공** — 결정: APK와 같은 GitHub Release에 `EZlator-<버전>-lgpl-sources.zip`을 첨부함. 라이선스 원문은 `licenses/LGPL-2.1.txt`, 안내는 `licenses/README.md`. APK에 들어가는 LGPL 구성요소와 버전:
   - PulseAudio 13.0 (`libpulse`, `libpulsecommon-13.0`, `libpulsecore-13.0`, `libpulseaudio`, `pulseaudio.tzst`의 모듈), 빌드 출처 brunodev85/pulseaudio-android
   - libsndfile 1.0.28, 빌드 출처 brunodev85/pulseaudio-android의 `libsndfile` 폴더
   - GNU libltdl 2.4.3a (libtool), 빌드 출처 brunodev85/pulseaudio-android의 `libtool` 폴더

