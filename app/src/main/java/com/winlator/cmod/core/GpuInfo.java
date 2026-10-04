package com.winlator.cmod.core;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;

/**
 * Detects which GPU family the device has, so the Graphic Tools list can show
 * only the drivers that could actually run here.
 * 기기의 GPU 계열을 알아내는 유틸임. Graphic Tools 목록에서 이 기기에서
 * 실제로 돌아갈 수 있는 드라이버만 보여 주기 위해 씀
 *
 * The renderer string is read through a 1x1 off-screen pbuffer context.
 * GLES20.glGetString() returns null without a current context, which is why
 * the EGL setup below cannot be skipped even though it looks heavy.
 * 렌더러 문자열은 1x1 화면 밖 pbuffer 컨텍스트를 만들어서 읽음.
 * GLES20.glGetString()은 현재 컨텍스트가 없으면 null을 돌려주므로,
 * 아래 EGL 준비 과정이 무거워 보여도 건너뛸 수 없음
 *
 * The result is cached: this runs once per process, not once per list bind.
 * 결과는 캐시함. 목록을 그릴 때마다가 아니라 프로세스당 한 번만 돎
 */
public class GpuInfo {
    private static final String TAG = "GpuInfo";

    // Vendor keys. These are the exact strings expected in the "gpuVendor"
    // field of a Graphic Tools entry in contents.json.
    // 벤더 키임. contents.json의 Graphic Tools 항목에서 "gpuVendor" 필드에
    // 들어갈 문자열과 정확히 같아야 함
    public static final String VENDOR_ADRENO = "adreno";
    public static final String VENDOR_MALI = "mali";
    public static final String VENDOR_XCLIPSE = "xclipse";
    public static final String VENDOR_POWERVR = "powervr";
    public static final String VENDOR_UNKNOWN = "unknown";

    private static String cachedRenderer;
    private static String cachedVendor;

    /**
     * Raw GL_RENDERER string, e.g. "Adreno (TM) 730", "Mali-G78 MP14",
     * "Samsung Xclipse 920". Empty string if it could not be read.
     * GL_RENDERER 원문임. 예: "Adreno (TM) 730", "Mali-G78 MP14",
     * "Samsung Xclipse 920". 읽지 못하면 빈 문자열임
     */
    public static synchronized String getRenderer() {
        if (cachedRenderer != null) return cachedRenderer;
        cachedRenderer = readRenderer();
        Log.d(TAG, "GL_RENDERER = " + cachedRenderer);
        return cachedRenderer;
    }

    /**
     * One of the VENDOR_* constants above, derived from the renderer string.
     * 위 VENDOR_* 상수 중 하나임. 렌더러 문자열에서 뽑아냄
     */
    public static synchronized String getVendor() {
        if (cachedVendor != null) return cachedVendor;

        String r = getRenderer().toLowerCase();

        // Xclipse is checked before Mali on purpose. Samsung's RDNA-based GPU
        // reports "Samsung Xclipse", but some builds also carry Mali strings
        // in the same line, and matching Mali first would misclassify it.
        // Xclipse를 Mali보다 먼저 검사함. 삼성의 RDNA 기반 GPU는
        // "Samsung Xclipse"로 보고하는데 일부 빌드는 같은 줄에 Mali 문자열도
        // 같이 실려 있어서, Mali를 먼저 맞추면 잘못 분류됨
        if (r.contains("xclipse")) cachedVendor = VENDOR_XCLIPSE;
        else if (r.contains("adreno")) cachedVendor = VENDOR_ADRENO;
        else if (r.contains("mali")) cachedVendor = VENDOR_MALI;
        else if (r.contains("powervr")) cachedVendor = VENDOR_POWERVR;
        else cachedVendor = VENDOR_UNKNOWN;

        return cachedVendor;
    }

    /**
     * True when an entry should be shown on this device.
     * A null or empty gpuVendor means "any GPU", and an unknown local vendor
     * shows everything rather than hiding everything - failing open, because
     * hiding the whole list on an unrecognised GPU looks like a broken screen.
     * 이 기기에서 해당 항목을 보여 줘야 하면 true임.
     * gpuVendor가 null이거나 비어 있으면 "모든 GPU"라는 뜻이고, 로컬 벤더를
     * 알 수 없으면 전부 숨기는 대신 전부 보여 줌. 인식 못 한 GPU에서 목록을
     * 통째로 숨기면 화면이 고장 난 것처럼 보이므로 열어 두는 쪽으로 실패시킴
     */
    public static boolean matches(String entryGpuVendor) {
        if (entryGpuVendor == null || entryGpuVendor.trim().isEmpty()) return true;
        String local = getVendor();
        if (VENDOR_UNKNOWN.equals(local)) return true;
        return local.equalsIgnoreCase(entryGpuVendor.trim());
    }

    /**
     * Strips everything but letters and digits, lowercased.
     * Without this, "Adreno (TM) 730" never contains "Adreno 7" and every
     * Adreno entry with a gpuMatch would vanish from the list.
     * 영숫자만 남기고 소문자로 바꿈.
     * 이게 없으면 "Adreno (TM) 730"에 "Adreno 7"이 절대 포함되지 않아서,
     * gpuMatch가 붙은 Adreno 항목이 목록에서 전부 사라짐
     */
    private static String normalize(String s) {
        // BUG FIX: trademark marks are dropped first. Real Adreno renderers read
        // "Adreno (TM) 830", which normalized to "adrenotm830" and never contained
        // "adreno8" - so every Adreno entry carrying a gpuMatch was filtered out
        // and GraphicTools showed no downloadable drivers at all.
        // 버그 수정: 상표 표기를 먼저 지움. 실제 Adreno 렌더러 문자열은
        // "Adreno (TM) 830"이라 "adrenotm830"이 되어 "adreno8"을 절대 포함하지
        // 않았음 - 그래서 gpuMatch가 있는 Adreno 항목이 전부 걸러져 GraphicTools에
        // 다운로드 가능한 드라이버가 하나도 안 떴음
        s = s.replaceAll("(?i)\\((tm|r|c)\\)|™|®", " ");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Narrower check against the renderer string, e.g. "Xclipse 920".
     * Comma separated values are OR'd, because one driver build often covers
     * several GPU generations - Turnip R5 ships a single zip for A7xx and A6xx.
     * 렌더러 문자열에 대한 더 좁은 검사임. 예: "Xclipse 920".
     * 쉼표로 나눈 값들은 OR로 묶임. 드라이버 하나가 여러 GPU 세대를 함께
     * 덮는 경우가 흔하기 때문임 - Turnip R5는 A7xx와 A6xx를 zip 하나로 냄
     */
    public static boolean matchesModel(String entryGpuMatch) {
        if (entryGpuMatch == null || entryGpuMatch.trim().isEmpty()) return true;

        String r = normalize(getRenderer());
        // Fail open when the renderer could not be read, same as matches()
        // 렌더러를 못 읽었으면 matches()와 마찬가지로 열어 두는 쪽으로 실패시킴
        if (r.isEmpty()) return true;

        for (String token : entryGpuMatch.split(",")) {
            String t = normalize(token);
            if (!t.isEmpty() && r.contains(t)) return true;
        }
        return false;
    }

    /**
     * True when this device's manufacturer matches an entry's optional
     * "manufacturer" spec. This exists for one real case: Snapdragon phones
     * from Samsung need a One UI-specific Turnip build, while every other
     * Snapdragon phone needs the plain one - same chip, different driver.
     * 이 기기의 제조사가 항목의 선택적 "manufacturer" 값과 맞으면 true임.
     * 실제 사례 하나 때문에 있음: 삼성의 스냅드래곤 폰은 One UI 전용 Turnip
     * 빌드가 필요하고, 다른 제조사의 같은 스냅드래곤 폰은 일반 빌드가
     * 필요함 - 칩은 같은데 드라이버가 다름
     *
     * A leading "!" negates the match ("!samsung" = "not Samsung"), which is
     * what lets the plain build exclude Samsung while the One UI build
     * includes only Samsung, so exactly one of the two ever shows.
     * 맨 앞에 "!"를 붙이면 반대로 뒤집힘("!samsung" = "삼성이 아님"). 이 덕분에
     * 일반 빌드는 삼성을 제외하고 One UI 빌드는 삼성만 포함하게 되어,
     * 둘 중 정확히 하나만 뜸
     */
    public static boolean matchesManufacturer(String spec) {
        if (spec == null || spec.trim().isEmpty()) return true;
        String s = spec.trim();
        boolean negate = s.startsWith("!");
        if (negate) s = s.substring(1);
        boolean isMatch = Build.MANUFACTURER != null && Build.MANUFACTURER.equalsIgnoreCase(s);
        return negate != isMatch;
    }

    /**
     * All three gates at once: vendor, model and manufacturer. Any of the
     * three specs being null simply skips that gate.
     * 벤더, 모델, 제조사 세 관문을 한 번에 봄. 셋 중 어느 값이든 null이면
     * 그 관문은 건너뜀
     */
    public static boolean matches(String entryGpuVendor, String entryGpuMatch, String entryManufacturer) {
        return matches(entryGpuVendor, entryGpuMatch) && matchesManufacturer(entryManufacturer);
    }

    /**
     * Both conditions at once. An entry must clear the vendor gate and the
     * model gate; either being null simply skips that gate.
     * 두 조건을 한 번에 봄. 벤더와 모델 관문을 모두 통과해야 하며,
     * 어느 한쪽이 null이면 그 관문은 건너뜀
     */
    public static boolean matches(String entryGpuVendor, String entryGpuMatch) {
        return matches(entryGpuVendor) && matchesModel(entryGpuMatch);
    }

    private static String readRenderer() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return "";

            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "";

            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] == 0) return "";

            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (context == EGL14.EGL_NO_CONTEXT) return "";

            // 1x1 is the smallest legal pbuffer. Nothing is ever drawn into
            // it; it exists only so a context can be made current.
            // 1x1은 허용되는 가장 작은 pbuffer임. 여기에 그리는 건 아무것도
            // 없고, 컨텍스트를 current로 만들기 위해서만 존재함
            int[] surfaceAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0);
            if (surface == EGL14.EGL_NO_SURFACE) return "";

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return "";

            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            return renderer != null ? renderer : "";
        } catch (Exception e) {
            Log.w(TAG, "Failed to read GL_RENDERER", e);
            return "";
        } finally {
            // Every handle is released even on an early return, otherwise the
            // process keeps a GL context alive for no reason.
            // 중간에 빠져나가도 핸들을 전부 반납함. 안 그러면 프로세스가
            // 쓸데없이 GL 컨텍스트를 붙들고 있게 됨
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
        }
    }
}
