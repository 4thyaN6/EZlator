package com.winlator.cmod.steam;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the Steam session the headless agent needs: account name, refresh
 * token and SteamID64. The refresh token is a long-lived login credential, so
 * it is encrypted with an AES-GCM key kept in the Android Keystore (the key
 * never leaves the secure hardware and is lost when the app is uninstalled).
 * The password is never stored.
 * 헤드리스 에이전트에 필요한 Steam 세션을 저장함: 계정 이름, refresh token,
 * SteamID64. refresh token은 오래 쓰는 로그인 자격이라 안드로이드 Keystore에 둔
 * AES-GCM 키로 암호화함(키는 보안 하드웨어 밖으로 안 나가고, 앱을 지우면 같이
 * 사라짐). 비밀번호는 절대 저장하지 않음
 */
public final class SteamAccountStore {
    private static final String TAG = "SteamAccountStore";
    private static final String PREFS = "steam_account";
    private static final String KEY_ALIAS = "ezlator_steam_token";

    private final SharedPreferences prefs;

    public SteamAccountStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isLoggedIn() {
        return !getAccountName().isEmpty() && !getRefreshToken().isEmpty() && getSteamId64() > 0;
    }

    public String getAccountName() { return prefs.getString("account", ""); }

    public long getSteamId64() { return prefs.getLong("steamid64", 0L); }

    public String getRefreshToken() {
        String enc = prefs.getString("token", "");
        if (enc.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(enc, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Key gone (e.g. restored backup on another device) - treat as logged out
            // 키가 없음(예: 다른 기기에 백업 복원) - 로그아웃 상태로 취급함
            Log.w(TAG, "token decrypt failed", e);
            return "";
        }
    }

    public void save(String accountName, String refreshToken, long steamId64) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        prefs.edit()
                .putString("account", accountName)
                .putLong("steamid64", steamId64)
                .putString("token", Base64.encodeToString(all, Base64.NO_WRAP))
                .apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /**
     * SteamID64 from a Steam refresh token. The token is a JWT whose "sub" claim
     * is the SteamID64, so no extra Steam logon round-trip is needed.
     * Steam refresh token에서 SteamID64를 꺼냄. 토큰은 JWT이고 "sub" 항목이
     * SteamID64라서 Steam에 따로 로그온할 필요가 없음
     */
    public static long steamIdFromToken(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0L;
            String json = new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                    StandardCharsets.UTF_8);
            return Long.parseLong(new org.json.JSONObject(json).optString("sub", "0"));
        } catch (Exception e) {
            Log.w(TAG, "could not read SteamID from token", e);
            return 0L;
        }
    }
}
