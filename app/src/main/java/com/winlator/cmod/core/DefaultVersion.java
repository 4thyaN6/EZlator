package com.winlator.cmod.core;

// Default versions used when a container/shortcut hasn't customized its
// DXVK/VKD3D/FEXCore settings yet. These are the ARM64EC-path defaults;
// the architecture auto-selection logic (Container Detail screen) overrides
// these per Wine architecture at container-creation time.
// 컨테이너/바로가기가 커스텀 안 했을 때 쓰이는 기본값. ARM64EC 경로 기준이며,
// 아키텍처 자동선택 로직이 컨테이너 생성 시 실제 값을 덮어씀
public abstract class DefaultVersion {
    // BOX86 / BOX64 resolve to assets/box86_64/box8(6|4)-<value>.tzst, so they must
    // name a file that is actually bundled. "0.4.3" was not, so the extraction
    // silently did nothing on the non-arm64ec path.
    // BOX86 / BOX64는 assets/box86_64/box8(6|4)-<값>.tzst 로 연결되므로 실제로
    // 번들된 파일명이어야 함. "0.4.3"은 번들에 없어서 비-arm64ec 경로에서
    // 압축 해제가 조용히 실패하고 있었음
    public static final String BOX86 = "0.3.8";
    public static final String BOX64 = "0.3.7";

    // FEXCORE / DXVK / VKD3D are content-profile identifiers in "<verName>-<verCode>"
    // form, matched by ContentsManager.getProfileByEntryName(). They must match a
    // profile installed by the recommended-contents download on first run.
    // If a mismatch happens anyway, extractDxvkFiles()/extractVkd3dFiles() fall back
    // to the first installed profile of the right type instead of installing nothing.
    // FEXCORE / DXVK / VKD3D는 "<verName>-<verCode>" 형식의 컨텐츠 프로필
    // 식별자이며 ContentsManager.getProfileByEntryName()이 이 형식으로 조회함.
    // 첫 실행 시 추천 구성요소 다운로드로 설치되는 프로필과 일치해야 함.
    // 그래도 어긋나면 extractDxvkFiles()/extractVkd3dFiles()가 아무것도 설치하지
    // 않는 대신 같은 타입의 첫 번째 설치 프로필로 폴백함
    public static final String FEXCORE = "2609-0";
    public static final String WRAPPER = "System";
    public static final String DXVK = "3.0.2-arm64ec-gplasync-0";
    public static final String D8VK = "1.0";
    public static final String VKD3D = "3.0.1-arm64ec-sm69-0";
}