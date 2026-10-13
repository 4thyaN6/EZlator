package com.winlator.cmod.container;

import java.util.List;
import java.util.Locale;

/**
 * Save archive layouts used by other Android Windows-game apps, so saves can
 * move both ways (users can leave, and come back). Only the layouts are
 * followed; no code is taken from those apps.
 *   WINLATOR  zip, entries "drive_c/users/xuser/..." (Winlator, WinNative,
 *             Bannerlator, DroidDeck read this; they replace the user folder
 *             name with their own when importing)
 *   GAMEHUB   zip, entries "/drive_c/users/steamuser/..."
 *   GAMENATIVE zip with "manifest.json" (version 5, steamAppId, gameName,
 *             exportedAt, roots[{rootId, path}]) and files under
 *             "files/<rootId>/...". rootId = "<root type, lower case>/<save
 *             path from the Steam app info, '/' separators, lower case>"
 *             ("root" when the path is empty); Steam userdata is
 *             "steamuserdata". Steam games only (steamAppId is required).
 * Everything here is pure Java (no Android classes) so it can be tested off
 * the device. Paths are relative to drive_c, with '/' separators.
 * 다른 안드로이드 Windows 게임 앱이 쓰는 세이브 묶음 형식. 세이브를 양쪽으로
 * 옮길 수 있게 함(사용자가 떠날 수도, 돌아올 수도 있게). 형식만 따르고 그 앱들의
 * 코드는 가져오지 않음.
 *   WINLATOR  zip, 항목 "drive_c/users/xuser/..."(Winlator, WinNative, Bannerlator,
 *             DroidDeck이 읽음. 가져올 때 사용자 폴더 이름을 자기 것으로 바꿈)
 *   GAMEHUB   zip, 항목 "/drive_c/users/steamuser/..."
 *   GAMENATIVE "manifest.json"(version 5, steamAppId, gameName, exportedAt,
 *             roots[{rootId, path}])과 "files/<rootId>/..." 아래 파일이 든 zip.
 *             rootId = "<경로 종류, 소문자>/<스팀 앱 정보의 세이브 경로, '/' 구분,
 *             소문자>"(경로가 비면 "root"). 스팀 userdata는 "steamuserdata".
 *             스팀 게임만 가능(steamAppId 필수).
 * 모두 안드로이드 클래스를 쓰지 않는 순수 Java라 기기 밖에서 시험할 수 있음.
 * 경로는 drive_c 기준 상대 경로이고 구분자는 '/'임
 */
public final class SaveTransfer {
    private SaveTransfer() {}

    public enum Format { WINLATOR, GAMEHUB, GAMENATIVE }

    // Windows user folder names in each layout / 각 형식의 Windows 사용자 폴더 이름
    public static final String USER_WINLATOR = "xuser";
    public static final String USER_GAMEHUB = "steamuser";

    public static final int GAMENATIVE_VERSION = 5;
    public static final String GAMENATIVE_MANIFEST = "manifest.json";
    public static final String GAMENATIVE_FILES = "files/";
    public static final String ROOT_STEAM_USERDATA = "steamuserdata";
    public static final String ROOT_GAME_INSTALL = "gameinstall";

    // ==================== drive_c layouts / drive_c 형식 ====================

    /**
     * Zip entry for a file of the shared save folder (relative to drive_c, our
     * user name) in the WINLATOR or GAMEHUB layout.
     * 공용 세이브 폴더의 파일(drive_c 기준, 우리 사용자 이름)을 WINLATOR 또는 GAMEHUB
     * 형식의 zip 항목 이름으로 바꿈
     */
    public static String toArchiveEntry(Format format, String rel, String ourUser) {
        String r = normalize(rel);
        String user = format == Format.GAMEHUB ? USER_GAMEHUB : USER_WINLATOR;
        String prefix = "users/" + ourUser + "/";
        if (r.startsWith(prefix)) r = "users/" + user + "/" + r.substring(prefix.length());
        return (format == Format.GAMEHUB ? "/drive_c/" : "drive_c/") + r;
    }

    /**
     * drive_c-relative path for a zip entry of a drive_c layout, with any user
     * folder other than Public renamed to ourUser. null for entries outside
     * drive_c, folders, and unsafe names (".." parts).
     * drive_c 형식 zip 항목을 drive_c 기준 상대 경로로 바꿈. Public이 아닌 사용자 폴더
     * 이름은 ourUser로 바꿈. drive_c 밖 항목, 폴더, 위험한 이름(".." 포함)은 null
     */
    public static String fromArchiveEntry(String entryName, String ourUser) {
        if (entryName == null) return null;
        String n = entryName.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.endsWith("/")) return null;
        if (!n.toLowerCase(Locale.ENGLISH).startsWith("drive_c/")) return null;
        String rel = normalize(n.substring("drive_c/".length()));
        if (rel.isEmpty() || !isSafe(rel)) return null;
        return renameUser(rel, ourUser);
    }

    // "users/<name>/..." -> "users/<ourUser>/..." unless <name> is Public
    // "users/<이름>/..." -> "users/<ourUser>/..."(이름이 Public이면 그대로)
    static String renameUser(String rel, String ourUser) {
        if (!rel.toLowerCase(Locale.ENGLISH).startsWith("users/")) return rel;
        int slash = rel.indexOf('/', 6);
        if (slash < 0) return rel;
        String name = rel.substring(6, slash);
        if (name.equalsIgnoreCase("public")) return "users/Public" + rel.substring(slash);
        return "users/" + ourUser + rel.substring(slash);
    }

    /** True when rel lies inside one of the shared save locations / rel이 공유 세이브 위치 안에 있으면 true */
    public static boolean isShared(String rel, List<String> sharedEntries) {
        String lower = normalize(rel).toLowerCase(Locale.ENGLISH);
        for (String e : sharedEntries) {
            String el = e.toLowerCase(Locale.ENGLISH);
            if (lower.equals(el) || lower.startsWith(el + "/")) return true;
        }
        return false;
    }

    /**
     * The shared location rel belongs to, spelled as in sharedEntries, plus the
     * rest of rel ("users/xuser/appdata/roaming/X" -> "users/xuser/AppData/Roaming/X").
     * null when rel is not in a shared location.
     * rel이 속한 공유 위치를 sharedEntries의 철자로 바꾸고 나머지를 붙임
     * ("users/xuser/appdata/roaming/X" -> "users/xuser/AppData/Roaming/X").
     * 공유 위치가 아니면 null
     */
    public static String sharedSpelling(String rel, List<String> sharedEntries) {
        String r = normalize(rel);
        String lower = r.toLowerCase(Locale.ENGLISH);
        for (String e : sharedEntries) {
            String el = e.toLowerCase(Locale.ENGLISH);
            if (lower.equals(el)) return e;
            if (lower.startsWith(el + "/")) return e + r.substring(e.length());
        }
        return null;
    }

    // ==================== GameNative layout / GameNative 형식 ====================

    /** rootId of a Steam save pattern / 스팀 세이브 패턴의 rootId */
    public static String rootId(String rootType, String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        if (p.trim().isEmpty()) p = "root";
        return rootType.toLowerCase(Locale.ENGLISH) + "/" + p.toLowerCase(Locale.ENGLISH);
    }

    /** {64BitSteamID} / {Steam3AccountID} in a save path / 세이브 경로의 {64BitSteamID} / {Steam3AccountID} */
    public static String substitute(String path, long steamId64) {
        if (path == null) return "";
        return path.replace("{64BitSteamID}", String.valueOf(steamId64))
                .replace("{Steam3AccountID}", String.valueOf(steamId64 & 0xFFFFFFFFL))
                .replace('\\', '/');
    }

    /**
     * drive_c-relative folder of a Windows save root type (Steam app info "root"),
     * for our user; null for types that are not under drive_c users/ProgramData
     * (GameInstall, SteamUserData are handled by the caller) and unknown types.
     * 윈도우 세이브 루트 종류(스팀 앱 정보의 "root")의 drive_c 기준 폴더(우리 사용자 기준).
     * drive_c의 users/ProgramData 아래가 아닌 종류(GameInstall, SteamUserData는 호출하는
     * 쪽에서 처리)와 모르는 종류는 null
     */
    public static String rootFolder(String rootType, String ourUser) {
        String u = "users/" + ourUser + "/";
        switch (rootType.toLowerCase(Locale.ENGLISH)) {
            case "winappdataroaming": return u + "AppData/Roaming";
            case "winappdatalocal": return u + "AppData/Local";
            case "winappdatalocallow": return u + "AppData/LocalLow";
            case "winmydocuments": return u + "Documents";
            case "winsavedgames": return u + "Saved Games";
            case "winprogramdata": return "ProgramData";
            case "root": return "users/" + ourUser;
            default: return null;
        }
    }

    /** Steam userdata folder of one game, relative to drive_c / 게임 하나의 스팀 userdata 폴더(drive_c 기준) */
    public static String steamUserDataFolder(long steamId64, int appId) {
        return "Program Files (x86)/Steam/userdata/" + (steamId64 & 0xFFFFFFFFL) + "/" + appId + "/remote";
    }

    /**
     * Where a GameNative root goes on import, from the absolute path the
     * exporting device wrote in the manifest:
     *   ".../drive_c/<rest>"                    -> "<rest>" (user folder renamed)
     *   ".../Steam/userdata/<acct>/<app>/remote" -> our account's userdata folder
     *   ".../steamapps/common/<rest>"           -> "gameinstall:<rest>"
     * null when the path says nothing usable (the rootId is used instead).
     * 가져올 때 GameNative 루트가 갈 곳. 내보낸 기기가 매니페스트에 쓴 절대 경로로 정함:
     *   ".../drive_c/<나머지>"                    -> "<나머지>"(사용자 폴더 이름 바꿈)
     *   ".../Steam/userdata/<계정>/<앱>/remote"    -> 우리 계정의 userdata 폴더
     *   ".../steamapps/common/<나머지>"           -> "gameinstall:<나머지>"
     * 쓸 만한 내용이 없으면 null(대신 rootId를 씀)
     */
    public static String importFolderFromPath(String absPath, String rootId, String ourUser, long steamId64, int appId) {
        if (absPath == null) return null;
        String p = absPath.replace('\\', '/');
        String lower = p.toLowerCase(Locale.ENGLISH);
        if (ROOT_STEAM_USERDATA.equals(rootId) || lower.contains("/steam/userdata/")) {
            return steamUserDataFolder(steamId64, appId);
        }
        int common = lower.indexOf("/steamapps/common/");
        if (common >= 0) {
            String rest = normalize(p.substring(common + "/steamapps/common/".length()));
            return isSafe(rest) && !rest.isEmpty() ? "gameinstall:" + rest : null;
        }
        int dc = lower.indexOf("drive_c/");
        if (dc >= 0) {
            String rest = normalize(p.substring(dc + "drive_c/".length()));
            if (rest.isEmpty() || !isSafe(rest)) return null;
            return renameUser(rest, ourUser);
        }
        return null;
    }

    /**
     * Fallback from the rootId alone ("winappdataroaming/mygame/saves" ->
     * "users/xuser/AppData/Roaming/mygame/saves"; "gameinstall/save" ->
     * "gamepath:save"). The save path part is lower case in a rootId, so the
     * caller matches existing folders ignoring case.
     * rootId만으로 정하는 대체 경로("winappdataroaming/mygame/saves" ->
     * "users/xuser/AppData/Roaming/mygame/saves", "gameinstall/save" ->
     * "gamepath:save"). rootId의 세이브 경로는 소문자라
     * 호출하는 쪽에서 기존 폴더와 대소문자를 무시하고 맞춤
     */
    public static String importFolderFromRootId(String rootId, String ourUser, long steamId64, int appId) {
        if (rootId == null) return null;
        if (ROOT_STEAM_USERDATA.equals(rootId)) return steamUserDataFolder(steamId64, appId);
        int slash = rootId.indexOf('/');
        String type = slash > 0 ? rootId.substring(0, slash) : rootId;
        String path = slash > 0 ? rootId.substring(slash + 1) : "";
        if (path.equals("root")) path = "";
        // A rootId keeps the Steam ID tokens, in lower case / rootId에는 스팀 ID 토큰이 소문자로 남아 있음
        path = path.replace("{64bitsteamid}", String.valueOf(steamId64))
                .replace("{steam3accountid}", String.valueOf(steamId64 & 0xFFFFFFFFL));
        if (!isSafe(path)) return null;
        // Inside the game folder, without the install folder name ("gamepath:")
        // 게임 폴더 안 경로임. 설치 폴더 이름은 없음("gamepath:")
        if (type.equals(ROOT_GAME_INSTALL)) return "gamepath:" + path;
        String base = rootFolder(type, ourUser);
        if (base == null) return null;
        return path.isEmpty() ? base : base + "/" + path;
    }

    /**
     * The longest rootId that starts entryRel ("<rootId>/<file>"); null when none.
     * entryRel("<rootId>/<파일>")의 앞부분과 맞는 가장 긴 rootId. 없으면 null
     */
    public static String matchRoot(String entryRel, List<String> rootIds) {
        String best = null;
        for (String id : rootIds) {
            if (entryRel.startsWith(id + "/") && (best == null || id.length() > best.length())) best = id;
        }
        return best;
    }

    // ==================== Helpers / 보조 ====================

    /**
     * Steam save pattern ("*.sav", "save?.dat", "*") against a file name,
     * ignoring case like Windows does.
     * 스팀 세이브 패턴("*.sav", "save?.dat", "*")과 파일 이름 비교. Windows처럼
     * 대소문자를 무시함
     */
    public static boolean matchesPattern(String name, String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.equals("*")) return true;
        return glob(name.toLowerCase(Locale.ENGLISH), 0, pattern.toLowerCase(Locale.ENGLISH), 0);
    }

    private static boolean glob(String s, int si, String p, int pi) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                for (int k = si; k <= s.length(); k++) if (glob(s, k, p, pi + 1)) return true;
                return false;
            }
            if (si >= s.length() || (c != '?' && c != s.charAt(si))) return false;
            si++;
            pi++;
        }
        return si == s.length();
    }

    /** Archive kind from the first bytes / 첫 바이트로 본 묶음 종류 */
    public enum Kind { ZIP, XZ, ZSTD, UNKNOWN }

    public static Kind kindOf(byte[] head) {
        if (head == null || head.length < 4) return Kind.UNKNOWN;
        if (head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) return Kind.ZIP;
        if ((head[0] & 0xFF) == 0xFD && head[1] == '7' && head[2] == 'z' && head[3] == 'X') return Kind.XZ;
        if ((head[0] & 0xFF) == 0x28 && (head[1] & 0xFF) == 0xB5 && (head[2] & 0xFF) == 0x2F && (head[3] & 0xFF) == 0xFD) return Kind.ZSTD;
        return Kind.UNKNOWN;
    }

    // '/' separators, no leading / trailing '/', no empty or "." parts
    // '/' 구분자, 앞뒤 '/' 없음, 빈 부분과 "." 부분 없음
    public static String normalize(String rel) {
        if (rel == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : rel.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }

    // No ".." part (an entry may not climb out of its folder)
    // ".." 부분이 없음(항목이 자기 폴더 밖으로 나가면 안 됨)
    public static boolean isSafe(String rel) {
        for (String part : rel.replace('\\', '/').split("/")) if (part.equals("..")) return false;
        return true;
    }

    /** "12.3 MB" style size / "12.3 MB" 형식 크기 */
    public static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ENGLISH, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ENGLISH, "%.1f MB", mb);
        return String.format(Locale.ENGLISH, "%.2f GB", mb / 1024.0);
    }
}
