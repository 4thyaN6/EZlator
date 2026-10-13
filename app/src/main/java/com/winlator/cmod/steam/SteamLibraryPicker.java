package com.winlator.cmod.steam;

/**
 * Which Steam library a new download goes to, and how much room is missing.
 * Internal storage (D:) is the default; the SD card (E:) only when the user
 * chose it in Settings. Nothing is switched by itself: when the chosen library
 * is short of space the user decides (close / delete games / move games, see
 * SteamSpaceCheck). An update always stays in the library the game is in.
 * Pure Java (no Android classes) so it can be tested off the device.
 * 새 다운로드를 넣을 스팀 라이브러리와 모자란 공간을 정함. 기본은 내장
 * 메모리(D:)이고, SD카드(E:)는 사용자가 설정에서 골랐을 때만 씀. 저절로 바꾸지 않음:
 * 고른 라이브러리의 공간이 모자라면 사용자가 정함(닫기 / 게임 삭제 / 게임 옮기기,
 * SteamSpaceCheck 참고). 업데이트는 항상 게임이 있는 라이브러리에 남음.
 * 안드로이드 클래스를 쓰지 않는 순수 Java라 기기 밖에서 시험할 수 있음
 */
public final class SteamLibraryPicker {
    private SteamLibraryPicker() {}

    public static final int INTERNAL = 0;
    public static final int SD = 1;

    // Room kept free besides the game (Wine, shader caches, saves need space too)
    // 게임 외에 남겨 둘 여유 공간(Wine, 셰이더 캐시, 세이브도 공간이 필요함)
    public static final long SPARE_BYTES = 512L * 1024 * 1024;

    /**
     * Library a new download goes to: the SD card only when the user chose it
     * and a card is there, otherwise internal storage.
     * 새 다운로드를 넣을 라이브러리: 사용자가 골랐고 카드가 있을 때만 SD카드, 아니면 내장
     */
    public static int preferred(boolean sdChosen, boolean sdAvailable) {
        return sdChosen && sdAvailable ? SD : INTERNAL;
    }

    /**
     * Bytes still to free so that needed (plus SPARE_BYTES) fits into free;
     * 0 when it fits or the size is unknown (needed 0 or less).
     * needed(+SPARE_BYTES)가 free에 들어가려면 더 비워야 할 바이트. 들어가거나 크기를
     * 모르면(needed 0 이하) 0
     */
    public static long shortfall(long free, long needed) {
        if (needed <= 0) return 0;
        long missing = needed + SPARE_BYTES - free;
        return missing > 0 ? missing : 0;
    }
}
