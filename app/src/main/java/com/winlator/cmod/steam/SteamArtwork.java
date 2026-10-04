package com.winlator.cmod.steam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import com.winlator.cmod.contents.Downloader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Steam artwork for the library page and for Steam shortcuts.
 *   capsule = portrait cover (tiles)          library_600x900.jpg
 *   wide    = wide cover (dialog backgrounds) library_hero.jpg, else header.jpg
 * Steam serves current artwork from "store_item_assets"; the old
 * "steam/apps" path is still tried, and as the last step the store API
 * (appdetails) gives the exact header image URL, which also covers games whose
 * files only exist under a hashed folder - the reason some games showed no
 * picture before. Results are cached in memory and in the cache folder; a
 * failed lookup is remembered for one day, then tried again.
 * 라이브러리 페이지와 스팀 바로가기용 스팀 아트워크임.
 *   capsule = 세로 표지(타일)          library_600x900.jpg
 *   wide    = 넓은 표지(창 배경)       library_hero.jpg, 없으면 header.jpg
 * 스팀은 현재 아트워크를 "store_item_assets"에서 제공함. 예전 "steam/apps" 경로도
 * 시도하고, 마지막으로 상점 API(appdetails)가 주는 정확한 헤더 이미지 주소를 씀.
 * 이것은 파일이 해시 이름 폴더에만 있는 게임도 해결함 - 예전에 일부 게임에 그림이
 * 안 나오던 이유임. 결과는 메모리와 캐시 폴더에 저장하고, 실패한 조회는 하루 동안
 * 기억했다가 다시 시도함
 */
public final class SteamArtwork {
    private static final String TAG = "SteamArtwork";
    private static final String[] HOSTS = {
            "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/",
            "https://cdn.cloudflare.steamstatic.com/steam/apps/"};
    // A failed lookup is retried after an hour. It used to be a day, so one
    // dropped connection left a game without artwork until the next day
    // 실패한 조회는 1시간 뒤 다시 시도함. 예전에는 하루였어서 연결이 한 번
    // 끊기면 다음 날까지 아트워크가 안 나왔음
    private static final long RETRY_AFTER_MS = 60L * 60 * 1000;
    private static final int CAPSULE_MAX_WIDTH = 300;
    private static final int WIDE_MAX_WIDTH = 1280;

    private static final LruCache<String, Bitmap> memory =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private SteamArtwork() {}

    public static Bitmap cachedCapsule(int appId) {
        return memory.get("c" + appId);
    }

    public static Bitmap cachedWide(int appId) {
        return memory.get("w" + appId);
    }

    // Blocking: call from a background thread / 동기식: 백그라운드 스레드에서 부를 것
    public static Bitmap loadCapsule(Context context, int appId) {
        return load(context, appId, "c", new String[]{"library_600x900.jpg", "library_600x900_2x.jpg", "header.jpg"},
                CAPSULE_MAX_WIDTH);
    }

    public static Bitmap loadWide(Context context, int appId) {
        return load(context, appId, "w", new String[]{"library_hero.jpg", "header.jpg"}, WIDE_MAX_WIDTH);
    }

    // One lock per artwork key: the tile list and the detail sheet can ask for the
    // same picture at the same time. Without it, the second caller found the file
    // the first one was still downloading, failed to decode the half-written file
    // and deleted it - artwork then showed only sometimes
    // 아트워크 키마다 잠금 하나: 타일 목록과 상세창이 같은 그림을 동시에 요청할 수
    // 있음. 잠금이 없으면 두 번째 요청이 첫 번째가 아직 받는 중인 파일을 보고, 반쯤
    // 쓰인 파일 해석에 실패해서 지워 버렸음 - 그래서 아트워크가 될 때도 있고 안 될
    // 때도 있었음
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Bitmap load(Context context, int appId, String kind, String[] names, int maxWidth) {
        String key = kind + appId;
        Bitmap hit = memory.get(key);
        if (hit != null) return hit;
        synchronized (LOCKS.computeIfAbsent(key, k -> new Object())) {
            return loadLocked(context, appId, key, names, maxWidth);
        }
    }

    private static Bitmap loadLocked(Context context, int appId, String key, String[] names, int maxWidth) {
        Bitmap hit = memory.get(key);
        if (hit != null) return hit;

        File dir = new File(context.getCacheDir(), "steam_art");
        File file = new File(dir, key + ".jpg");
        File none = new File(dir, key + ".none");
        if (!file.isFile()) {
            if (none.isFile() && System.currentTimeMillis() - none.lastModified() < RETRY_AFTER_MS) return null;
            dir.mkdirs();
            boolean ok = false;
            for (String name : names) {
                for (String host : HOSTS) {
                    if (fetch(host + appId + "/" + name, file)) {
                        ok = true;
                        break;
                    }
                }
                if (ok) break;
            }
            // Last step: the exact header URL from the store API
            // 마지막 단계: 상점 API가 주는 정확한 헤더 주소
            if (!ok) {
                String header = headerFromStoreApi(appId);
                ok = header != null && fetch(header, file);
            }
            if (!ok) {
                try {
                    none.delete();
                    none.createNewFile();
                } catch (Exception ignored) {}
                return null;
            }
            none.delete();
        }
        Bitmap bmp = decode(file, maxWidth);
        if (bmp == null) {
            file.delete();
            return null;
        }
        memory.put(key, bmp);
        return bmp;
    }

    // Downloads to a temporary file and renames it into place, so a cached file is
    // always complete
    // 임시 파일로 받은 뒤 제 이름으로 바꿈. 그래서 캐시 파일은 항상 완전한 파일임
    private static boolean fetch(String url, File out) {
        File part = new File(out.getPath() + ".part");
        if (Downloader.downloadFile(url, part) && part.length() > 1024 && part.renameTo(out)) return true;
        part.delete();
        return false;
    }

    private static Bitmap decode(File file, int maxWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    // store.steampowered.com/api/appdetails -> data.header_image
    private static String headerFromStoreApi(int appId) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://store.steampowered.com/api/appdetails?appids="
                    + appId + "&filters=basic").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            JSONObject root = new JSONObject(bos.toString("UTF-8")).optJSONObject(String.valueOf(appId));
            if (root == null || !root.optBoolean("success")) return null;
            JSONObject data = root.optJSONObject("data");
            String url = data != null ? data.optString("header_image", null) : null;
            return url != null && !url.isEmpty() ? url : null;
        } catch (Exception e) {
            Log.w(TAG, "store api failed for " + appId, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
