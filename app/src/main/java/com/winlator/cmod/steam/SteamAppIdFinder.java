package com.winlator.cmod.steam;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds a Steam AppID for a shortcut without any account data:
 *  1) steam_appid.txt next to the game exe (exact, but many retail builds lack it)
 *  2) public Steam store search by game name -> candidates for the user to pick
 * Only the game name is sent; nothing is stored.
 * 계정 정보 없이 바로가기의 Steam AppID를 찾음:
 *  1) 게임 exe 옆의 steam_appid.txt(정확하지만 정식 배포판엔 없는 경우가 많음)
 *  2) 공개 Steam 상점 검색(게임 이름) -> 사용자가 고를 후보 목록
 * 보내는 건 게임 이름뿐이고 아무것도 저장하지 않음
 */
public final class SteamAppIdFinder {
    private static final String TAG = "SteamAppIdFinder";

    public static final class Candidate {
        public final int appId;
        public final String name;
        Candidate(int appId, String name) { this.appId = appId; this.name = name; }
    }

    private SteamAppIdFinder() {}

    /** @return AppID from steam_appid.txt in gameDir (or its steam_settings), 0 if none
     *  gameDir(또는 steam_settings)의 steam_appid.txt에 있는 AppID, 없으면 0 */
    public static int fromGameFolder(File gameDir) {
        if (gameDir == null) return 0;
        File[] candidates = {new File(gameDir, "steam_appid.txt"), new File(gameDir, "steam_settings/steam_appid.txt")};
        for (File f : candidates) {
            try {
                if (!f.isFile()) continue;
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                String digits = s.replaceAll("[^0-9].*$", "");
                if (!digits.isEmpty()) return Integer.parseInt(digits);
            } catch (Exception e) {
                Log.w(TAG, "read failed: " + f, e);
            }
        }
        return 0;
    }

    /**
     * Tries several spellings, because shortcut/exe names are often squashed:
     * "TeamfightManager2" -> "Teamfight Manager 2" (the real store title).
     * Also tries the game folder name. First non-empty result wins.
     * 바로가기/exe 이름은 붙어 있는 경우가 많아 여러 표기로 시도함:
     * "TeamfightManager2" -> "Teamfight Manager 2"(실제 상점 제목). 게임 폴더
     * 이름도 시도함. 처음으로 결과가 나온 것을 씀
     */
    public static List<Candidate> searchStoreVariants(String shortcutName, File gameDir) {
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        for (String base : new String[]{shortcutName, gameDir != null ? gameDir.getName() : null}) {
            if (base == null || base.trim().isEmpty()) continue;
            String b = base.replaceAll("(?i)\\.exe$", "").replace('_', ' ').replace('-', ' ').trim();
            // Split camelCase and letter/digit joins / camelCase와 문자-숫자 경계를 띄움
            String spaced = b.replaceAll("([a-z])([A-Z])", "$1 $2")
                    .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                    .replaceAll("([0-9])([A-Za-z])", "$1 $2")
                    .replaceAll("\\s+", " ").trim();
            terms.add(spaced);
            terms.add(b);
            // Without a trailing number (sequel number sometimes differs in folder names)
            // 끝 숫자를 뺀 이름(폴더 이름에서 시리즈 번호가 다를 때가 있음)
            String noNum = spaced.replaceAll("\\s*[0-9]+$", "").trim();
            if (!noNum.isEmpty()) terms.add(noNum);
        }
        for (String t : terms) {
            List<Candidate> r = searchStore(t);
            Log.i(TAG, "store search '" + t + "' -> " + r.size());
            if (!r.isEmpty()) return r;
        }
        return new ArrayList<>();
    }

    /** Blocking; call off the UI thread. / 블로킹 호출이므로 UI 스레드 밖에서 부를 것 */
    public static List<Candidate> searchStore(String gameName) {
        List<Candidate> out = new ArrayList<>();
        HttpURLConnection c = null;
        try {
            String url = "https://store.steampowered.com/api/storesearch/?term="
                    + URLEncoder.encode(gameName, "UTF-8") + "&l=korean&cc=KR";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            JSONArray items = new JSONObject(bo.toString("UTF-8")).optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length() && out.size() < 10; i++) {
                    JSONObject it = items.getJSONObject(i);
                    int id = it.optInt("id", 0);
                    if (id > 0) out.add(new Candidate(id, it.optString("name", String.valueOf(id))));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "store search failed", e);
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }
}
