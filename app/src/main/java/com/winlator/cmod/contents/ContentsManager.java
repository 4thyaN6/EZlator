package com.winlator.cmod.contents;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ContentsManager {
    public static final String PROFILE_NAME = "profile.json";
    public static final String REMOTE_PROFILES = "contents.json";
    public static final String[] DXVK_TRUST_FILES = {"${system32}/d3d8.dll", "${system32}/d3d9.dll", "${system32}/d3d10.dll", "${system32}/d3d10_1.dll",
            "${system32}/d3d10core.dll", "${system32}/d3d11.dll", "${system32}/dxgi.dll", "${syswow64}/d3d8.dll", "${syswow64}/d3d9.dll", "${syswow64}/d3d10.dll",
            "${syswow64}/d3d10_1.dll", "${syswow64}/d3d10core.dll", "${syswow64}/d3d11.dll", "${syswow64}/dxgi.dll"};
    public static final String[] VKD3D_TRUST_FILES = {"${system32}/d3d12core.dll", "${system32}/d3d12.dll",
            "${syswow64}/d3d12core.dll", "${syswow64}/d3d12.dll"};
    public static final String[] BOX64_TRUST_FILES = {"${bindir}/box64"};
    public static final String[] WOWBOX64_TRUST_FILES = {"${system32}/wowbox64.dll"};
    public static final String[] FEXCORE_TRUST_FILES = {"${system32}/libwow64fex.dll", "${system32}/libarm64ecfex.dll"};
    private Map<String, String> dirTemplateMap;
    private Map<ContentProfile.ContentType, List<String>> trustedFilesMap;

    private SharedPreferences preferences;

    public enum InstallFailedReason {
        ERROR_NOSPACE,
        ERROR_BADTAR,
        ERROR_NOPROFILE,
        ERROR_BADPROFILE,
        ERROR_MISSINGFILES,
        ERROR_EXIST,
        ERROR_UNTRUSTPROFILE,
        ERROR_UNKNOWN
    }

    public enum ContentDirName {
        CONTENT_MAIN_DIR_NAME("contents"),
        CONTENT_WINE_DIR_NAME("wine"),
        CONTENT_PROTON_DIR_NAME("proton"),
        CONTENT_DXVK_DIR_NAME("dxvk"),
        CONTENT_VKD3D_DIR_NAME("vkd3d"),
        CONTENT_BOX64_DIR_NAME("box64");

        private String name;

        ContentDirName(String name) {
            this.name = name;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private final Context context;

    private HashMap<ContentProfile.ContentType, List<ContentProfile>> profilesMap;

    private ArrayList<ContentProfile> remoteProfiles;

    public ContentsManager(Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences("contents_manager_prefs", Context.MODE_PRIVATE);
    }

    // Method to mark the graphics driver as installed
    public void setGraphicsDriverInstalled(String driverVersion, boolean installed) {
        preferences.edit().putBoolean("graphics_driver_installed_" + driverVersion, installed).apply();
    }

    public interface OnInstallFinishedCallback {
        void onFailed(InstallFailedReason reason, Exception e);

        void onSucceed(ContentProfile profile);
    }

    public void setRemoteProfiles(String json) {
        remoteProfiles = parseRemoteProfiles(json, null);
        syncContents();
    }

    /**
     * Merges another catalog into the current one instead of replacing it.
     * setRemoteProfiles() used to be called again with the live The412Banner
     * catalog, which wiped every bundled entry - including the StevenMXZ
     * drivers in assets/contents.json. Entries are de-duplicated by URL.
     * 현재 카탈로그를 교체하지 않고 다른 카탈로그를 병합함. 예전엔 실시간
     * The412Banner 카탈로그로 setRemoteProfiles()를 다시 호출해서 번들 항목이
     * 전부 지워졌음 - assets/contents.json의 StevenMXZ 드라이버 포함. URL 기준으로
     * 중복을 제거함
     */
    public void mergeRemoteProfilesJson(String json, String defaultProvider) {
        mergeRemoteProfiles(parseRemoteProfiles(json, defaultProvider));
    }

    public synchronized void mergeRemoteProfiles(List<ContentProfile> extra) {
        if (remoteProfiles == null) remoteProfiles = new ArrayList<>();
        java.util.HashSet<String> urls = new java.util.HashSet<>();
        for (ContentProfile p : remoteProfiles) if (p.remoteUrl != null) urls.add(p.remoteUrl);
        if (extra != null) {
            for (ContentProfile p : extra) {
                if (p.remoteUrl != null && urls.add(p.remoteUrl)) remoteProfiles.add(p);
            }
        }
        syncContents();
    }

    // Records which provider an installed driver came from, so the sub-tabs can
    // show it under the right source. Manual file installs record nothing and
    // therefore appear on both sub-tabs.
    // 설치된 드라이버가 어느 배포처에서 왔는지 기록함. 하위 탭에서 맞는 쪽에
    // 보여주기 위함. 파일로 직접 설치한 드라이버는 기록이 없어 양쪽 탭에 다 보임
    public void setDriverProvider(String driverName, String provider) {
        if (driverName == null || driverName.isEmpty()) return;
        if (provider == null) preferences.edit().remove("driver_provider_" + driverName).apply();
        else preferences.edit().putString("driver_provider_" + driverName, provider).apply();
    }

    public String getDriverProvider(String driverName) {
        return preferences.getString("driver_provider_" + driverName, null);
    }

    private static ArrayList<ContentProfile> parseRemoteProfiles(String json, String defaultProvider) {
        ArrayList<ContentProfile> result = new ArrayList<>();
        ArrayList<ContentProfile> remoteProfiles = result;
        if (json == null) return result;
        try {
            JSONArray content = new JSONArray(json);
            for (int i = 0; i < content.length(); i++) {
                try {
                    JSONObject object = content.getJSONObject(i);
                    ContentProfile remoteProfile = new ContentProfile();
                    remoteProfile.remoteUrl = object.getString("remoteUrl");
                    remoteProfile.type = ContentProfile.ContentType.getTypeByName(object.getString("type"));
                    remoteProfile.verName = object.getString("verName");
                    remoteProfile.verCode = object.getInt("verCode");

                    // optString, not getString: both keys are optional, and
                    // getString throws on a missing key, which would drop the
                    // whole entry from the catalog.
                    // getString이 아니라 optString임. 두 키 모두 선택 항목인데
                    // getString은 키가 없으면 예외를 던져서 그 항목이 카탈로그에서
                    // 통째로 빠져버림
                    remoteProfile.subType = object.optString(ContentProfile.MARK_SUBTYPE, null);
                    remoteProfile.gpuVendor = object.optString(ContentProfile.MARK_GPU_VENDOR, null);
                    remoteProfile.gpuMatch = object.optString(ContentProfile.MARK_GPU_MATCH, null);
                    remoteProfile.manufacturer = object.optString(ContentProfile.MARK_MANUFACTURER, null);

                    // Provider: explicit key > known URL > caller's default
                    // 배포처: 명시된 키 > 알려진 URL > 호출부 기본값 순으로 정함
                    String provider = object.optString(ContentProfile.MARK_PROVIDER, null);
                    if (provider == null || provider.isEmpty()) provider = ContentProfile.guessProvider(remoteProfile.remoteUrl);
                    if (provider == null) provider = defaultProvider;
                    remoteProfile.provider = provider;

                    if (remoteProfile.type != null) remoteProfiles.add(remoteProfile);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return result;
    }

    public void syncContents() {
        profilesMap = new HashMap<>();

        // Ensure all content types are initialized in the profilesMap
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            profilesMap.put(type, new LinkedList<>());
        }

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> profiles = profilesMap.get(type);


            // Load local profiles
            File typeFile = getContentTypeDir(context, type);
            File[] fileList = typeFile.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    File proFile = new File(file, PROFILE_NAME);
                    if (proFile.exists() && proFile.isFile()) {
                        ContentProfile profile = readProfile(proFile);
                        if (profile != null) {
                            profiles.add(profile);
                            Log.d("ContentsManager", "Local profile loaded: " + profile.verName);
                        } else {
                            Log.w("ContentsManager", "Invalid local profile at: " + proFile.getAbsolutePath());
                        }
                    }
                }
            }

            // bug #7 fix: GraphicTools drivers never install through the
            // profile.json/getContentTypeDir() path above - AdrenotoolsManager
            // extracts them straight into imagefs/contents/adrenotools/<id>/
            // as meta.json, a completely separate location and file format.
            // Without this, an installed driver could never match a local
            // profile, so it always looked "not installed" in the Contents list.
            // bug #7 수정: GraphicTools 드라이버는 위 profile.json/
            // getContentTypeDir() 경로로는 절대 설치되지 않음.
            // AdrenotoolsManager가 imagefs/contents/adrenotools/<id>/ 아래에
            // meta.json 형식으로 완전히 별도 위치에 압축을 풂. 이걸 안 챙기면
            // 설치된 드라이버가 로컬 프로필과 매칭될 방법이 없어서 Contents
            // 목록에서 항상 "설치 안 됨"으로 보임
            if (type == ContentProfile.ContentType.CONTENT_TYPE_GRAPHIC_TOOLS) {
                try {
                    for (ContentProfile installed : new AdrenotoolsManager(context).getInstalledProfiles()) {
                        profiles.add(installed);
                        Log.d("ContentsManager", "Installed AdrenoTools driver found: " + installed.verName);
                    }
                } catch (Exception e) {
                    // Whatever went wrong reading installed drivers, the
                    // remote/downloadable entries below must still get
                    // added - an exception here used to abort the rest of
                    // this method, since GraphicTools is the last type in
                    // the enum, silently leaving the whole tab empty
                    // 설치된 드라이버를 읽다가 뭐가 잘못됐든, 아래 원격/
                    // 다운로드 가능 항목은 반드시 계속 추가되어야 함 -
                    // GraphicTools가 enum의 마지막 타입이라, 여기서 예외가
                    // 나면 예전엔 이 메서드의 나머지가 전부 중단되어 탭
                    // 전체가 조용히 비어 보였음
                    Log.w("ContentsManager", "Failed to read installed AdrenoTools drivers", e);
                }
            }

            // Add remote profiles for this type
            if (remoteProfiles != null) {
                for (ContentProfile remote : remoteProfiles) {
                    if (remote.type == type) {
                        boolean exists = false;
                        for (ContentProfile profile : profiles) {
                            if (profile.verName.equals(remote.verName) && profile.verCode == remote.verCode) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            profiles.add(remote);
                            Log.d("ContentsManager", "Remote profile added: " + remote.verName);
                        }
                    }
                }
            }
        }
    }

    public void extraContentFile(Uri uri, OnInstallFinishedCallback callback) {
        cleanTmpDir(context);

        File file = getTmpDir(context);

        boolean ret;
        ret = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, file);
        if (!ret)
            ret = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, uri, file);
        if (!ret) {
            callback.onFailed(InstallFailedReason.ERROR_BADTAR, null);
            return;
        }

        File proFile = new File(file, PROFILE_NAME);
        if (!proFile.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_NOPROFILE, null);
            return;
        }

        ContentProfile profile = readProfile(proFile);
        if (profile == null) {
            callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, null);
            return;
        }

        // GameNative's Winlator CMOD layer packages (proton-wine-*.wcp.xz) declare
        // themselves as "Wine". They are Proton builds, so they are installed as a
        // Proton layer named "<version>-proton-wine" (see markAsProtonWine())
        // GameNative의 Winlator CMOD용 레이어 패키지(proton-wine-*.wcp.xz)는 스스로를
        // "Wine"으로 표기함. 실제로는 Proton 빌드이므로 "<버전>-proton-wine" 이름의
        // Proton 레이어로 설치함(markAsProtonWine() 참고)
        if (isProtonWinePackage(profile)) markAsProtonWine(proFile, profile);

        String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File tmpFile = new File(file, contentFile.source);
            if (!tmpFile.exists() || !tmpFile.isFile() || !isSubPath(file.getAbsolutePath(), tmpFile.getAbsolutePath())) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }

            String realPath = getPathFromTemplate(contentFile.target);
            if (!isSubPath(imagefsPath, realPath) || isSubPath(ContentsManager.getContentDir(context).getAbsolutePath(), realPath) || realPath.contains("dosdevices")) {
                callback.onFailed(InstallFailedReason.ERROR_UNTRUSTPROFILE, null);
                return;
            }
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE) {
            File bin = new File(file, profile.wineBinPath);
            File lib = new File(file, profile.wineLibPath);
            File cp = new File(file, profile.winePrefixPack);

            if (!bin.exists() || !bin.isDirectory() || !lib.exists() || !lib.isDirectory() || !cp.exists() || !cp.isFile()) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            File bin = new File(file, profile.protonBinPath);
            File lib = new File(file, profile.protonLibPath);
            File cp = new File(file, profile.protonPrefixPack);

            if (!bin.exists() || !bin.isDirectory() || !lib.exists() || !lib.isDirectory() || !cp.exists() || !cp.isFile()) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }
        }

        callback.onSucceed(profile);
    }

    // Name suffix of a proton-wine layer, e.g. "11.0-2-arm64ec-proton-wine"
    // proton-wine 레이어의 이름 꼬리표. 예: "11.0-2-arm64ec-proton-wine"
    public static final String PROTON_WINE_SUFFIX = "-proton-wine";

    // A "Wine" package whose version name or description says Proton
    // 버전 이름이나 설명에 Proton이 들어 있는 "Wine" 패키지
    private static boolean isProtonWinePackage(ContentProfile profile) {
        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE) return false;
        String text = (profile.verName + " " + (profile.desc != null ? profile.desc : ""))
                .toLowerCase(java.util.Locale.ENGLISH);
        return text.contains("proton");
    }

    // Rewrites the extracted profile.json as a Proton profile before it is moved
    // into place, so every later sync reads it as Proton without any special case.
    // The version name keeps the "<digits>-...-<arch>" shape WineInfo parses:
    // any leading non-digit prefix (e.g. "proton-wine-") is cut, then the
    // "-proton-wine" suffix is added. The paths stay under the "wine" key, which
    // readProfile() already accepts for Proton. On any error the package is left
    // as it was (a Wine layer, which also works).
    // 압축을 푼 profile.json을 제자리로 옮기기 전에 Proton 프로필로 고쳐 씀. 그래서
    // 이후 동기화는 특별 처리 없이 Proton으로 읽음. 버전 이름은 WineInfo가 해석하는
    // "<숫자>-...-<아키텍처>" 모양을 유지함: 앞쪽의 숫자 아닌 접두어(예:
    // "proton-wine-")는 떼고 "-proton-wine" 꼬리표를 붙임. 경로는 "wine" 키에 그대로
    // 두며, readProfile()이 Proton에도 이미 받아들임. 오류가 나면 패키지를 그대로
    // 둠(Wine 레이어로도 동작함)
    private void markAsProtonWine(File proFile, ContentProfile profile) {
        try {
            String ver = profile.verName;
            int firstDigit = 0;
            while (firstDigit < ver.length() && !Character.isDigit(ver.charAt(firstDigit))) firstDigit++;
            if (firstDigit < ver.length()) ver = ver.substring(firstDigit);
            if (!ver.endsWith(PROTON_WINE_SUFFIX)) ver += PROTON_WINE_SUFFIX;

            JSONObject json = new JSONObject(FileUtils.readString(proFile));
            json.put(ContentProfile.MARK_TYPE, ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString());
            json.put(ContentProfile.MARK_VERSION_NAME, ver);
            if (!FileUtils.writeString(proFile, json.toString(2))) return;

            profile.type = ContentProfile.ContentType.CONTENT_TYPE_PROTON;
            profile.verName = ver;
            profile.protonBinPath = profile.wineBinPath;
            profile.protonLibPath = profile.wineLibPath;
            profile.protonPrefixPack = profile.winePrefixPack;
            Log.i("ContentsManager", "proton-wine package installed as Proton: " + ver);
        } catch (Exception e) {
            Log.w("ContentsManager", "proton-wine conversion skipped", e);
        }
    }

    public void finishInstallContent(ContentProfile profile, OnInstallFinishedCallback callback) {
        File installPath = getInstallDir(context, profile);
        if (installPath.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_EXIST, null);
            return;
        }

        if (!installPath.mkdirs()) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        if (!getTmpDir(context).renameTo(installPath)) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
        }

        callback.onSucceed(profile);
    }

    public ContentProfile readProfile(File file) {
        try {
            ContentProfile profile = new ContentProfile();
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            String typeName = profileJSONObject.getString(ContentProfile.MARK_TYPE);
            String verName = profileJSONObject.getString(ContentProfile.MARK_VERSION_NAME);
            int verCode = profileJSONObject.getInt(ContentProfile.MARK_VERSION_CODE);
            String desc = profileJSONObject.getString(ContentProfile.MARK_DESC);

            JSONArray fileJSONArray = profileJSONObject.getJSONArray(ContentProfile.MARK_FILE_LIST);
            List<ContentProfile.ContentFile> fileList = new ArrayList<>();
            for (int i = 0; i < fileJSONArray.length(); i++) {
                JSONObject contentFileJSONObject = fileJSONArray.getJSONObject(i);
                ContentProfile.ContentFile contentFile = new ContentProfile.ContentFile();
                contentFile.source = contentFileJSONObject.getString(ContentProfile.MARK_FILE_SOURCE);
                contentFile.target = contentFileJSONObject.getString(ContentProfile.MARK_FILE_TARGET);
                fileList.add(contentFile);
            }
            if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString())) {
                JSONObject wineJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_WINE);
                profile.wineLibPath = wineJSONObject.getString(ContentProfile.MARK_WINE_LIBPATH);
                profile.wineBinPath = wineJSONObject.getString(ContentProfile.MARK_WINE_BINPATH);
                profile.winePrefixPack = wineJSONObject.getString(ContentProfile.MARK_WINE_PREFIX_PACK);
            }
            // 수정 - "proton" 키가 없으면 "wine" 키로 대체해서 읽음
            // Some third-party .wcp packages (e.g. The412Banner's proton-wine builds) use
            // the "wine" key for Proton profiles too, instead of a separate "proton" key.
            // Fall back to "wine" so those packages install correctly.
            // 일부 외부 .wcp 패키지(예: The412Banner의 proton-wine 빌드)는 Proton 프로필에도
            // 별도의 "proton" 키 대신 "wine" 키를 그대로 씁니다. 이런 패키지도 정상 설치되도록
            // "proton" 키가 없으면 "wine" 키로 대신 읽습니다.
            if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString())) {
                JSONObject protonJSONObject = profileJSONObject.has(ContentProfile.MARK_PROTON)
                        ? profileJSONObject.getJSONObject(ContentProfile.MARK_PROTON)
                        : profileJSONObject.getJSONObject(ContentProfile.MARK_WINE);
                profile.protonLibPath = protonJSONObject.getString(ContentProfile.MARK_PROTON_LIBPATH);
                profile.protonBinPath = protonJSONObject.getString(ContentProfile.MARK_PROTON_BINPATH);
                profile.protonPrefixPack = protonJSONObject.getString(ContentProfile.MARK_PROTON_PREFIX_PACK);
            }

            // Same optional keys as the remote catalog, so a locally installed
            // driver package keeps its GPU tag after a restart.
            // 원격 카탈로그와 같은 선택 키임. 로컬에 설치된 드라이버 패키지가
            // 재시작 뒤에도 GPU 표시를 유지하게 함
            profile.subType = profileJSONObject.optString(ContentProfile.MARK_SUBTYPE, null);
            profile.gpuVendor = profileJSONObject.optString(ContentProfile.MARK_GPU_VENDOR, null);
            profile.gpuMatch = profileJSONObject.optString(ContentProfile.MARK_GPU_MATCH, null);
            profile.manufacturer = profileJSONObject.optString(ContentProfile.MARK_MANUFACTURER, null);

            profile.type = ContentProfile.ContentType.getTypeByName(typeName);
            profile.verName = verName;
            profile.verCode = verCode;
            profile.desc = desc;
            profile.fileList = fileList;
            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    public List<ContentProfile> getProfiles(ContentProfile.ContentType type) {
        if (profilesMap != null)
            return profilesMap.get(type);
        return null;
    }

    public static File getInstallDir(Context context, ContentProfile profile) {
        return new File(getContentTypeDir(context, profile.type), profile.verName + "-" + profile.verCode);
    }

    public static File getContentDir(Context context) {
        return new File(context.getFilesDir(), ContentDirName.CONTENT_MAIN_DIR_NAME.toString());
    }

    public static File getContentTypeDir(Context context, ContentProfile.ContentType type) {
        return new File(getContentDir(context), type.toString());
    }

    public static File getTmpDir(Context context) {
        return new File(context.getFilesDir(), "tmp/" + ContentDirName.CONTENT_MAIN_DIR_NAME);
    }

    public static File getSourceFile(Context context, ContentProfile profile, String path) {
        return new File(getInstallDir(context, profile), path);
    }

    public static void cleanTmpDir(Context context) {
        File file = getTmpDir(context);
        FileUtils.delete(file);
        file.mkdirs();
    }

    public List<ContentProfile.ContentFile> getUnTrustedContentFiles(ContentProfile profile) {
        createTrustedFilesMap();
        List<ContentProfile.ContentFile> files = new ArrayList<>();
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            if (!trustedFilesMap.get(profile.type).contains(
                    Paths.get(getPathFromTemplate(contentFile.target)).toAbsolutePath().normalize().toString()))
                files.add(contentFile);
        }
        return files;
    }

    private boolean isSubPath(String parent, String child) {
        return Paths.get(child).toAbsolutePath().normalize().startsWith(Paths.get(parent).toAbsolutePath().normalize());
    }

    private void createDirTemplateMap() {
        if (dirTemplateMap == null) {
            dirTemplateMap = new HashMap<>();
            String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
            String drivecPath = imagefsPath + "/home/xuser/.wine/drive_c";
            dirTemplateMap.put("${libdir}", imagefsPath + "/usr/lib");
            dirTemplateMap.put("${system32}", drivecPath + "/windows/system32");
            dirTemplateMap.put("${syswow64}", drivecPath + "/windows/syswow64");
            dirTemplateMap.put("${bindir}", imagefsPath + "/usr/bin");
            dirTemplateMap.put("${sharedir}", imagefsPath + "/usr/share");
        }
    }

    private void createTrustedFilesMap() {
        if (trustedFilesMap == null) {
            trustedFilesMap = new HashMap<>();
            for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
                List<String> pathList = new ArrayList<>();
                trustedFilesMap.put(type, pathList);

                String[] paths = switch (type) {
                    case CONTENT_TYPE_DXVK -> DXVK_TRUST_FILES;
                    case CONTENT_TYPE_VKD3D -> VKD3D_TRUST_FILES;
                    case CONTENT_TYPE_BOX64 -> BOX64_TRUST_FILES;
                    case CONTENT_TYPE_WOWBOX64 -> WOWBOX64_TRUST_FILES;
                    case CONTENT_TYPE_FEXCORE -> FEXCORE_TRUST_FILES;
                    default -> new String[0];
                };
                for (String path : paths)
                    pathList.add(Paths.get(getPathFromTemplate(path)).toAbsolutePath().normalize().toString());
            }
        }
    }

    private String getPathFromTemplate(String path) {
        createDirTemplateMap();
        String realPath = path;
        for (String key : dirTemplateMap.keySet()) {
            realPath = realPath.replace(key, dirTemplateMap.get(key));
        }
        return realPath;
    }

    public void removeContent(ContentProfile profile) {
        if (profilesMap.get(profile.type).contains(profile)) {
            // GraphicTools drivers live under AdrenotoolsManager's own
            // directory (see the "bug #7 fix" comment in syncContents()),
            // not under getInstallDir() like every other content type, so
            // deleting through getInstallDir() here would silently no-op
            // and the driver would just reappear on the next syncContents()
            // GraphicTools 드라이버는 AdrenotoolsManager 자체 디렉터리
            // 아래에 있음(syncContents()의 "bug #7 fix" 주석 참고). 다른
            // 콘텐츠 타입처럼 getInstallDir()로 지우면 조용히 아무 일도
            // 안 일어나고, 다음 syncContents() 때 드라이버가 도로 나타남
            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_GRAPHIC_TOOLS) {
                new AdrenotoolsManager(context).removeDriver(profile.verName);
            } else {
                FileUtils.delete(getInstallDir(context, profile));
            }
            profilesMap.get(profile.type).remove(profile);
            syncContents();
        }
    }

    public static String getEntryName(ContentProfile profile) {
        return profile.type.toString() + '-' + profile.verName + '-' + profile.verCode;
    }

    public ContentProfile getProfileByEntryName(String entryName) {
        int firstDashIndex = entryName.indexOf('-');
        int lastDashIndex = entryName.lastIndexOf('-');

        try {
            String typeName = entryName.substring(0, firstDashIndex);
            String versionName = entryName.substring(firstDashIndex + 1, lastDashIndex);
            String versionCode = entryName.substring(lastDashIndex + 1);

            for (ContentProfile profile : profilesMap.get(ContentProfile.ContentType.getTypeByName(typeName))) {
                if (versionName.equals(profile.verName) && Integer.parseInt(versionCode) == profile.verCode)
                    return profile;
            }
        } catch (Exception e) {
        }

        return null;
    }

    public boolean applyContent(ContentProfile profile) {
        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            for (ContentProfile.ContentFile contentFile : profile.fileList) {
                File targetFile = new File(getPathFromTemplate(contentFile.target));
                File sourceFile = new File(getInstallDir(context, profile), contentFile.source);

                targetFile.delete();
                FileUtils.copy(sourceFile, targetFile);

                if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_BOX64) {
                    FileUtils.chmod(targetFile, 0771);
                }
            }
        }
        return true;
    }
}
