package com.winlator.cmod.contents;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GpuInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the StevenMXZ GraphicTools list straight from GitHub Releases, so a
 * new Turnip build shows up in the app without editing contents.json.
 * Qualcomm (proprietary) driver releases are skipped on purpose.
 * StevenMXZ GraphicTools 목록을 GitHub Releases에서 바로 만듦. 새 Turnip 빌드가
 * 올라오면 contents.json을 고치지 않아도 앱에 뜸. 퀄컴(독점) 드라이버 릴리즈는
 * 일부러 제외함
 *
 * The unauthenticated GitHub API allows 60 requests per hour per IP, so the
 * response is cached for CACHE_TTL_MS and the stale cache is reused on failure.
 * 비인증 GitHub API는 IP당 시간당 60회로 제한되므로 응답을 CACHE_TTL_MS 동안
 * 캐시하고, 실패하면 오래된 캐시라도 다시 씀
 */
public final class DriverCatalog {
    private static final String TAG = "DriverCatalog";
    private static final String STEVENMXZ_API =
            "https://api.github.com/repos/StevenMXZ/Adreno-Tools-Drivers/releases?per_page=30";
    private static final String CACHE_FILE = "driver_catalog_stevenmxz.json";
    // The412Banner publishes Turnip in its own repo (Banners-Turnip), not in the
    // winlator-contents catalog - that is why this sub-tab stayed empty
    // The412Banner는 Turnip을 winlator-contents 카탈로그가 아니라 별도 저장소
    // (Banners-Turnip)에 올림 - 그래서 이 하위 탭이 비어 있었음
    private static final String BANNER_API =
            "https://api.github.com/repos/The412Banner/Banners-Turnip/releases?per_page=20";
    private static final String BANNER_CACHE_FILE = "driver_catalog_the412banner.json";
    private static final long CACHE_TTL_MS = 6L * 60 * 60 * 1000;

    private DriverCatalog() {}

    /** Blocking; call off the UI thread. / 블로킹 호출이므로 UI 스레드 밖에서 부를 것 */
    public static List<ContentProfile> fetchStevenMxz(Context context) {
        return parseReleases(fetchJson(context, STEVENMXZ_API, CACHE_FILE));
    }

    /** Blocking; call off the UI thread. / 블로킹 호출이므로 UI 스레드 밖에서 부를 것 */
    public static List<ContentProfile> fetchThe412Banner(Context context) {
        return parseBannerReleases(fetchJson(context, BANNER_API, BANNER_CACHE_FILE));
    }

    /**
     * Banners-Turnip assets (e.g. release v26.3.0-20260924):
     *   Turnip-v26.3.0-20260924.zip                 -> Android build, all Adreno
     *   Turnip-v26.3.0-20260924-710-720-Test.zip    -> test build for Adreno 710/720
     *   ...-Linux.zip / ...-Wayland.zip             -> desktop Linux builds, skipped
     * Banners-Turnip 파일 구성(예: 릴리즈 v26.3.0-20260924):
     *   Turnip-v26.3.0-20260924.zip                 -> 안드로이드용, 모든 Adreno
     *   Turnip-v26.3.0-20260924-710-720-Test.zip    -> Adreno 710/720 전용 테스트 빌드
     *   ...-Linux.zip / ...-Wayland.zip             -> 데스크탑 리눅스용이라 제외
     */
    static List<ContentProfile> parseBannerReleases(String json) {
        ArrayList<ContentProfile> result = new ArrayList<>();
        if (json == null) return result;
        try {
            JSONArray releases = new JSONArray(json);
            for (int i = 0; i < releases.length(); i++) {
                JSONObject rel = releases.getJSONObject(i);
                if (rel.optBoolean("draft", false)) continue;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null) continue;
                for (int a = 0; a < assets.length(); a++) {
                    JSONObject asset = assets.getJSONObject(a);
                    String name = asset.optString("name", "");
                    String lower = name.toLowerCase(Locale.ENGLISH);
                    String url = asset.optString("browser_download_url", null);
                    if (url == null || !lower.endsWith(".zip") || !lower.startsWith("turnip")) continue;
                    if (lower.contains("linux") || lower.contains("wayland")) continue;

                    ContentProfile p = new ContentProfile();
                    p.type = ContentProfile.ContentType.CONTENT_TYPE_GRAPHIC_TOOLS;
                    p.subType = ContentProfile.SUBTYPE_ADRENOTOOLS;
                    p.provider = ContentProfile.PROVIDER_THE412BANNER;
                    p.gpuVendor = GpuInfo.VENDOR_ADRENO;
                    // "710-720" builds are GPU-specific; the plain build has no model limit
                    // "710-720" 빌드는 특정 GPU 전용이고, 일반 빌드는 모델 제한이 없음
                    if (lower.contains("710-720")) p.gpuMatch = "Adreno 710, Adreno 720";
                    p.verName = name.substring(0, name.length() - 4).replace('_', '-');
                    p.verCode = 0;
                    p.remoteUrl = url;
                    result.add(p);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Banners-Turnip list parse failed", e);
        }
        return result;
    }

    private static String fetchJson(Context context, String api, String cacheName) {
        File cache = new File(context.getFilesDir(), cacheName);
        String json = null;

        boolean fresh = cache.isFile() && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS;
        if (fresh) json = FileUtils.readString(cache);

        if (json == null || json.isEmpty()) {
            String downloaded = httpGet(api);
            if (downloaded != null && downloaded.trim().startsWith("[")) {
                json = downloaded;
                FileUtils.writeString(cache, json);
            } else if (cache.isFile()) {
                // Offline or rate-limited: an old list beats an empty tab
                // 오프라인이거나 호출 제한 상태: 빈 탭보다 오래된 목록이 나음
                json = FileUtils.readString(cache);
            }
        }
        return json;
    }

    static List<ContentProfile> parseReleases(String json) {
        ArrayList<ContentProfile> result = new ArrayList<>();
        if (json == null) return result;
        try {
            JSONArray releases = new JSONArray(json);
            for (int i = 0; i < releases.length(); i++) {
                JSONObject rel = releases.getJSONObject(i);
                if (rel.optBoolean("draft", false)) continue;
                String relName = rel.optString("name", "") + " " + rel.optString("tag_name", "");
                // Qualcomm proprietary drivers are excluded (e.g. "Qualcomm Driver v863.1")
                // 퀄컴 독점 드라이버는 제외함(예: "Qualcomm Driver v863.1")
                if (relName.toLowerCase(Locale.ENGLISH).contains("qualcomm")) continue;

                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null) continue;

                // If a release ships a One UI zip, its plain zip is for non-Samsung
                // 릴리즈에 One UI용 zip이 있으면 일반 zip은 삼성 외 기기용임
                boolean hasOneUi = false;
                for (int a = 0; a < assets.length(); a++) {
                    if (assets.getJSONObject(a).optString("name", "").toLowerCase(Locale.ENGLISH).contains("oneui")) hasOneUi = true;
                }

                for (int a = 0; a < assets.length(); a++) {
                    JSONObject asset = assets.getJSONObject(a);
                    String name = asset.optString("name", "");
                    String lower = name.toLowerCase(Locale.ENGLISH);
                    String url = asset.optString("browser_download_url", null);
                    if (url == null || !lower.endsWith(".zip") || !lower.contains("turnip")) continue;

                    ContentProfile p = new ContentProfile();
                    p.type = ContentProfile.ContentType.CONTENT_TYPE_GRAPHIC_TOOLS;
                    p.subType = ContentProfile.SUBTYPE_ADRENOTOOLS;
                    p.provider = ContentProfile.PROVIDER_STEVENMXZ;
                    p.gpuVendor = GpuInfo.VENDOR_ADRENO;
                    // Gen8 builds target A8xx; the numbered builds target A6xx/A7xx
                    // Gen8 빌드는 A8xx용이고, 버전 번호 빌드는 A6xx/A7xx용임
                    p.gpuMatch = lower.contains("gen8") ? "Adreno 8" : "Adreno 7, Adreno 6";
                    if (lower.contains("oneui")) p.manufacturer = "samsung";
                    else if (hasOneUi) p.manufacturer = "!samsung";
                    p.verName = name.substring(0, name.length() - 4).replace('_', '-');
                    p.verCode = 0;
                    p.remoteUrl = url;
                    result.add(p);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "release list parse failed", e);
        }
        return result;
    }

    private static String httpGet(String address) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            // GitHub rejects API calls without a User-Agent
            // GitHub API는 User-Agent가 없으면 요청을 거부함
            conn.setRequestProperty("User-Agent", "EZlator");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "HTTP " + conn.getResponseCode() + " from " + address);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "request failed: " + address, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
