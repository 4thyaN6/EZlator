package com.winlator.cmod.steam;

import java.util.Locale;

/**
 * Wine/Proton builds the Steam agent cannot run on. Containers with these
 * builds are hidden wherever a Steam game picks a container (new Steam
 * installs, the container list in a Steam shortcut's settings).
 * Pure Java (no Android classes) so it can be tested off the device.
 * 스팀 에이전트가 동작하지 않는 Wine/Proton 빌드. 스팀 게임이 컨테이너를 고르는
 * 곳(새 스팀 설치, 스팀 바로가기 설정의 컨테이너 목록)에서 이 빌드의 컨테이너를 숨김.
 * 안드로이드 클래스를 쓰지 않는 순수 Java라 기기 밖에서 시험할 수 있음
 */
public final class SteamWineSupport {
    private SteamWineSupport() {}

    // Proton 10.0-4: Valve's steamclient64.dll fails in its DllMain
    // (GetLastError 998) on both the arm64ec build (FEXCore 2605 and 2609) and
    // the x86_64 build (Box64), so the agent ends before the game starts. The
    // same agent and DLL work on Proton 9.0 and 11.0-2 (device check 2026-10-13)
    // Proton 10.0-4: Valve steamclient64.dll이 DllMain에서 실패함(GetLastError 998).
    // arm64ec 빌드(FEXCore 2605, 2609)와 x86_64 빌드(Box64) 모두 같아서 게임이 뜨기
    // 전에 에이전트가 끝남. 같은 에이전트와 DLL이 Proton 9.0, 11.0-2에서는 동작함
    // (2026-10-13 기기 확인)
    private static final String[] BLOCKED = {"10.0-4"};

    /**
     * True when the Steam agent does not work with this Wine/Proton identifier
     * (e.g. "Proton-10.0-4-arm64ec-6"). Null or empty is treated as usable.
     * 이 Wine/Proton 식별자(예: "Proton-10.0-4-arm64ec-6")에서 스팀 에이전트가
     * 동작하지 않으면 true. null이나 빈 값은 쓸 수 있는 것으로 봄
     */
    public static boolean isBlocked(String wineVersion) {
        if (wineVersion == null || wineVersion.isEmpty()) return false;
        String v = wineVersion.toLowerCase(Locale.ENGLISH);
        for (String b : BLOCKED) if (v.contains(b)) return true;
        return false;
    }
}
