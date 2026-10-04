package com.winlator.cmod.steam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nickname + avatar for the signed-in account, read from the PUBLIC community
 * profile (steamcommunity.com/profiles/<SteamID64>/?xml=1). Name and avatar are
 * public even on private profiles, so no API key and no extra login is needed.
 * Nothing is written to storage - the result lives only while the screen is open.
 * 로그인된 계정의 닉네임 + 프로필 사진을 공개 커뮤니티 프로필
 * (steamcommunity.com/profiles/<SteamID64>/?xml=1)에서 읽음. 이름과 사진은
 * 비공개 프로필이어도 공개라서 API 키도 추가 로그인도 필요 없음.
 * 저장소에는 아무것도 쓰지 않음 - 결과는 화면이 열려 있는 동안에만 존재함
 */
public final class SteamProfileFetcher {
    private static final String TAG = "SteamProfileFetcher";

    public static final class Profile {
        public final String nickname;
        public final Bitmap avatar;
        Profile(String nickname, Bitmap avatar) { this.nickname = nickname; this.avatar = avatar; }
    }

    private SteamProfileFetcher() {}

    /** Blocking; call off the UI thread. / 블로킹 호출이므로 UI 스레드 밖에서 부를 것 */
    public static Profile fetch(long steamId64) {
        try {
            String xml = httpGetString("https://steamcommunity.com/profiles/" + steamId64 + "/?xml=1");
            if (xml == null) return null;
            String name = tag(xml, "steamID");
            String avatarUrl = tag(xml, "avatarFull");
            Bitmap avatar = null;
            if (avatarUrl != null && avatarUrl.startsWith("https://")) {
                HttpURLConnection c = (HttpURLConnection) new URL(avatarUrl).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) { avatar = BitmapFactory.decodeStream(in); }
                finally { c.disconnect(); }
            }
            return new Profile(name, avatar);
        } catch (Exception e) {
            Log.w(TAG, "profile fetch failed", e);
            return null;
        }
    }

    // <tag><![CDATA[value]]></tag> or <tag>value</tag>
    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</" + name + ">",
                Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String httpGetString(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
