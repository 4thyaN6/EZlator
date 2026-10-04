package com.winlator.cmod.container;

import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

public class Container {
    public static final String DEFAULT_ENV_VARS = "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 TU_DEBUG=noconform,sysmem MANGOHUD=0 MANGOHUD_CONFIG=engine_version,gpu_stats=0";
    // DXVK_HUD removed from the defaults: its 6-line block drew over the game
    // image in every DXVK game. The HUD is now the "Show FPS" option, drawn as a
    // one-line MangoHud-style bar at the top (see XServerDisplayActivity.applyHudPolicy()).
    // 기본값에서 DXVK_HUD를 뺌: 6줄짜리 블록이 모든 DXVK 게임 화면 위를 가렸음.
    // HUD는 이제 "FPS 표시" 옵션이며, 상단 한 줄짜리 MangoHud 방식 바로 그림
    // (XServerDisplayActivity.applyHudPolicy() 참고)
    public static final String LEGACY_DEFAULT_DXVK_HUD = "devinfo,fps,frametimes,gpuload,version,api";
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    public static final String DEFAULT_GRAPHICS_DRIVER = "wrapper";
    public static final String DEFAULT_AUDIO_DRIVER = "alsa-reflector";
    public static final String DEFAULT_EMULATOR = "FEXCore";
    // dxwrapper_entries only produces "wined3d" and "dxvk-vkd3d" since DXVK and
    // VKD3D were merged into one option. The old "dxvk" value matched neither, so
    // new containers silently fell back to spinner index 0 (WineD3D) and
    // extractDXWrapperFiles() took no branch at all - no DLLs were ever installed.
    // DXVK와 VKD3D가 하나의 옵션으로 통합된 뒤 dxwrapper_entries는 "wined3d"와
    // "dxvk-vkd3d"만 만들어냄. 옛 값 "dxvk"는 둘 중 어느 것도 아니라서 새 컨테이너가
    // 조용히 0번(WineD3D)으로 떨어졌고, extractDXWrapperFiles()도 어느 분기도 타지
    // 않아 DLL이 전혀 설치되지 않았음
    public static final String DEFAULT_DXWRAPPER = "dxvk-vkd3d";
    public static final String DEFAULT_DXWRAPPERCONFIG = "version=" + DefaultVersion.DXVK + ",framerate=0,maxDeviceMemory=0,async=0,asyncCache=0" + ",vkd3dVersion=" + DefaultVersion.VKD3D + ",vkd3dLevel=12_1";
    public static final String DEFAULT_GRAPHICSDRIVERCONFIG = "version=" + DefaultVersion.WRAPPER + ";blacklistedExtensions=" + ";maxDeviceMemory=0" + ";adrenotoolsTurnip=1" + ";frameSync=Normal";
    public static final String DEFAULT_DDRAWRAPPER = "wined3d";
    // vcrun2015 (Visual C++ 2015-2022) is not offered for now: its DLLs may not be
    // shipped inside the APK, so it waits for a download-once-into-imagefs installer.
    // Its DLL list stays in wincomponents.json so containers that had it turned on
    // can be put back to Wine's builtin DLLs (see offeredWinComponents())
    // vcrun2015(Visual C++ 2015~2022)는 당분간 제공하지 않음: DLL을 APK에 넣어 배포할 수
    // 없으므로, 한 번 받아 imagefs에 두는 설치 방식이 생길 때까지 보류함. DLL 목록은
    // wincomponents.json에 남겨서 이 항목을 켰던 컨테이너를 Wine 기본 DLL로 되돌릴 때 씀
    // (offeredWinComponents() 참고)
    public static final String DEFAULT_WINCOMPONENTS = "direct3d=1,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,vcrun2010=1,opengl=0";
    public static final String FALLBACK_WINCOMPONENTS = "direct3d=1,directsound=1,directmusic=1,directshow=1,directplay=1,xaudio=1,vcrun2010=1,opengl=0";

    // Paths use the IMAGEFS_TOKEN placeholder instead of a hardcoded
    // /data/data/<package> path, which broke as soon as the applicationId
    // changed. XServerDisplayActivity swaps the token for the real imagefs root.
    // 하드코딩된 /data/data/<패키지> 경로 대신 IMAGEFS_TOKEN 자리표시자를 씀.
    // applicationId가 바뀌면 바로 깨지기 때문임. 실제 imagefs 경로로의 치환은
    // XServerDisplayActivity에서 함
    public static final String IMAGEFS_TOKEN = "@IMAGEFS@";
    public static final String[] MEDIACONV_ENV_VARS = {
            "MEDIACONV_AUDIO_DUMP_FILE=" + IMAGEFS_TOKEN + "/home/xuser/audio.dmp",
            "MEDIACONV_VIDEO_DUMP_FILE=" + IMAGEFS_TOKEN + "/home/xuser/video.dmp",
            "MEDIACONV_VIDEO_TRANSCODED_FILE=" + IMAGEFS_TOKEN + "/home/xuser/transcoded.mkv",
            "MEDIACONV_AUDIO_TRANSCODED_FILE=" + IMAGEFS_TOKEN + "/home/xuser/transcoded.wav",
            "MEDIACONV_BLANK_AUDIO_FILE=" + IMAGEFS_TOKEN + "/home/xuser/blank.wav",
            "MEDIACONV_BLANK_VIDEO_FILE=" + IMAGEFS_TOKEN + "/home/xuser/blank.mkv",
    };
    // E: (app-private /data/data/<pkg>/storage) was dropped from the defaults:
    // Android no longer lets file managers reach it, so it was a drive users
    // could not fill. Removable volumes (SD/USB) are now auto-mapped from E:
    // at launch - see StorageDrives. Existing containers keep their saved list.
    // 기본값에서 E:(앱 전용 /data/data/<패키지>/storage)를 뺌: 최근 안드로이드는
    // 파일 관리자가 그 경로에 못 들어가서 사용자가 채울 수 없는 드라이브였음.
    // 대신 착탈식 볼륨(SD/USB)을 실행 시점에 E:부터 자동 매핑함(StorageDrives 참고).
    // 이미 만든 컨테이너는 저장된 목록을 그대로 씀
    // Fallback / debug default (Download). New containers get their list from
    // StorageDrives.defaultDrives(), which uses the internal EZlator folder in
    // release builds. Existing containers keep their saved list.
    // 대체용/디버그 기본값(다운로드). 새 컨테이너는 StorageDrives.defaultDrives()에서
    // 목록을 받으며, 릴리즈 빌드에서는 내장 EZlator 폴더를 씀. 기존 컨테이너는
    // 저장된 목록을 그대로 씀
    public static final String DEFAULT_DRIVES = "D:"+Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    public static final byte STARTUP_SELECTION_NORMAL = 0;
    public static final byte STARTUP_SELECTION_ESSENTIAL = 1;
    public static final byte STARTUP_SELECTION_AGGRESSIVE = 2;
    public static final byte MAX_DRIVE_LETTERS = 26;
    public final int id;
    private String name;
    private String screenSize = DEFAULT_SCREEN_SIZE;
    private String envVars = DEFAULT_ENV_VARS;
    private String graphicsDriver = DEFAULT_GRAPHICS_DRIVER;
    private String graphicsDriverConfig = DEFAULT_GRAPHICSDRIVERCONFIG;
    private String dxwrapper = DEFAULT_DXWRAPPER;
    private String ddrawrapper = DEFAULT_DDRAWRAPPER;
    // The constant above was correct but never wired to the field, so every container
    // started with an empty config - "vkd3dVersion" was missing and VKD3D fell back to
    // whatever was installed, including x86_64 builds on an arm64ec prefix.
    // 위 상수는 맞게 만들어져 있었는데 필드에 연결이 안 돼 있었음. 그래서 모든 컨테이너가
    // 빈 설정으로 시작했고 "vkd3dVersion" 키가 없어, VKD3D가 설치된 아무거나로
    // 폴백됐음(arm64ec 프리픽스에 x86_64 빌드가 깔리는 경우 포함)
    private String dxwrapperConfig = DEFAULT_DXWRAPPERCONFIG;
    private String wincomponents = DEFAULT_WINCOMPONENTS;
    private String audioDriver = DEFAULT_AUDIO_DRIVER;
    private String drives = DEFAULT_DRIVES;
    private String wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
    private boolean showFPS;
    private boolean fullscreenStretched;
    private boolean wow64Mode = true;
    private byte startupSelection = STARTUP_SELECTION_ESSENTIAL;
    private String cpuList;
    private String cpuListWoW64;
    private String desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;
    // Performance by default (crash fallback steps down per shortcut)
    // 기본값 성능(크래시가 나면 바로가기별로 한 단계씩 내림)
    private String box64Preset = Box86_64Preset.PERFORMANCE;
    private File rootDir;
    private JSONObject extraData;
    private int rcfileId = 0;
    private String midiSoundFont = "";
    private int inputType = WinHandler.DEFAULT_INPUT_TYPE;
    private String lc_all = "";
    // primaryController / controllerMapping (VR controller settings) removed with
    // XrActivity. Old container.json files still holding these keys load fine:
    // loadData() ignores keys it does not know.
    // primaryController / controllerMapping(VR 컨트롤러 설정)을 XrActivity와 함께
    // 제거함. 이 키가 남아 있는 예전 container.json도 문제없이 읽힘:
    // loadData()는 모르는 키를 무시함
    private String fexcoreVersion = DefaultVersion.FEXCORE;
    private String box64Version = DefaultVersion.BOX64;
    // DEFAULT_EMULATOR existed but was unused, leaving this null. BionicProgramLauncher
    // calls emulator.toLowerCase() directly, so a container created without this key
    // would NPE at launch.
    // DEFAULT_EMULATOR가 있는데 쓰이지 않아 null로 남아 있었음. BionicProgramLauncher가
    // emulator.toLowerCase()를 바로 호출하므로, 이 키 없이 만들어진 컨테이너는
    // 실행 시점에 NPE가 남
    private String emulator = DEFAULT_EMULATOR;

    // Recomputed at launch from StorageDrives, never persisted. Keeping it out of
    // `drives` guarantees saveData() can never write an auto mapping into the config,
    // so a swapped SD card cannot leave a stale entry behind.
    // 실행할 때 StorageDrives로 매번 다시 계산하며 저장하지 않음. `drives`와 분리해두면
    // saveData()가 자동 매핑을 설정에 쓸 일이 절대 없으므로, SD카드를 교체해도
    // 낡은 항목이 안 남음
    private String autoDrives = "";
    private boolean isRelativeMouseMovement;

    private boolean gstreamerWorkaround = false;

    private ContainerManager containerManager;



    public Container(int id) {
        this.id = id;
        this.name = "Container-"+id;
    }

    public Container(int id, ContainerManager containerManager) {
        this.id = id;
        this.name = "Container-"+id;
        this.containerManager = containerManager;
    }

    public ContainerManager getManager() {
        return containerManager;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScreenSize() {
        return screenSize;
    }

    public void setScreenSize(String screenSize) {
        this.screenSize = screenSize;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
    }

    public String getGraphicsDriver() {
        return graphicsDriver;
    }

    public void setGraphicsDriver(String graphicsDriver) {
        this.graphicsDriver = graphicsDriver;
    }

    public String getGraphicsDriverConfig() { return this.graphicsDriverConfig; }

    public void setGraphicsDriverConfig(String graphicsDriverConfig) { this.graphicsDriverConfig = graphicsDriverConfig; }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public String getDDrawWrapper() { return ddrawrapper; }

    public void setDDrawWrapper(String ddrawrapper) { this.ddrawrapper = ddrawrapper; }

    public String getDXWrapperConfig() {
        return dxwrapperConfig;
    }

    public void setDXWrapperConfig(String dxwrapperConfig) {
        this.dxwrapperConfig = dxwrapperConfig != null ? dxwrapperConfig : DEFAULT_DXWRAPPERCONFIG;
    }

    public String getAudioDriver() {
        return audioDriver;
    }

    public void setAudioDriver(String audioDriver) {
        this.audioDriver = audioDriver;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public String getDrives() {
        return drives;
    }

    public void setDrives(String drives) {
        this.drives = drives;
    }

    public String getLC_ALL() {
        return lc_all;
    }

    public void setLC_ALL(String lc_all) {
        this.lc_all = lc_all;
    }

    public boolean isRelativeMouseMovement() {
        return this.isRelativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean isRelativeMouseMovement) {
        this.isRelativeMouseMovement = isRelativeMouseMovement;
    }

    public boolean isFullscreenStretched() { return fullscreenStretched; }

    public boolean isShowFPS() {
        return showFPS;
    }

    public void setFullscreenStretched(boolean fullscreenStretched) { this.fullscreenStretched = fullscreenStretched; }

    public void setShowFPS(boolean showFPS) {
        this.showFPS = showFPS;
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public byte getStartupSelection() {
        return startupSelection;
    }

    public void setStartupSelection(byte startupSelection) {
        this.startupSelection = startupSelection;
    }

    public String getCPUList() {
        return getCPUList(false);
    }

    public String getCPUList(boolean allowFallback) {
        return cpuList != null ? cpuList : (allowFallback ? getFallbackCPUList() : null);
    }

    public void setCPUList(String cpuList) {
        this.cpuList = cpuList != null && !cpuList.isEmpty() ? cpuList : null;
    }

    public String getCPUListWoW64() {
        return getCPUListWoW64(false);
    }

    public String getCPUListWoW64(boolean allowFallback) {
        return cpuListWoW64 != null ? cpuListWoW64 : (allowFallback ? getFallbackCPUListWoW64() : null);
    }

    public void setCPUListWoW64(String cpuListWoW64) {
        this.cpuListWoW64 = cpuListWoW64 != null && !cpuListWoW64.isEmpty() ? cpuListWoW64 : null;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public String getFEXCoreVersion() { return this.fexcoreVersion; }

    public void setFEXCoreVersion(String version) { this.fexcoreVersion = version; }

    public String getBox64Version() { return box64Version; }

    public void setBox64Version(String version) { this.box64Version = version; }

    public void setEmulator(String emulator) {
        this.emulator = emulator;
    }

    public String getEmulator() {
        return this.emulator;
    }

    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public boolean isGstreamerWorkaround() { // Add this getter
        return this.gstreamerWorkaround;
    }

    public void setGstreamerWorkaround(boolean gstreamerWorkaround) { // Add this setter
        this.gstreamerWorkaround = gstreamerWorkaround;
    }

    public void setExtraData(JSONObject extraData) {
        this.extraData = extraData;
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return extraData != null && extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, Object value) {
        if (extraData == null) extraData = new JSONObject();
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    public String getWineVersion() {
        return wineVersion;
    }

    public void setWineVersion(String wineVersion) {
        this.wineVersion = wineVersion;
    }

    public File getConfigFile() {
        return new File(rootDir, ".container");
    }

    public File getDesktopDir() {
        return new File(rootDir, ".wine/drive_c/users/"+ImageFs.USER+"/Desktop/");
    }

    public File getDesktopDirXuser() {
        return new File(rootDir, ".wine/drive_c/users/xuser/Desktop/");
    }

    public File getStartMenuDir() {
        return new File(rootDir, ".wine/drive_c/ProgramData/Microsoft/Windows/Start Menu/");
    }

    public File getIconsDir(int size) {
        return new File(rootDir, ".local/share/icons/hicolor/"+size+"x"+size+"/apps/");
    }

    public String getDesktopTheme() {
        return desktopTheme;
    }

    public void setDesktopTheme(String desktopTheme) {
        this.desktopTheme = desktopTheme;
    }

    public int getRCFileId() {
        return rcfileId;
    }

    public void setRcfileId(int id) {
        rcfileId = id;
    }

    public String getMIDISoundFont() {
        return midiSoundFont;
    }

    public void setMidiSoundFont(String fileName) {
        midiSoundFont = fileName;
    }

    public int getInputType() {
        return inputType;
    }

    public void setInputType(int inputType) {
        this.inputType = inputType;
    }

    public String getAutoDrives() {
        return autoDrives;
    }

    public void setAutoDrives(String autoDrives) {
        this.autoDrives = autoDrives != null ? autoDrives : "";
    }

    // Launch-time view: user drives first, auto-detected volumes appended.
    // The settings screen keeps using drivesIterator() so auto entries never show up
    // as editable rows and can never be saved back into `drives`.
    // 실행 시점용 목록. 사용자 드라이브가 먼저 오고 자동 감지된 볼륨이 뒤에 붙음.
    // 설정 화면은 계속 drivesIterator()를 쓰므로 자동 항목이 편집 가능한 행으로
    // 나타나지 않고, `drives`에 되저장될 일도 없음
    public Iterable<String[]> drivesIteratorWithAuto() {
        // Auto letters (fixed D:/E:, removable volumes, storage roots) win over
        // a user entry with the same letter - see StorageDrives.mergeDrives()
        // 자동 문자(고정 D:/E:, 착탈식 볼륨, 저장소 루트)가 같은 문자의 사용자
        // 항목보다 우선함 - StorageDrives.mergeDrives() 참고
        return drivesIterator(com.winlator.cmod.core.StorageDrives.mergeDrives(drives, autoDrives));
    }

    public Iterable<String[]> drivesIterator() {
        return drivesIterator(drives);
    }

    public static Iterable<String[]> drivesIterator(final String drives) {
        final int[] index = {drives.indexOf(":")};
        final String[] item = new String[2];
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] != -1;
            }

            @Override
            public String[] next() {
                item[0] = String.valueOf(drives.charAt(index[0]-1));
                int nextIndex = drives.indexOf(":", index[0]+1);
                item[1] = drives.substring(index[0]+1, nextIndex != -1 ? nextIndex-1 : drives.length());
                index[0] = nextIndex;
                return item;
            }
        };
    }

    public void saveData() {
        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("screenSize", screenSize);
            data.put("envVars", envVars);
            data.put("cpuList", cpuList);
            data.put("cpuListWoW64", cpuListWoW64);
            data.put("graphicsDriver", graphicsDriver);
            data.put("graphicsDriverConfig", graphicsDriverConfig);
            data.put("emulator", emulator);
            data.put("dxwrapper", dxwrapper);
            data.put("ddrawrapper", ddrawrapper);
            if (!dxwrapperConfig.isEmpty()) data.put("dxwrapperConfig", dxwrapperConfig);
            data.put("audioDriver", audioDriver);
            data.put("wincomponents", wincomponents);
            data.put("drives", drives);
            data.put("showFPS", showFPS);
            data.put("relativeMouseMovement", isRelativeMouseMovement);
            data.put("fullscreenStretched", fullscreenStretched);
            data.put("inputType", inputType);
            data.put("wow64Mode", wow64Mode);
            data.put("startupSelection", startupSelection);
            data.put("box64Version", box64Version);
            data.put("box64Preset", box64Preset);
            data.put("fexcoreVersion", fexcoreVersion);
            data.put("desktopTheme", desktopTheme);
            data.put("extraData", extraData);
            data.put("rcfileId", rcfileId);
            data.put("midiSoundFont", midiSoundFont);
            data.put("lc_all", lc_all);
            data.put("gstreamerWorkaround", gstreamerWorkaround);
            if (!WineInfo.isMainWineVersion(wineVersion)) data.put("wineVersion", wineVersion);
            FileUtils.writeString(getConfigFile(), data.toString());
        }
        catch (JSONException e) {}
    }


    public void loadData(JSONObject data) throws JSONException {
        wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
        dxwrapperConfig = DEFAULT_DXWRAPPERCONFIG;
        checkObsoleteOrMissingProperties(data);

        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            switch (key) {
                case "name" :
                    setName(data.getString(key));
                    break;
                case "screenSize" :
                    setScreenSize(data.getString(key));
                    break;
                case "envVars" :
                    setEnvVars(data.getString(key));
                    break;
                case "cpuList" :
                    setCPUList(data.getString(key));
                    break;
                case "cpuListWoW64" :
                    setCPUListWoW64(data.getString(key));
                    break;
                case "graphicsDriver" :
                    setGraphicsDriver(data.getString(key));
                    break;
                case "graphicsDriverConfig" :
                    setGraphicsDriverConfig(data.getString(key));
                    break;
                case "emulator":
                    setEmulator(data.getString(key));
                    break;
                case "wincomponents" :
                    setWinComponents(data.getString(key));
                    break;
                case "dxwrapper" :
                    setDXWrapper(data.getString(key));
                    break;
                case "ddrawrapper":
                    setDDrawWrapper(data.getString(key));
                    // Missing break: execution fell through into "dxwrapperConfig",
                    // overwriting the DX wrapper config with the ddrawrapper value.
                    // break 누락으로 아래 "dxwrapperConfig" 케이스까지 실행되어,
                    // DX 래퍼 설정값이 ddrawrapper 값으로 덮어써지고 있었음
                    break;
                case "dxwrapperConfig" :
                    setDXWrapperConfig(data.getString(key));
                    break;
                case "drives" :
                    setDrives(data.getString(key));
                    break;
                case "showFPS" :
                    setShowFPS(data.getBoolean(key));
                    break;
                case "relativeMouseMovement":
                    setRelativeMouseMovement(data.getBoolean(key));
                    break;
                case "fullscreenStretched" :
                    setFullscreenStretched(data.getBoolean(key));
                    break;
                case "inputType" :
                    setInputType(data.getInt(key));
                    break;
                case "wow64Mode" :
                    setWoW64Mode(data.getBoolean(key));
                    break;
                case "startupSelection" :
                    setStartupSelection((byte)data.getInt(key));
                    break;
                case "extraData" : {
                    JSONObject extraData = data.getJSONObject(key);
                    checkObsoleteOrMissingProperties(extraData);
                    setExtraData(extraData);
                    break;
                }
                case "wineVersion" :
                    setWineVersion(data.getString(key));
                    break;
                case "box64Version":
                    setBox64Version(data.getString(key));
                    break;
                case "box64Preset" :
                    setBox64Preset(data.getString(key));
                    break;
                case "fexcoreVersion":
                    setFEXCoreVersion(data.getString(key));
                    break;
                case "audioDriver" :
                    setAudioDriver(data.getString(key));
                    break;
                case "desktopTheme" :
                    setDesktopTheme(data.getString(key));
                    break;
                case "rcfileId" :
                    setRcfileId(data.getInt(key));
                    break;
                case "midiSoundFont" :
                    setMidiSoundFont(data.getString(key));
                    break;
                case "lc_all" :
                    setLC_ALL(data.getString(key));
                    break;
                case "gstreamerWorkaround" : // Add this case
                    setGstreamerWorkaround(data.getBoolean(key));
                    break;
            }
        }
    }

    public static void checkObsoleteOrMissingProperties(JSONObject data) {
        try {
            if (data.has("dxcomponents")) {
                data.put("wincomponents", data.getString("dxcomponents"));
                data.remove("dxcomponents");
            }

            if (data.has("dxwrapper")) {
                String dxwrapper = data.getString("dxwrapper");
                if (dxwrapper.equals("original-wined3d")) {
                    data.put("dxwrapper", DEFAULT_DXWRAPPER);
                }
                // Containers created before the DXVK/VKD3D merge stored "dxvk" or
                // "vkd3d" on their own. Those identifiers no longer match any branch
                // in extractDXWrapperFiles(), so upgrade them to the merged option.
                // Checked with equals(), not startsWith(), so that a real combined
                // value like "dxvk-<ver>+vkd3d-<ver>" is left untouched.
                // DXVK/VKD3D 통합 이전에 만들어진 컨테이너는 "dxvk" 또는 "vkd3d"
                // 단독으로 저장돼 있음. 이 값들은 extractDXWrapperFiles()의 어느
                // 분기와도 안 맞으므로 통합 옵션으로 승격시킴.
                // startsWith가 아닌 equals로 비교해서 "dxvk-<버전>+vkd3d-<버전>"
                // 같은 실제 조합 값은 건드리지 않음
                else if (dxwrapper.equals("dxvk") || dxwrapper.equals("vkd3d")) {
                    data.put("dxwrapper", "dxvk-vkd3d");
                }
                else if (dxwrapper.startsWith("d8vk-") || dxwrapper.startsWith("dxvk-")) {
                    data.put("dxwrapper", dxwrapper);
                }
            }

            if (data.has("graphicsDriver")) {
                String graphicsDriver = data.getString("graphicsDriver");
                if (graphicsDriver.equals("turnip-zink") || graphicsDriver.equals("turnip")) {
                    data.put("graphicsDriver", "wrapper");
                }
                else if (graphicsDriver.equals("llvmpipe")) {
                    data.put("graphicsDriver", "wrapper");
                }
            }

            if (data.has("envVars") && data.has("extraData")) {
                JSONObject extraData = data.getJSONObject("extraData");
                int appVersion = Integer.parseInt(extraData.optString("appVersion", "0"));
                if (appVersion < 16) {
                    EnvVars defaultEnvVars = new EnvVars(DEFAULT_ENV_VARS);
                    EnvVars envVars = new EnvVars(data.getString("envVars"));
                    for (String name : defaultEnvVars) if (!envVars.has(name)) envVars.put(name, defaultEnvVars.get(name));
                    data.put("envVars", envVars.toString());
                }
            }

            KeyValueSet wincomponents1 = new KeyValueSet(DEFAULT_WINCOMPONENTS);
            KeyValueSet wincomponents2 = new KeyValueSet(data.getString("wincomponents"));
            String result = "";

            for (String[] wincomponent1 : wincomponents1) {
                String value = wincomponent1[1];

                for (String[] wincomponent2 : wincomponents2) {
                    if (wincomponent1[0].equals(wincomponent2[0])) {
                        value = wincomponent2[1];
                        break;
                    }
                }

                result += (!result.isEmpty() ? "," : "")+wincomponent1[0]+"="+value;
            }

            data.put("wincomponents", result);
        }
        catch (JSONException e) {}
    }

    // Keeps only the components this version offers (the keys of DEFAULT_WINCOMPONENTS),
    // in the given order. Used on per-shortcut settings, which are not merged with the
    // default list on load and can still carry a component that was taken out
    // 이 버전이 제공하는 항목(DEFAULT_WINCOMPONENTS의 키)만 주어진 순서대로 남김. 바로가기별
    // 설정에 씀. 바로가기 설정은 불러올 때 기본 목록과 합쳐지지 않아서, 빠진 항목이 남아
    // 있을 수 있음
    public static String offeredWinComponents(String wincomponents) {
        KeyValueSet offered = new KeyValueSet(DEFAULT_WINCOMPONENTS);
        StringBuilder result = new StringBuilder();
        for (String[] wincomponent : new KeyValueSet(wincomponents)) {
            if (offered.get(wincomponent[0], null) == null) continue;
            if (result.length() > 0) result.append(',');
            result.append(wincomponent[0]).append('=').append(wincomponent[1]);
        }
        return result.toString();
    }

    public static String getFallbackCPUList() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    public static String getFallbackCPUListWoW64() {
        return getFallbackCPUList();
    }

    // Check if a specific environment variable exists
    public boolean hasEnvVar(String keyValue) {
        if (envVars == null || envVars.isEmpty()) return false;
        String[] vars = envVars.split(",");
        for (String var : vars) {
            if (var.trim().equalsIgnoreCase(keyValue.trim())) {
                return true; // Found the variable
            }
        }
        return false;
    }

}
