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
 *   optional: WN_STEAM_VAC=0 (no VAC), WN_STEAM_LANGUAGE (game language)
 *   선택: WN_STEAM_VAC=0(VAC 없음), WN_STEAM_LANGUAGE(게임 언어)
 *
 * The agent itself runs the redistributable installers it finds in the game
 * folder (vc_redist, DXSETUP, PhysX ...) silently on the first launch and keeps
 * a list in C:\wn-installed-redists.txt (agent-src main.cpp scan_and_install_redists).
 * 에이전트는 첫 실행 때 게임 폴더에서 찾은 재배포 설치 파일(vc_redist, DXSETUP,
 * PhysX 등)을 직접 조용히 실행하고 목록을 C:\wn-installed-redists.txt에 남김
 * (agent-src main.cpp scan_and_install_redists)
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

    // Parts the agent needs next to it (checked against the package's own list,
    // VALVE_COMPONENTS.txt: 64-bit and 32-bit client, tier0, vstdlib)
    // 에이전트 옆에 있어야 하는 부품(패키지 자체 목록 VALVE_COMPONENTS.txt 기준:
    // 64비트·32비트 클라이언트, tier0, vstdlib)
    static final String[] REQUIRED_FILES = {
            "steam.exe", "steamclient64.dll", "steamclient.dll",
            "tier0_s64.dll", "tier0_s.dll", "vstdlib_s64.dll", "vstdlib_s.dll"
    };
    // Package version file / 패키지 버전 파일
    private static final String VERSION_FILE = "steamlite.version";

    public static boolean isInstalled(Context context) {
        return missingParts(getInstallDir(context)).isEmpty();
    }

    // Names from REQUIRED_FILES that are not in dir / dir에 없는 REQUIRED_FILES 이름
    static java.util.List<String> missingParts(File dir) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String name : REQUIRED_FILES) if (!new File(dir, name).isFile()) missing.add(name);
        return missing;
    }

    // "version=" line of steamlite.version, or "" / steamlite.version의 "version=" 줄, 없으면 ""
    static String packageVersion(File dir) {
        // A container that has no copy yet has no version file
        // 아직 복사본이 없는 컨테이너에는 버전 파일이 없음
        File f = new File(dir, VERSION_FILE);
        if (!f.isFile()) return "";
        String text = FileUtils.readString(f);
        if (text == null) return "";
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("version=")) return line.substring(8).trim();
        }
        return "";
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
            // Package check (what the agent needs) / 패키지 점검(에이전트에 필요한 것)
            java.util.List<String> missing = missingParts(dir);
            Log.i(TAG, "SteamLite installed: version=" + packageVersion(dir)
                    + (missing.isEmpty() ? ", all parts present" : ", missing " + missing));
            return missing.isEmpty();
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
        // Same package version and every part in place: nothing to copy. The size
        // of steam.exe is only the fallback for a package without a version file
        // 패키지 버전이 같고 부품이 다 있으면 복사하지 않음. steam.exe 크기 비교는
        // 버전 파일이 없는 패키지에만 씀
        String srcVer = packageVersion(src);
        boolean same = !srcVer.isEmpty() ? srcVer.equals(packageVersion(dst))
                : dstExe.isFile() && dstExe.length() == srcExe.length();
        if (same && missingParts(dst).isEmpty()) return true;
        dst.mkdirs();
        copyTree(src, dst);
        java.util.List<String> missing = missingParts(dst);
        Log.i(TAG, "SteamLite staged into container: version=" + srcVer
                + (missing.isEmpty() ? ", all parts present" : ", missing " + missing));
        return missing.isEmpty();
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
        return applyEnv(context, env, appId, null, null, null);
    }

    /**
     * Same, plus what was read from the app info when the game was downloaded
     * (shortcut extras, see launchInfoExtras; null for older shortcuts):
     *  - steamVac "0": the game needs no VAC-secure launch, so the agent starts
     *    it directly after ~15 s instead of ~60 s when Steam does not spawn it
     *    (WN_STEAM_VAC=0, agent p3b). "1" or unknown keeps the secure window
     *  - steamLangs / steamLangDepots: the device language is passed as the
     *    game's Steam language (WN_STEAM_LANGUAGE) only when the game supports it
     *    and has no language-only depots (otherwise Steam could want a download)
     * 같은 일에 더해, 게임을 받을 때 앱 정보에서 읽은 값(바로가기 extra,
     * launchInfoExtras 참고. 옛 바로가기는 null)을 씀:
     *  - steamVac "0": VAC 보안 실행이 필요 없는 게임이라, 스팀이 게임을 띄우지 않을 때
     *    에이전트가 약 60초 대신 약 15초 뒤 바로 실행함(WN_STEAM_VAC=0, 에이전트 p3b).
     *    "1"이거나 모르면 보안 대기 시간을 그대로 둠
     *  - steamLangs / steamLangDepots: 게임이 기기 언어를 지원하고 언어 전용 depot이
     *    없을 때만 기기 언어를 게임의 스팀 언어로 넘김(WN_STEAM_LANGUAGE). 언어 전용
     *    depot이 있으면 스팀이 받기를 원할 수 있음
     */
    public static boolean applyEnv(Context context, EnvVars env, int appId,
                                   String vacExtra, String langsExtra, String langDepotsExtra) {
        SteamAccountStore store = new SteamAccountStore(context);
        if (!store.isLoggedIn() || appId <= 0) return false;
        if ("0".equals(vacExtra)) env.put("WN_STEAM_VAC", "0");
        String lang = gameLanguage(SteamAchievements.steamLanguage(), langsExtra, "1".equals(langDepotsExtra));
        if (lang != null) env.put("WN_STEAM_LANGUAGE", lang);
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
        // Internal or SD card library, wherever the game is
        // 게임이 있는 라이브러리(내장 또는 SD카드)
        File acf = SteamGameInstaller.acfOf(context, appId);
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

    /**
     * Steam language to pass for a game: the device's Steam language when the
     * game lists it and has no language-only depots; null otherwise (the agent
     * then uses "english", as before).
     * 게임에 넘길 스팀 언어: 게임 목록에 기기의 스팀 언어가 있고 언어 전용 depot이
     * 없으면 그 언어. 아니면 null(그러면 에이전트가 예전처럼 "english"를 씀)
     */
    static String gameLanguage(String deviceLang, String langsCsv, boolean languageDepots) {
        if (deviceLang == null || deviceLang.isEmpty() || "english".equals(deviceLang)) return null;
        if (languageDepots || langsCsv == null || langsCsv.isEmpty()) return null;
        for (String l : langsCsv.split(",")) if (l.trim().equals(deviceLang)) return deviceLang;
        return null;
    }

    /**
     * Shortcut extras with what the launch needs from the app info (read once,
     * when the game is downloaded): "steamVac=0|1", "steamLangs=a,b",
     * "steamLangDepots=0|1", each on its own line. Unknown values are left out.
     * 실행에 필요한 앱 정보 값을 담은 바로가기 extra(게임을 받을 때 한 번 읽음):
     * "steamVac=0|1", "steamLangs=a,b", "steamLangDepots=0|1"을 한 줄씩 씀. 모르는
     * 값은 넣지 않음
     */
    public static String launchInfoExtras(SteamSession.AppDetails app) {
        StringBuilder sb = new StringBuilder();
        if (app.vac != null) sb.append("steamVac=").append(app.vac ? "1" : "0").append('\n');
        if (!app.languages.isEmpty()) sb.append("steamLangs=").append(String.join(",", app.languages)).append('\n');
        sb.append("steamLangDepots=").append(app.languageDepots ? "1" : "0").append('\n');
        return sb.toString();
    }

    /**
     * How a Steam game is started. AGENT: through the SteamLite agent (the
     * default for games with a Steam AppID while signed in). DIRECT: the game
     * exe on its own, without Steam (not signed in, sign-in expired, agent
     * missing, or chosen after the agent launch ended at once).
     * 스팀 게임을 시작하는 방식. AGENT: SteamLite 에이전트를 거침(로그인 상태에서
     * Steam AppID가 있는 게임의 기본값). DIRECT: 스팀 없이 게임 exe만 실행(로그인
     * 안 됨, 로그인 만료, 에이전트 없음, 또는 에이전트 실행이 바로 끝난 뒤 고름)
     */
    public enum LaunchMode { AGENT, DIRECT }

    // Intent extra: start this one launch without Steam / 인텐트 extra: 이번 한 번은 스팀 없이 실행
    public static final String EXTRA_STEAM_DIRECT = "steam_direct";

    /**
     * Picks the launch mode. reasonOut[0] gets a short reason for the log.
     * 실행 방식을 고름. reasonOut[0]에 로그용 짧은 이유를 넣음
     */
    public static LaunchMode pickMode(Context context, int appId, boolean directRequested, String[] reasonOut) {
        String reason;
        LaunchMode mode;
        if (appId <= 0) { mode = LaunchMode.DIRECT; reason = "no Steam AppID"; }
        else if (directRequested) { mode = LaunchMode.DIRECT; reason = "chosen by the user for this launch"; }
        else if (!isInstalled(context)) { mode = LaunchMode.DIRECT; reason = "SteamLite not installed"; }
        else if (!new SteamAccountStore(context).isLoggedIn()) { mode = LaunchMode.DIRECT; reason = "not signed in"; }
        else { mode = LaunchMode.AGENT; reason = "signed in, agent installed"; }
        if (reasonOut != null && reasonOut.length > 0) reasonOut[0] = reason;
        return mode;
    }

    /**
     * Private values of the signed-in account, for LogScrubber: token, account
     * name, SteamID.
     * LogScrubber에 넘길 로그인 계정의 개인 값: 토큰, 계정 이름, SteamID
     */
    public static java.util.List<String> secrets(Context context) {
        SteamAccountStore store = new SteamAccountStore(context);
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(store.getRefreshToken());
        list.add(store.getAccountName());
        long id = store.getSteamId64();
        if (id > 0) list.add(String.valueOf(id));
        return list;
    }
}
