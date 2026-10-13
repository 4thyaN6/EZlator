package com.winlator.cmod.steam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

// Steam update check for an installed game: the build id in its
// appmanifest_<appid>.acf vs the current public build id on Steam. Remote ids
// are remembered for 15 minutes so opening the detail sheet again does not
// sign in to Steam every time.
// 설치된 게임의 스팀 업데이트 확인임: appmanifest_<appid>.acf의 빌드 id와
// 스팀의 현재 public 빌드 id를 비교함. 원격 id는 15분 동안 기억해서 상세창을 다시
// 열 때마다 스팀에 로그인하지 않게 함
public final class SteamUpdateChecker {
    private static final long CACHE_MS = 15 * 60 * 1000L;
    private static final Map<Integer, String> remoteBuild = new HashMap<>();
    private static final Map<Integer, Long> remoteAt = new HashMap<>();

    public interface Callback {
        // known=false: could not be checked (signed out, offline, no record)
        // known=false: 확인하지 못함(로그아웃, 오프라인, 설치 기록 없음)
        void onResult(boolean updateAvailable, boolean known);
    }

    private SteamUpdateChecker() {}

    // Result arrives on the main thread; false on any error or when signed out
    // 결과는 메인 스레드로 옴. 오류가 나거나 로그아웃 상태면 false
    public static void check(Context context, int appId, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean available = false;
            boolean known = false;
            try {
                // Internal or SD card library / 내장 또는 SD카드 라이브러리
                File acf = SteamGameInstaller.acfOf(app, appId);
                String local = acf.isFile() ? SteamGameInstaller.readAcfValue(acf, "buildid") : null;
                if (local != null && !local.isEmpty()) {
                    String remote;
                    synchronized (remoteBuild) {
                        Long at = remoteAt.get(appId);
                        remote = (at != null && System.currentTimeMillis() - at < CACHE_MS) ? remoteBuild.get(appId) : null;
                    }
                    if (remote == null) {
                        SteamSession session = SteamFragment.sharedSession(app);
                        if (session != null) {
                            remote = session.getAppDetails(appId).buildId;
                            synchronized (remoteBuild) {
                                remoteBuild.put(appId, remote);
                                remoteAt.put(appId, System.currentTimeMillis());
                            }
                        }
                    }
                    known = remote != null && !remote.isEmpty() && !"0".equals(remote);
                    available = known && !remote.equals(local);
                }
            } catch (Exception e) {
                Log.w("SteamUpdateChecker", "update check failed for " + appId, e);
            }
            final boolean result = available;
            final boolean isKnown = known;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result, isKnown));
        }, "SteamUpdateCheck").start();
    }

    // After an update the stored remote id is still right; forget it anyway so
    // the next check re-reads both sides
    // 업데이트 뒤에도 저장된 원격 id는 맞지만, 다음 확인 때 양쪽을 다시 읽도록 지움
    public static void forget(int appId) {
        synchronized (remoteBuild) {
            remoteBuild.remove(appId);
            remoteAt.remove(appId);
        }
    }
}
