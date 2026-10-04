package com.winlator.cmod.core;

import java.util.Locale;

/**
 * Guest locale environment normalization.
 *
 * The imagefs ships no locale data, so asking for a real locale (e.g. ko_KR.UTF-8) makes
 * setlocale() fail and fall back to plain "C", which is ASCII-only and mangles every
 * non-ASCII path on the wine command line. LC_ALL is therefore pinned to C.UTF-8, which
 * is always available, and the user's locale goes to LANG instead.
 *
 * 게스트 로케일 환경변수 정규화.
 *
 * imagefs에는 로케일 데이터가 들어 있지 않다. 따라서 ko_KR.UTF-8 같은 실제 로케일을 요청하면
 * setlocale()이 실패하고 ASCII 전용인 "C"로 떨어져, wine 명령줄의 비ASCII 경로가 전부 깨진다.
 * 그래서 LC_ALL은 항상 존재하는 C.UTF-8로 고정하고, 사용자가 고른 로케일은 LANG으로 넘긴다.
 */
public final class LocaleEnv {
    private LocaleEnv() {}

    // Always C.UTF-8 — the only UTF-8 locale guaranteed to exist inside imagefs.
    // 항상 C.UTF-8. imagefs 안에 확실히 존재하는 유일한 UTF-8 로케일이다.
    public static String normalize() {
        return "C.UTF-8";
    }

    // User-chosen locale for LANG. Falls back to the device locale when unset.
    // LANG에 넣을 사용자 선택 로케일. 값이 없으면 기기 로케일에서 유도한다.
    public static String normalizeLang(String stored) {
        if (stored != null && !stored.isEmpty()) {
            return stored.indexOf('.') >= 0 ? stored : stored + ".UTF-8";
        }
        return deriveFromDevice();
    }

    private static String deriveFromDevice() {
        Locale locale = Locale.getDefault();
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if (lang == null || lang.isEmpty() || country == null || country.isEmpty()) {
            return "C.UTF-8";
        }
        return lang + "_" + country + ".UTF-8";   // e.g. ko_KR.UTF-8
    }
}