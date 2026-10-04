package com.winlator.cmod.steam;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import java.io.File;

/**
 * Phase 2: The412Banner's SteamLite package (headless Steam agent steam.exe +
 * matched genuine Valve client DLLs, GPL-3.0 agent / Valve components).
 * Downloaded once as a separate component - never bundled in the APK - so the
 * GPL agent stays a separate program next to this app.
 * 2단계: The412Banner의 SteamLite 패키지(헤드리스 Steam 에이전트 steam.exe +
 * 짝이 맞는 Valve 정식 클라이언트 DLL. 에이전트는 GPL-3.0, 부품은 Valve 소유).
 * APK에 넣지 않고 별도 구성요소로 한 번만 받음 - GPL 에이전트를 이 앱과 분리된
 * 별도 프로그램으로 두기 위함
 *
 * Launch contract (the agent is derived from WinNative's launcher, same env):
 * 실행 규약(에이전트가 WinNative 실행기에서 파생돼 환경변수가 같음):
 *   "C:\Program Files (x86)\Steam\steam.exe" "<game exe>" [args]
 *   WN_STEAM_USERNAME / WN_STEAM_STEAMID / WN_STEAM_TOKEN / WN_STEAM_APPID
 *   PROTON_DISABLE_LSTEAMCLIENT=1, WINEDLLOVERRIDES += lsteamclient=
 */
public final class SteamLiteManager {
    private static final String TAG = "SteamLiteManager";
    public static final String URL =
            "https://github.com/The412Banner/winlator-contents/releases/download/steamlite-v1/steamlite.tzst";
    public static final String STEAM_DIR_WIN = "C:\\Program Files (x86)\\Steam";

    private SteamLiteManager() {}

    /** imagefs/opt/steamlite - shared by all containers / 모든 컨테이너가 공유함 */
    public static File getInstallDir(Context context) {
        return new File(context.getFilesDir(), "imagefs/opt/steamlite");
    }

    public static boolean isInstalled(Context context) {
        return new File(getInstallDir(context), "steam.exe").isFile();
    }

    /** Blocking download + extract. / 블로킹 다운로드 + 압축 해제 */
    public static boolean install(Context context) {
        File tmp = new File(context.getCacheDir(), "steamlite.tzst");
        try {
            if (!Downloader.downloadFile(URL, tmp)) return false;
            File dir = getInstallDir(context);
            FileUtils.delete(dir);
            dir.mkdirs();
            boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, tmp, dir);
            // Some archives wrap everything in one top folder; flatten it
            // 압축본이 최상위 폴더 하나로 감싸져 있으면 풀어서 평평하게 만듦
            if (ok && !new File(dir, "steam.exe").isFile()) {
                File[] kids = dir.listFiles();
                if (kids != null && kids.length == 1 && kids[0].isDirectory()
                        && new File(kids[0], "steam.exe").isFile()) {
                    File inner = kids[0];
                    File[] files = inner.listFiles();
                    if (files != null) for (File f : files) f.renameTo(new File(dir, f.getName()));
                    inner.delete();
                }
            }
            Log.i(TAG, "SteamLite installed=" + isInstalled(context));
            return isInstalled(context);
        } catch (Exception e) {
            Log.w(TAG, "SteamLite install failed", e);
            return false;
        } finally {
            tmp.delete();
        }
    }

    /**
     * Copies the package into the container's C:\Program Files (x86)\Steam,
     * where the agent expects to live. Skipped when already current (size of
     * steam.exe used as a cheap version check).
     * 패키지를 컨테이너의 C:\Program Files (x86)\Steam으로 복사함. 에이전트가
     * 있어야 하는 위치임. 이미 최신이면 건너뜀(steam.exe 크기로 간단히 비교)
     */
    public static boolean stageIntoContainer(Context context, File driveC) {
        File src = getInstallDir(context);
        File dst = new File(driveC, "Program Files (x86)/Steam");
        File srcExe = new File(src, "steam.exe"), dstExe = new File(dst, "steam.exe");
        if (!srcExe.isFile()) return false;
        if (dstExe.isFile() && dstExe.length() == srcExe.length()) return true;
        dst.mkdirs();
        copyTree(src, dst);
        return dstExe.isFile();
    }

    private static void copyTree(File src, File dst) {
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File t = new File(dst, f.getName());
            if (f.isDirectory()) { t.mkdirs(); copyTree(f, t); }
            else FileUtils.copy(f, t);
        }
    }

    /**
     * BUG FIX (agent-src main.cpp ~1447): the agent treats argv[1] as a SPEC FILE
     * whenever fopen() succeeds on it - and the game .exe we passed opens fine, so
     * the agent read the first line of the binary ("MZ...") as the game path. That
     * is why its log said "game not under steamapps\common" and no game ever
     * started. A real spec file is now written - line 1 = game exe (Windows path),
     * line 2 = appId - and passed as argv[1] (and WN_STEAM_GAMEEXE_FILE), exactly
     * like WinNative does.
     * 버그 수정(agent-src main.cpp 약 1447번째 줄): 에이전트는 argv[1]을 fopen()할
     * 수 있으면 무조건 SPEC 파일로 취급함 - 우리가 넘긴 게임 .exe는 당연히 열리므로,
     * 에이전트가 실행 파일의 첫 줄("MZ...")을 게임 경로로 읽었음. 에이전트 로그의
     * "game not under steamapps\common"과 게임이 끝내 안 뜬 원인이 이것임. 이제
     * 진짜 spec 파일(1줄 = 게임 exe Windows 경로, 2줄 = appId)을 만들어 argv[1]과
     * WN_STEAM_GAMEEXE_FILE로 넘김. WinNative와 같은 방식임
     *
     * @return Windows path of the spec file, or null on failure
     *         spec 파일의 Windows 경로, 실패하면 null
     */
    public static String writeSpecFile(File driveC, int appId, String gameExeWin) {
        try {
            File dir = new File(driveC, "ezlator_steam");
            dir.mkdirs();
            File spec = new File(dir, appId + ".spec");
            FileUtils.writeString(spec, gameExeWin + "\r\n" + appId + "\r\n");
            return "C:\\ezlator_steam\\" + appId + ".spec";
        } catch (Exception e) {
            Log.w(TAG, "spec file write failed", e);
            return null;
        }
    }

    /**
     * Adds the session + agent env for one launch. Returns false (and adds
     * nothing) when not signed in, so the game then starts without Steam.
     * 실행 1회분의 세션 + 에이전트 환경변수를 넣음. 로그인 안 돼 있으면 false를
     * 돌려주고 아무것도 넣지 않음 - 그러면 게임은 Steam 없이 그대로 실행됨
     */
    public static boolean applyEnv(Context context, EnvVars env, int appId) {
        SteamAccountStore store = new SteamAccountStore(context);
        if (!store.isLoggedIn() || appId <= 0) return false;
        env.put("WN_STEAM_USERNAME", store.getAccountName());
        env.put("WN_STEAM_STEAMID", String.valueOf(store.getSteamId64()));
        env.put("WN_STEAM_TOKEN", store.getRefreshToken());
        env.put("WN_STEAM_APPID", String.valueOf(appId));
        env.put("SteamAppId", String.valueOf(appId));
        env.put("SteamGameId", String.valueOf(appId));
        // Proton's own Steam bridge would hijack steamclient calls from the real
        // client the agent starts
        // Proton 자체 Steam 브리지가 에이전트가 띄운 정식 클라이언트의 호출을
        // 가로채지 않게 끔
        env.put("PROTON_DISABLE_LSTEAMCLIENT", "1");

        // WN_STEAM_DIRECT_EXE removed again: the real cause was the spec-file bug
        // (see writeSpecFile), and a Steam-owned (secure) launch is what DRM/VAC
        // titles need. The agent still falls back to a direct start on its own if
        // Steam refuses the launch.
        // WN_STEAM_DIRECT_EXE는 다시 뺌: 진짜 원인은 spec 파일 버그였고
        // (writeSpecFile 참고), DRM/VAC 게임에는 Steam이 직접 띄우는(보안) 실행이
        // 필요함. Steam이 실행을 거부하면 에이전트가 알아서 직접 실행으로 대체함

        // Name / build of the installed copy (from our appmanifest) so the agent
        // reports the right build instead of an unknown one
        // 설치본의 이름 / 빌드 번호(우리 appmanifest에서 읽음). 에이전트가 알 수 없는
        // 빌드가 아니라 맞는 빌드로 보고하게 함
        File acf = new File(SteamGameInstaller.getLibraryDir(context), "appmanifest_" + appId + ".acf");
        String name = SteamGameInstaller.readAcfValue(acf, "name");
        String build = SteamGameInstaller.readAcfValue(acf, "buildid");
        if (name != null) env.put("WN_STEAM_APP_NAME", name);
        if (build != null) env.put("WN_STEAM_BUILD_ID", build);
        // The agent rewrites appmanifest_<appid>.acf from these on every launch
        // (agent-src main.cpp stage_app_manifest); without them it would write an
        // EMPTY InstalledDepots. Formats per the agent source:
        //   WN_STEAM_DEPOTS        depotId:manifestGid:size,...
        //   WN_STEAM_SHARED_DEPOTS sourceDepotId:targetAppId,...
        // 에이전트는 실행할 때마다 이 값으로 appmanifest_<appid>.acf를 다시 씀
        // (agent-src main.cpp stage_app_manifest). 값이 없으면 InstalledDepots를
        // 비운 채로 써버림. 형식은 에이전트 소스 기준:
        //   WN_STEAM_DEPOTS        depotId:manifestGid:size,...
        //   WN_STEAM_SHARED_DEPOTS sourceDepotId:targetAppId,...
        String depots = SteamGameInstaller.readAcfDepots(acf);
        String shared = SteamGameInstaller.readAcfSharedDepots(acf);
        if (!depots.isEmpty()) env.put("WN_STEAM_DEPOTS", depots);
        if (!shared.isEmpty()) env.put("WN_STEAM_SHARED_DEPOTS", shared);
        String o = env.has("WINEDLLOVERRIDES") ? env.get("WINEDLLOVERRIDES") : "";
        if (!o.contains("lsteamclient=")) env.put("WINEDLLOVERRIDES", o.isEmpty() ? "lsteamclient=" : o + ";lsteamclient=");
        return true;
    }
}
