package com.winlator.cmod.steam;

import android.util.Log;

import java.security.Provider;
import java.security.Security;

/**
 * BUG FIX (logcat 2026-09-27: "no such algorithm: SHA-1 for provider BC").
 * JavaSteam asks for crypto by provider NAME "BC". On Android that name belongs
 * to the platform's stripped-down internal Bouncy Castle, which has no SHA-1
 * digest, and JavaSteam's own attempt to install the full one is refused
 * because the name is already taken. The first depot manifest check then
 * failed. The platform "BC" is swapped for the full Bouncy Castle that the app
 * already ships (org.bouncycastle:bcprov). It is appended at the END of the
 * provider list, so the app's default crypto (TLS etc.) is unaffected - only
 * lookups that ask for "BC" by name reach it.
 * 버그 수정(로그캣 2026-09-27: "no such algorithm: SHA-1 for provider BC").
 * JavaSteam은 암호 기능을 공급자 이름 "BC"로 찾음. 안드로이드에선 이 이름이
 * 플랫폼 내부의 축소판 Bouncy Castle 것이라 SHA-1 해시가 없고, JavaSteam이
 * 완전판을 설치하려 해도 이름이 이미 있어서 거부됨. 그래서 첫 depot 매니페스트
 * 확인에서 실패했음. 플랫폼 "BC"를 앱에 이미 들어 있는 완전판 Bouncy Castle
 * (org.bouncycastle:bcprov)로 바꿈. 공급자 목록 맨 뒤에 붙이므로 앱의 기본
 * 암호 기능(TLS 등)은 영향 없고, "BC"를 이름으로 찾는 호출만 여기로 옴
 */
public final class SteamCrypto {
    private static boolean done;
    // Android's own "BC" provider, kept so it can be put back after a download
    // 안드로이드 자체 "BC" 공급자. 다운로드가 끝나면 되돌리려고 보관함
    private static Provider platformBc;

    private SteamCrypto() {}

    /**
     * CHANGED (logcat 2026-09-27 20:43: log on disconnected within 1 s right after
     * the swap, "Steam log on failed: null"). The swap is now scoped to the depot
     * download only - sign-in and log on keep Android's own "BC", which worked -
     * and restore() puts it back afterwards.
     * 변경(로그캣 2026-09-27 20:43: 교체 직후 로그온이 1초 안에 끊기며 "Steam log on
     * failed: null"). 교체를 depot 다운로드 구간에만 적용함 - 로그인과 로그온은
     * 원래 잘 되던 안드로이드 자체 "BC"를 그대로 쓰고, 끝나면 restore()로 되돌림
     */
    public static synchronized void restore() {
        if (!done) return;
        try {
            Security.removeProvider("BC");
            if (platformBc != null) Security.addProvider(platformBc);
            Log.i("SteamCrypto", "Platform BC restored");
        } catch (Throwable t) {
            Log.e("SteamCrypto", "restore failed", t);
        }
        done = false;
    }

    public static synchronized void ensure() {
        if (done) return;
        try {
            Provider bc = Security.getProvider("BC");
            boolean full = bc != null
                    && "org.bouncycastle.jce.provider.BouncyCastleProvider".equals(bc.getClass().getName())
                    && bc.getService("MessageDigest", "SHA-1") != null;
            if (!full) {
                platformBc = bc;
                Security.removeProvider("BC");
                Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
                Log.i("SteamCrypto", "Full Bouncy Castle registered as BC (was "
                        + (bc != null ? bc.getClass().getName() : "none") + ")");
            }
            done = true;
        } catch (Throwable t) {
            Log.e("SteamCrypto", "provider setup failed", t);
        }
    }
}
