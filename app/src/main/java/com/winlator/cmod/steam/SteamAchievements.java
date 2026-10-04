package com.winlator.cmod.steam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Steam achievements of one game for the signed-in account.
 * The list comes from Steam (SteamSession.getAchievements) and the last good
 * list is kept in the app's own storage (files/steam/achievements/<appId>.json),
 * so it can still be shown when Steam cannot be reached. Icons are kept in the
 * app cache (cache/steam_achievement_icons/<appId>/). Nothing is sent anywhere
 * else; the only request is the account's own stats query to Steam.
 * 로그인한 계정의 게임 하나에 대한 스팀 도전과제임.
 * 목록은 스팀에서 받고(SteamSession.getAchievements), 마지막으로 받은 목록을 앱
 * 자체 저장소(files/steam/achievements/<appId>.json)에 둬서 스팀에 연결하지 못할
 * 때도 보여 줄 수 있게 함. 아이콘은 앱 캐시(cache/steam_achievement_icons/<appId>/)에
 * 둠. 다른 곳으로 보내는 것은 없고, 요청은 스팀에 보내는 자기 계정 통계 조회뿐임
 *
 * Blocking calls; use them off the UI thread.
 * 블로킹 호출이므로 UI 스레드 밖에서 쓸 것
 */
public final class SteamAchievements {
    private static final String TAG = "SteamAchievements";
    // Steam community CDN folders of achievement icons, tried in order; the schema
    // stores only the file name. Whether each host answers is checked on the device
    // 스팀 커뮤니티 CDN의 도전과제 아이콘 폴더. 순서대로 시도함. 스키마에는 파일
    // 이름만 들어 있음. 각 주소가 응답하는지는 기기에서 확인함
    private static final String[] ICON_BASES = {
            "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/",
            "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/"
    };

    private SteamAchievements() {}

    // One achievement
    // 도전과제 하나
    public static final class Achievement {
        public String apiName = "";
        public String name = "";
        public String description = "";
        public String icon;
        public String iconGray;
        public boolean hidden;
        // Unix seconds, 0 = locked
        // 유닉스 초, 0 = 미달성
        public long unlockTime;

        public boolean isUnlocked() { return unlockTime > 0; }
    }

    // Loaded list. fromCache = Steam could not be reached and the saved list is shown;
    // notSignedIn = no Steam account on this device
    // 불러온 목록. fromCache = 스팀에 연결하지 못해 저장된 목록을 보여 줌,
    // notSignedIn = 이 기기에 스팀 계정이 없음
    public static final class Result {
        public final List<Achievement> list = new ArrayList<>();
        public boolean fromCache;
        public boolean notSignedIn;
        public boolean failed;
    }

    // Steam's language name for the device language (achievement texts are stored per language)
    // 기기 언어에 맞는 스팀 언어 이름(도전과제 글은 언어별로 저장되어 있음)
    public static String steamLanguage() {
        Locale l = Locale.getDefault();
        String lang = l.getLanguage();
        if (lang.equals("ko")) return "koreana";
        if (lang.equals("ja")) return "japanese";
        if (lang.equals("zh")) {
            String c = l.getCountry();
            return c.equals("TW") || c.equals("HK") || "Hant".equals(l.getScript()) ? "tchinese" : "schinese";
        }
        return "english";
    }

    private static File cacheFile(Context context, int appId) {
        return new File(context.getFilesDir(), "steam/achievements/" + appId + ".json");
    }

    // Steam first; on any failure the saved list. Blocking
    // 스팀 먼저, 실패하면 저장된 목록. 블로킹
    public static Result load(Context context, int appId) {
        Result r = new Result();
        SteamAccountStore store = new SteamAccountStore(context);
        if (!store.isLoggedIn()) {
            r.notSignedIn = true;
            r.fromCache = readCache(context, appId, r.list);
            return r;
        }
        try {
            List<Achievement> list = fetch(context, store, appId);
            r.list.addAll(list);
            writeCache(context, appId, list);
            return r;
        } catch (Exception e) {
            Log.w(TAG, "fetch failed for " + appId, e);
        }
        r.fromCache = readCache(context, appId, r.list);
        r.failed = !r.fromCache;
        return r;
    }

    // The shared Steam session of the UI process first (already logged on); when
    // that fails, a fresh session that is closed again afterwards
    // UI 프로세스의 공유 스팀 세션(이미 로그온됨)을 먼저 씀. 실패하면 새 세션을 열고
    // 끝나면 닫음
    private static List<Achievement> fetch(Context context, SteamAccountStore store, int appId) throws Exception {
        String lang = steamLanguage();
        SteamSession shared = SteamFragment.sharedSession(context);
        if (shared != null) {
            try {
                return shared.getAchievements(appId, store.getSteamId64(), lang);
            } catch (Exception e) {
                Log.w(TAG, "shared session failed, opening a new one", e);
            }
        }
        SteamSession s = new SteamSession();
        try {
            s.open(store);
            return s.getAchievements(appId, store.getSteamId64(), lang);
        } finally {
            s.close();
        }
    }

    private static void writeCache(Context context, int appId, List<Achievement> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Achievement a : list) {
                JSONObject o = new JSONObject();
                o.put("api", a.apiName);
                o.put("name", a.name);
                o.put("desc", a.description);
                o.put("icon", a.icon != null ? a.icon : "");
                o.put("iconGray", a.iconGray != null ? a.iconGray : "");
                o.put("hidden", a.hidden);
                o.put("unlock", a.unlockTime);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("appId", appId);
            root.put("savedAt", System.currentTimeMillis());
            root.put("achievements", arr);
            File f = cacheFile(context, appId);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            FileUtils.writeString(f, root.toString());
        } catch (Exception e) {
            Log.w(TAG, "cache write failed for " + appId, e);
        }
    }

    // Fills out from the saved list; false when there is none
    // 저장된 목록으로 채움. 없으면 false
    private static boolean readCache(Context context, int appId, List<Achievement> out) {
        File f = cacheFile(context, appId);
        if (!f.isFile()) return false;
        try {
            JSONArray arr = new JSONObject(FileUtils.readString(f)).getJSONArray("achievements");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Achievement a = new Achievement();
                a.apiName = o.optString("api");
                a.name = o.optString("name");
                a.description = o.optString("desc");
                a.icon = o.optString("icon", null);
                a.iconGray = o.optString("iconGray", null);
                a.hidden = o.optBoolean("hidden");
                a.unlockTime = o.optLong("unlock");
                out.add(a);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cache read failed for " + appId, e);
            return false;
        }
    }

    // Icon of an achievement (the grey one while locked), downloaded once into
    // the app cache. null when it cannot be had. Blocking
    // 도전과제 아이콘(미달성이면 회색 아이콘). 앱 캐시에 한 번만 받음. 못 구하면 null.
    // 블로킹
    public static Bitmap icon(Context context, int appId, Achievement a) {
        String file = a.isUnlocked() || a.iconGray == null || a.iconGray.isEmpty() ? a.icon : a.iconGray;
        if (file == null || file.isEmpty()) return null;
        String name = file.substring(file.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        File dir = new File(context.getCacheDir(), "steam_achievement_icons/" + appId);
        if (!dir.isDirectory()) dir.mkdirs();
        File cached = new File(dir, name);
        if (!(cached.isFile() && cached.length() > 0)) {
            String[] urls = file.startsWith("http") ? new String[]{file} : new String[ICON_BASES.length];
            if (!file.startsWith("http")) for (int i = 0; i < ICON_BASES.length; i++) urls[i] = ICON_BASES[i] + appId + "/" + file;
            File part = new File(dir, name + ".part");
            boolean ok = false;
            for (String url : urls) {
                if (Downloader.downloadFile(url, part) && part.length() > 0 && part.renameTo(cached)) { ok = true; break; }
                part.delete();
            }
            if (!ok) {
                Log.w(TAG, "icon not available: " + file);
                return null;
            }
        }
        return BitmapFactory.decodeFile(cached.getPath());
    }
}
