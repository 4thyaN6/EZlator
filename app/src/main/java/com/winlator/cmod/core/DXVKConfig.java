package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

// Parses/applies DXVK config (KeyValueSet string <-> env vars).
// Split out of the old DXVKConfigDialog: that class's dialog UI (constructor,
// spinner wiring, dxvk_config_dialog.xml) was never instantiated anywhere and
// has been deleted as dead code, but parseConfig()/setEnvVars() below are still
// called from ShortcutsFragment, XServerDisplayActivity and DXWrapperConfigDialog,
// so they were moved here rather than deleted along with the rest.
// DXVK 설정(KeyValueSet 문자열 <-> 환경변수) 파싱/적용 담당함.
// 예전 DXVKConfigDialog에서 분리해 낸 것임: 그 클래스의 다이얼로그 UI 부분
// (생성자, 스피너 연결, dxvk_config_dialog.xml)은 어디서도 생성되지 않는 죽은
// 코드라 삭제했지만, 아래 parseConfig()/setEnvVars()는 ShortcutsFragment,
// XServerDisplayActivity, DXWrapperConfigDialog에서 실사용 중이라 여기로 옮겨서 남김
public class DXVKConfig {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0,async=0,asyncCache=0";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        envVars.put("DXVK_STATE_CACHE_PATH", context.getFilesDir() + "/imagefs/" + ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = ImageFs.find(context).getRootDir();

        String content = "\"";
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+';';
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+';';
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        content = content + '\"';

        envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
        envVars.put("DXVK_CONFIG", content);
    }
}
