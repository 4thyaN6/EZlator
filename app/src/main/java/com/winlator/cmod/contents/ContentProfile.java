package com.winlator.cmod.contents;

import androidx.annotation.NonNull;

import java.util.List;

public class ContentProfile {
    public static final String MARK_TYPE = "type";
    public static final String MARK_VERSION_NAME = "versionName";
    public static final String MARK_VERSION_CODE = "versionCode";
    public static final String MARK_DESC = "description";
    public static final String MARK_FILE_LIST = "files";
    public static final String MARK_FILE_SOURCE = "source";
    public static final String MARK_FILE_TARGET = "target";
    public static final String MARK_WINE = "wine";
    public static final String MARK_WINE_BINPATH = "binPath";
    public static final String MARK_WINE_LIBPATH = "libPath";
    public static final String MARK_WINE_PREFIX_PACK = "prefixPack";
    public static final String MARK_PROTON = "proton";
    public static final String MARK_PROTON_BINPATH = "binPath";
    public static final String MARK_PROTON_LIBPATH = "libPath";
    public static final String MARK_PROTON_PREFIX_PACK = "prefixPack";

    // New profile.json keys for the Graphic Tools type.
    // Both are optional - any profile that omits them behaves exactly as
    // before, so existing .wcp packages keep installing unchanged.
    // Graphic Tools 타입용으로 새로 쓰는 profile.json 키임.
    // 둘 다 선택 항목이라 이 키가 없는 프로필은 기존과 똑같이 동작함.
    // 따라서 이미 있는 .wcp 패키지들은 그대로 설치됨
    public static final String MARK_SUBTYPE = "subType";
    public static final String MARK_GPU_VENDOR = "gpuVendor";

    // Substring matched against GL_RENDERER, e.g. "Xclipse 920", "Adreno 7".
    // Vendor alone is not enough: ExynosTools ships one zip per Exynos
    // generation, and they are not interchangeable.
    // GL_RENDERER에 부분 문자열로 대조하는 값임. 예: "Xclipse 920", "Adreno 7".
    // 벤더만으로는 부족함. ExynosTools는 Exynos 세대별로 zip이 따로 나오고
    // 서로 호환되지 않음
    public static final String MARK_GPU_MATCH = "gpuMatch";

    // Optional manufacturer gate, e.g. "samsung" or "!samsung". See
    // GpuInfo.matchesManufacturer() for why this exists.
    // 선택적 제조사 관문임. 예: "samsung" 또는 "!samsung".
    // 왜 있는지는 GpuInfo.matchesManufacturer() 참고
    public static final String MARK_MANUFACTURER = "manufacturer";

    // Sub-type values for CONTENT_TYPE_GRAPHIC_TOOLS.
    // Kept as plain strings rather than a second enum so a new driver family
    // can be added by editing contents.json alone, with no app update.
    // CONTENT_TYPE_GRAPHIC_TOOLS의 하위 분류 값임.
    // 두 번째 enum이 아니라 문자열로 둔 이유는, 새 드라이버 계열이 생겼을 때
    // 앱 업데이트 없이 contents.json만 고쳐서 추가할 수 있게 하기 위함
    public static final String SUBTYPE_ADRENOTOOLS = "adrenotools";

    // Optional catalog key naming who distributes a GraphicTools build. It drives
    // the StevenMXZ / The412Banner sub-tabs in ContentsFragment.
    // GraphicTools 빌드의 배포처를 나타내는 선택 키임. ContentsFragment의
    // StevenMXZ / The412Banner 하위 탭 분류에 씀
    public static final String MARK_PROVIDER = "provider";
    public static final String PROVIDER_STEVENMXZ = "stevenmxz";
    public static final String PROVIDER_THE412BANNER = "the412banner";

    /**
     * Provider guessed from the download URL, or null when the host is not one
     * we recognise. ExynosTools is grouped with StevenMXZ because both are the
     * stable, recommended sources shown on the default sub-tab.
     * 다운로드 URL로 배포처를 추정함. 모르는 호스트면 null임. ExynosTools는
     * 기본 하위 탭에 나오는 안정/권장 배포처라 StevenMXZ와 같은 그룹으로 묶음
     */
    public static String guessProvider(String url) {
        if (url == null) return null;
        String u = url.toLowerCase(java.util.Locale.ENGLISH);
        if (u.contains("github.com/stevenmxz/") || u.contains("github.com/wearyconcern1165/")) return PROVIDER_STEVENMXZ;
        if (u.contains("github.com/the412banner/")) return PROVIDER_THE412BANNER;
        return null;
    }
    public static final String SUBTYPE_EXYNOSTOOLS = "exynostools";

    public enum ContentType {
        CONTENT_TYPE_WINE("Wine"),
        CONTENT_TYPE_PROTON("Proton"),
        CONTENT_TYPE_DXVK("DXVK"),
        CONTENT_TYPE_VKD3D("VKD3D"),
        CONTENT_TYPE_BOX64("Box64"),
        CONTENT_TYPE_WOWBOX64("WOWBox64"),
        CONTENT_TYPE_FEXCORE("FEXCore"),

        // Appended at the END on purpose. ContentsFragment line 126 maps the
        // spinner position straight onto ContentType.values()[position], so
        // inserting anywhere else would silently shift every existing type
        // by one and hand the user the wrong list.
        // 일부러 맨 끝에 붙임. ContentsFragment 126번째 줄이 스피너 위치를
        // ContentType.values()[position]에 그대로 대응시키므로, 중간에 끼워
        // 넣으면 기존 타입이 전부 한 칸씩 밀려서 엉뚱한 목록이 뜸
        CONTENT_TYPE_GRAPHIC_TOOLS("GraphicTools");

        final String typeName;

        ContentType(String typeName) {
            this.typeName = typeName;
        }

        @NonNull
        @Override
        public String toString() {
            return typeName;
        }

        public static ContentType getTypeByName(String name) {
            for (ContentType type : ContentType.values())
                if (type.typeName.toLowerCase().equals(name.toLowerCase()))
                    return type;
            return null;
        }
    }

    public static class ContentFile {
        public String source;
        public String target;
    }

    public ContentType type;
    public String verName;
    public int verCode;
    public String desc;
    public List<ContentFile> fileList;
    public String wineLibPath;
    public String wineBinPath;
    public String winePrefixPack;
    public String protonLibPath;

    public String protonBinPath;
    public String protonPrefixPack;
    public String remoteUrl;

    // Graphic Tools only. null for every other type.
    // Graphic Tools 전용임. 다른 타입에서는 전부 null임
    public String subType;

    // GPU family this build targets: "adreno", "mali", "xclipse".
    // null means "applies to any GPU" and the entry is never filtered out.
    // 이 빌드가 겨냥하는 GPU 계열임: "adreno", "mali", "xclipse".
    // null이면 "모든 GPU 대상"이라는 뜻이라 절대 걸러지지 않음
    public String gpuVendor;

    // Narrower model filter, checked on top of gpuVendor. null = no narrowing.
    // gpuVendor 위에 한 겹 더 좁히는 모델 조건임. null이면 좁히지 않음
    public String gpuMatch;

    // Manufacturer gate on top of gpuVendor/gpuMatch. null = any manufacturer.
    // gpuVendor/gpuMatch 위에 한 겹 더 두는 제조사 관문임. null이면 모든 제조사
    public String manufacturer;

    // GraphicTools only: PROVIDER_* value, null = unknown
    // GraphicTools 전용: PROVIDER_* 값, null이면 미상
    public String provider;
}
