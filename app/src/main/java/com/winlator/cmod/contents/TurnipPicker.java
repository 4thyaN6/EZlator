package com.winlator.cmod.contents;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Graphics driver installed on first run for the Latest tier, by GPU. Fixed,
 * checked builds (not "the newest release"): StevenMXZ's newest releases
 * include Qualcomm proprietary drivers, and asset names change over time.
 *   Adreno 8xx              -> Turnip Gen8 V37
 *   Adreno 710 / 720 / 722  -> none (device driver; Turnip support there is not
 *                              settled, the user can install one in Contents)
 *   Adreno 740 on Samsung   -> Turnip v26.3.0-R6 One UI (screen tearing fix)
 *   other Adreno 6xx / 7xx  -> Turnip v26.3.0-R6
 *   Samsung Xclipse         -> ExynosTools V2.0
 *   anything else           -> none (device driver)
 * When the download or install fails nothing is set, so the device driver stays.
 * Pure Java (no Android classes) so it can be tested off the device.
 * Latest(최신) 단계 첫 실행 때 GPU별로 설치하는 그래픽 드라이버. "가장 새 릴리즈"가
 * 아니라 확인한 빌드로 고정함: StevenMXZ의 최신 릴리즈에는 퀄컴 독점 드라이버가
 * 섞여 있고, 파일 이름이 시기마다 바뀜.
 *   Adreno 8xx              -> Turnip Gen8 V37
 *   Adreno 710 / 720 / 722  -> 없음(기기 드라이버. 이 GPU의 Turnip 지원은 아직 정해지지
 *                              않았고, 사용자가 구성요소 화면에서 설치할 수 있음)
 *   삼성의 Adreno 740        -> Turnip v26.3.0-R6 One UI(화면 찢김 수정)
 *   그 밖의 Adreno 6xx / 7xx -> Turnip v26.3.0-R6
 *   삼성 Xclipse            -> ExynosTools V2.0
 *   그 밖                   -> 없음(기기 드라이버)
 * 받기나 설치가 실패하면 아무것도 지정하지 않으므로 기기 드라이버가 그대로 쓰임.
 * 안드로이드 클래스를 쓰지 않는 순수 Java라 기기 밖에서 시험할 수 있음
 */
public final class TurnipPicker {
    private TurnipPicker() {}

    private static final String STEVENMXZ = "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/";
    public static final String TURNIP_GEN8_URL = STEVENMXZ + "v37/Turnip_Gen8_V37.zip";
    public static final String TURNIP_R_URL = STEVENMXZ + "v37/Turnip_v26.3.0-R6.zip";
    public static final String TURNIP_R_ONEUI_URL = STEVENMXZ + "v37/Turnip_v26.3.0-R6-Oneui.zip";
    public static final String EXYNOSTOOLS_URL =
            "https://github.com/WearyConcern1165/ExynosTools/releases/download/2.0/ExynosTools_V2.0stable.zip";

    private static final Pattern ADRENO_MODEL = Pattern.compile("adreno[^0-9]*([0-9]{3})");

    /** {label, url}, or null for the device driver / {표시 이름, 주소}. 기기 드라이버면 null */
    public static String[] pick(String renderer, String manufacturer) {
        if (renderer == null) return null;
        String r = renderer.toLowerCase(Locale.ENGLISH);
        boolean samsung = manufacturer != null && manufacturer.equalsIgnoreCase("samsung");
        if (r.contains("adreno")) {
            int model = adrenoModel(r);
            if (model >= 800 && model < 900) return new String[]{"Turnip Gen8 V37 (Adreno 8xx)", TURNIP_GEN8_URL};
            if (model == 710 || model == 720 || model == 722) return null;
            if (model >= 600 && model < 800) {
                if (samsung && model == 740) return new String[]{"Turnip v26.3.0-R6 OneUI (Adreno 740)", TURNIP_R_ONEUI_URL};
                return new String[]{"Turnip v26.3.0-R6 (Adreno 6xx/7xx)", TURNIP_R_URL};
            }
            return null;
        }
        if (r.contains("xclipse")) return new String[]{"ExynosTools V2.0 (Samsung Xclipse)", EXYNOSTOOLS_URL};
        return null;
    }

    // "adreno (tm) 740" -> 740; -1 when there is no 3-digit model
    // "adreno (tm) 740" -> 740. 세 자리 모델 번호가 없으면 -1
    static int adrenoModel(String lowerRenderer) {
        Matcher m = ADRENO_MODEL.matcher(lowerRenderer);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
