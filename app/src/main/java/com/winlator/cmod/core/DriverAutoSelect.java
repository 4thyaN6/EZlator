package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Beginner default: the graphics driver downloaded most recently becomes the
 * one containers use, without opening the driver dialog.
 * 초보자용 기본 동작: 가장 최근에 받은 그래픽 드라이버를 드라이버 설정창을 열지
 * 않아도 컨테이너가 쓰게 함.
 *
 * Only containers still on the automatic choice follow along - i.e. version is
 * "System" (the stock default) or equals the previous automatic pick. A driver
 * the user picked by hand is never replaced.
 * 자동 선택 상태인 컨테이너만 따라감 - 즉 version이 "System"(원래 기본값)이거나
 * 직전 자동 선택값과 같은 경우임. 사용자가 직접 고른 드라이버는 절대 안 바꿈
 */
public final class DriverAutoSelect {
    private static final String TAG = "DriverAutoSelect";
    private static final String PREF_AUTO_DRIVER = "auto_graphics_driver_version";

    private DriverAutoSelect() {}

    /** Call after a driver install succeeds. / 드라이버 설치 성공 뒤에 호출할 것 */
    public static void onDriverInstalled(Context context, String driverName) {
        if (context == null || driverName == null || driverName.isEmpty()) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String previousAuto = prefs.getString(PREF_AUTO_DRIVER, null);
        prefs.edit().putString(PREF_AUTO_DRIVER, driverName).apply();

        int updated = 0;
        for (Container c : new ContainerManager(context).getContainers()) {
            Map<String, String> cfg = parse(c.getGraphicsDriverConfig());
            String current = cfg.get("version");
            boolean onAuto = current == null || current.isEmpty()
                    || current.equals(DefaultVersion.WRAPPER)
                    || current.equals(previousAuto);
            if (!onAuto || driverName.equals(current)) continue;
            cfg.put("version", driverName);
            c.setGraphicsDriverConfig(join(cfg));
            c.saveData();
            updated++;
        }
        Log.i(TAG, "auto driver -> " + driverName + " (containers updated: " + updated + ")");
    }

    /** Config for a new container: default config with the newest auto driver.
     *  새 컨테이너용 설정: 기본 설정에 최신 자동 드라이버를 넣은 값 */
    public static String defaultGraphicsDriverConfig(Context context) {
        String base = Container.DEFAULT_GRAPHICSDRIVERCONFIG;
        if (context == null) return base;
        String auto = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_AUTO_DRIVER, null);
        if (auto == null || auto.isEmpty()) return base;
        Map<String, String> cfg = parse(base);
        cfg.put("version", auto);
        return join(cfg);
    }

    // graphicsDriverConfig is "key=value;key=value" (';', not KeyValueSet's ',')
    // graphicsDriverConfig는 "key=value;key=value" 형식임(KeyValueSet의 ','가 아님)
    private static Map<String, String> parse(String s) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (s == null) return map;
        for (String part : s.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) map.put(part.substring(0, i), part.substring(i + 1));
        }
        return map;
    }

    private static String join(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
