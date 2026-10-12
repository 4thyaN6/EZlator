package com.winlator.cmod.container;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.FilenameFilter;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Executors;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;
    private final File homeDir;
    private final Context context;

    private boolean isInitialized = false; // New flag to track initialization

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = ImageFs.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
        isInitialized = true;
    }

    // Check if the ContainerManager is fully initialized
    public boolean isInitialized() {
        return isInitialized;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    // Load containers from the home directory
    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        try {
            File[] files = homeDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        if (file.getName().startsWith(ImageFs.USER + "-")) {
                            Container container = new Container(
                                    Integer.parseInt(file.getName().replace(ImageFs.USER + "-", "")), this
                            );

                            container.setRootDir(new File(homeDir, ImageFs.USER + "-" + container.id));
                            JSONObject data = new JSONObject(FileUtils.readString(container.getConfigFile()));
                            container.loadData(data);
                            containers.add(container);
                            maxContainerId = Math.max(maxContainerId, container.id);
                        }
                    }
                }
            }
        } catch (JSONException | NullPointerException e) {
            Log.e("ContainerManager", "Error loading containers", e);
        }
    }


    public Context getContext() {
        return context;
    }


    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
        File file = new File(homeDir, ImageFs.USER);
        file.delete();
        FileUtils.symlink("./"+ImageFs.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, ContentsManager contentsManager, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container container = createContainer(data, contentsManager);
            handler.post(() -> callback.call(container));
        });
    }
    // Create a container with sensible defaults, no UI form needed.
    // Used when the user picks an .exe directly and doesn't have (or care about)
    // an existing container.
    // UI 폼 없이 기본값으로 컨테이너 생성. 사용자가 .exe를 직접 선택했는데
    // 컨테이너가 없거나 신경 안 쓸 때 사용
    // Every default here is resolved from what the recommended-contents download
    // actually installed, not from a build-time constant. A first-run user should be
    // able to hit "create" and launch a game without opening the settings screen once.
    // 여기의 모든 기본값은 빌드 상수가 아니라 추천 구성요소 다운로드로 실제 설치된
    // 것에서 결정됨. 처음 쓰는 사람이 설정 화면을 한 번도 열지 않고 생성 버튼만
    // 눌러도 게임이 뜨는 게 목표임
    public void createDefaultContainerAsync(ContentsManager contentsManager, Callback<Container> callback) {
        try {
            contentsManager.syncContents();

            // Proton is the only hard requirement - without it the container is useless,
            // so fail visibly instead of creating a broken one.
            // Proton만 필수임. 없으면 컨테이너가 쓸모없으므로 깨진 걸 만드는 대신
            // 눈에 보이게 실패시킴
            JSONObject data = buildDefaultContainerData(contentsManager, false);
            if (data == null) {
                Log.e("ContainerManager", "설치된 Proton 프로필이 없어 기본 컨테이너를 만들 수 없음");
                callback.call(null);
                return;
            }
            createContainerAsync(data, contentsManager, callback);
        } catch (JSONException e) {
            callback.call(null);
        }
    }

    // Synchronous ARM64EC-only variant for background threads (Steam install).
    // createDefaultContainerAsync() cannot be used there: its Handler needs a
    // Looper, which a plain worker thread does not have. Returns null when no
    // ARM64EC Wine/Proton is installed - it never falls back to x86_64.
    // 백그라운드 스레드(Steam 설치)용 동기식 ARM64EC 전용 버전임.
    // createDefaultContainerAsync()는 거기서 못 씀: 안의 Handler가 Looper를
    // 필요로 하는데 일반 작업 스레드에는 없음. ARM64EC Wine/Proton이 설치되어
    // 있지 않으면 null을 돌려줌 - x86_64로 대체하지 않음
    public Container createArm64ECContainer(ContentsManager contentsManager) {
        try {
            contentsManager.syncContents();
            JSONObject data = buildDefaultContainerData(contentsManager, true);
            return data != null ? createContainer(data, contentsManager) : null;
        } catch (JSONException e) {
            Log.e("ContainerManager", "ARM64EC 컨테이너 생성 실패", e);
            return null;
        }
    }

    // Default container settings, split out of createDefaultContainerAsync() so the
    // async and the ARM64EC-only paths build exactly the same config.
    // requireArm64EC: true = only an ARM64EC Wine/Proton is accepted.
    // Returns null when no usable Wine/Proton is installed.
    // 기본 컨테이너 설정임. createDefaultContainerAsync()에서 떼어내서 비동기 경로와
    // ARM64EC 전용 경로가 완전히 같은 설정을 만들게 함.
    // requireArm64EC: true = ARM64EC Wine/Proton만 받음.
    // 쓸 수 있는 Wine/Proton이 없으면 null을 돌려줌
    private JSONObject buildDefaultContainerData(ContentsManager contentsManager, boolean requireArm64EC) throws JSONException {
        String wineVersion = pickWineVersion(contentsManager, requireArm64EC);
        if (wineVersion == null) return null;
        return buildContainerDataForWine(contentsManager, wineVersion);
    }

    // Component defaults for the container creation screen: the same values an
    // automatic container gets for this Wine/Proton (Latest tier builds when
    // installed, otherwise an installed build of the right architecture). null on error
    // 컨테이너 생성 화면용 구성요소 기본값: 이 Wine/Proton으로 자동 컨테이너를 만들 때와
    // 같은 값임(Latest(최신) 단계 빌드가 설치되어 있으면 그것, 없으면 아키텍처가 맞는 설치된
    // 빌드). 오류면 null
    public JSONObject newContainerDefaults(ContentsManager contentsManager, String wineVersion) {
        try {
            return buildContainerDataForWine(contentsManager, wineVersion);
        } catch (Exception e) {
            Log.w("ContainerManager", "no defaults for " + wineVersion, e);
            return null;
        }
    }

    // Same defaults, for an already chosen Wine/Proton (used by the x86_64 path)
    // 같은 기본값을 이미 고른 Wine/Proton으로 만듦(x86_64 경로에서 씀)
    private JSONObject buildContainerDataForWine(ContentsManager contentsManager, String wineVersion) throws JSONException {
        boolean arm64ec = wineVersion.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec");

        JSONObject data = new JSONObject();
        // Named after the Wine/Proton layer, e.g. "Proton 10.0-4-arm64ec",
        // with -1, -2... added only when that name is already taken
        // Wine/Proton 레이어 이름으로 지음. 예: "Proton 10.0-4-arm64ec".
        // 같은 이름이 이미 있을 때만 -1, -2...를 붙임
        data.put("name", makeUniqueName(buildLayerName(wineVersion, contentsManager)));
        // Newest downloaded graphics driver, if any (see DriverAutoSelect)
        // 받아둔 최신 그래픽 드라이버가 있으면 그걸 씀(DriverAutoSelect 참고)
        data.put("graphicsDriverConfig", com.winlator.cmod.core.DriverAutoSelect.defaultGraphicsDriverConfig(context));
        data.put("wineVersion", wineVersion);
        // Compact HUD (engine + FPS) on by default for auto-made containers
        // 자동으로 만든 컨테이너는 간단 HUD(엔진 + FPS)를 기본으로 켬
        data.put("showFPS", true);
        // Box64 Performance preset by default
        // Box64 기본 프리셋은 성능
        data.put("box64Preset", com.winlator.cmod.box86_64.Box86_64Preset.PERFORMANCE);

        // Containers made here skipped lc_all entirely, so an empty LC_ALL reached
        // the guest. Match what the manual creation screen does.
        // 여기서 만든 컨테이너는 lc_all을 아예 넣지 않아 게스트에 빈 LC_ALL이
        // 전달됐음. 설정 화면의 수동 생성과 동일하게 맞춤
        java.util.Locale sysLocale = java.util.Locale.getDefault();
        data.put("lc_all", sysLocale.getLanguage() + "_" + sysLocale.getCountry() + ".UTF-8");

        // Graphics: DXVK and VKD3D are installed as one combined wrapper. A missing
        // one is a warning, not a stop - wined3d still renders, just slower.
        // 그래픽: DXVK와 VKD3D는 통합 래퍼 하나로 설치됨. 없으면 경고만 하고
        // 진행함. wined3d로도 렌더링은 되며 느릴 뿐임
        // The Latest tier's builds come first when they are installed (first run
        // installs them); otherwise any installed build of the right architecture
        // Latest(최신) 단계 빌드가 설치되어 있으면 그것을 먼저 씀(첫 실행에서 설치됨).
        // 없으면 아키텍처가 맞는 설치된 빌드 아무거나 씀
        LayerTiers.ArchSet latest = LayerTiers.TIER_LATEST.arch(arm64ec);
        String dxvk = firstNonNull(LayerTiers.versionKey(LayerTiers.installedProfile(context, contentsManager, latest.dxvk)),
                pickVersionKey(contentsManager, ContentProfile.ContentType.CONTENT_TYPE_DXVK, arm64ec, DefaultVersion.DXVK));
        String vkd3d = firstNonNull(LayerTiers.versionKey(LayerTiers.installedProfile(context, contentsManager, latest.vkd3d)),
                pickVersionKey(contentsManager, ContentProfile.ContentType.CONTENT_TYPE_VKD3D, arm64ec, DefaultVersion.VKD3D));

        data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
        data.put("dxwrapperConfig", dxwrapperConfig(dxvk, vkd3d));

        // Emulator: FEXCore only matters on arm64ec. Setting it unconditionally is
        // still correct because BionicProgramLauncher reads it before checking arch.
        // 에뮬레이터: FEXCore는 arm64ec에서만 의미가 있음. 그래도 무조건 넣는 게
        // 맞음. BionicProgramLauncher가 아키텍처 확인보다 먼저 이 값을 읽기 때문임
        data.put("emulator", Container.DEFAULT_EMULATOR);
        data.put("fexcoreVersion", firstNonNull(
                arm64ec ? LayerTiers.versionKey(LayerTiers.installedProfile(context, contentsManager, latest.emulator)) : null,
                pickVersionKey(contentsManager, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE, arm64ec, DefaultVersion.FEXCORE)));
        // The field is named box64Version but on arm64ec it holds a WOWBox64 version -
        // see ContainerDetailFragment.loadBox64VersionSpinner(), which switches the
        // profile type by architecture. Querying the wrong type writes a value the
        // spinner will never match and the launcher cannot resolve.
        // 필드 이름은 box64Version이지만 arm64ec에서는 WOWBox64 버전이 들어감.
        // ContainerDetailFragment.loadBox64VersionSpinner()가 아키텍처로 프로필 타입을
        // 갈아끼우기 때문임. 타입을 잘못 조회하면 스피너가 매칭하지 못하고 런처도
        // 해석하지 못하는 값이 저장됨
        data.put("box64Version", firstNonNull(
                LayerTiers.versionKey(LayerTiers.installedProfile(context, contentsManager,
                        arm64ec ? latest.wowbox64 : latest.emulator)),
                pickVersionKey(contentsManager,
                        arm64ec ? ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
                                : ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                        arm64ec, DefaultVersion.BOX64)));

        // Removable volumes are appended at launch, not stored, so only the user's
        // own defaults go into the config here.
        // 착탈식 볼륨은 실행 시점에 붙고 저장되지 않으므로, 여기에는 사용자
        // 기본값만 들어감
        data.put("drives", com.winlator.cmod.core.StorageDrives.defaultDrives(context));

        Log.d("ContainerManager", "기본 컨테이너 구성: wine=" + wineVersion
                + " dxvk=" + dxvk + " vkd3d=" + vkd3d);

        return data;
    }

    // The container stores what the spinner stores: ContentsManager.getEntryName(),
    // i.e. "Proton-<verName>-<verCode>" (confirmed in logcat as "Proton-10.0-4-arm64ec-0").
    // 컨테이너에는 스피너와 같은 형식, 즉 ContentsManager.getEntryName()의
    // "Proton-<verName>-<verCode>"가 저장됨(로그캣에서 "Proton-10.0-4-arm64ec-0" 확인)
    // Preferred default layer, matched against verName (proton-wine 11.0-2)
    // 우선 기본 레이어. verName과 대조함(proton-wine 11.0-2)
    private static final String PREFERRED_WINE_HINT = "11.0-2";

    // requireArm64EC: true = return null instead of falling back to x86_64
    // requireArm64EC: true = x86_64로 대체하지 않고 null을 돌려줌
    private String pickWineVersion(ContentsManager contentsManager, boolean requireArm64EC) {
        // Proton AND Wine types: the proton-wine 11.0-2 package for Winlator CMOD
        // is published as a "Wine type" wcp, so Proton-only missed it
        // Proton과 Wine 타입 모두 봄: Winlator CMOD용 proton-wine 11.0-2 패키지가
        // "Wine 타입" wcp로 배포돼서 Proton만 보면 놓침
        java.util.ArrayList<ContentProfile> all = new java.util.ArrayList<>();
        for (ContentProfile.ContentType t : new ContentProfile.ContentType[]{
                ContentProfile.ContentType.CONTENT_TYPE_PROTON, ContentProfile.ContentType.CONTENT_TYPE_WINE}) {
            java.util.List<ContentProfile> l = contentsManager.getProfiles(t);
            if (l != null) for (ContentProfile p : l) if (p.remoteUrl == null && p.verName != null) all.add(p);
        }
        if (all.isEmpty()) return null;

        // 1) preferred version on arm64ec, 2) any arm64ec, 3) anything
        // 1) arm64ec의 우선 버전, 2) 아무 arm64ec, 3) 아무거나
        ContentProfile anyArm = null;
        for (ContentProfile p : all) {
            String v = p.verName.toLowerCase(java.util.Locale.ENGLISH);
            if (!v.contains("arm64ec")) continue;
            if (v.contains(PREFERRED_WINE_HINT)) return ContentsManager.getEntryName(p);
            if (anyArm == null) anyArm = p;
        }
        if (anyArm != null) return ContentsManager.getEntryName(anyArm);
        return requireArm64EC ? null : ContentsManager.getEntryName(all.get(0));
    }

    // ==================== Layer (architecture) helpers ====================
    // ==================== 레이어(아키텍처) 도우미 ====================

    // A container's layer is decided by its Wine/Proton build name
    // 컨테이너의 레이어는 Wine/Proton 빌드 이름으로 정해짐
    public static boolean isArm64EC(Container container) {
        String wine = container.getWineVersion();
        return wine != null && wine.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec");
    }

    // Lowest-id container of the wanted layer, or null. Lowest id because
    // listFiles() order is not defined, and the oldest container is the one
    // the user knows as "the first one"
    // 원하는 레이어 중 id가 가장 작은 컨테이너, 없으면 null. listFiles() 순서는
    // 정해져 있지 않고, 가장 오래된 컨테이너가 사용자가 아는 "1번"이기 때문임
    public Container findFirstContainer(boolean arm64ec) {
        Container best = null;
        for (Container c : containers) {
            if (isArm64EC(c) != arm64ec) continue;
            if (best == null || c.id < best.id) best = c;
        }
        return best;
    }

    // Existing container of the wanted layer, or a new default one. Blocking:
    // call from a background thread. Returns null when no Wine/Proton of that
    // layer is installed
    // 원하는 레이어의 기존 컨테이너, 없으면 새 기본 컨테이너를 돌려줌. 동기식이라
    // 백그라운드 스레드에서 불러야 함. 그 레이어의 Wine/Proton이 설치되어 있지
    // 않으면 null임
    public Container getOrCreateContainerForArch(ContentsManager contentsManager, boolean arm64ec) {
        Container found = findFirstContainer(arm64ec);
        if (found != null) return found;
        if (arm64ec) return createArm64ECContainer(contentsManager);
        try {
            contentsManager.syncContents();
            String wineVersion = pickX86_64WineVersion(contentsManager);
            if (wineVersion == null) return null;
            JSONObject data = buildContainerDataForWine(contentsManager, wineVersion);
            return createContainer(data, contentsManager);
        } catch (JSONException e) {
            Log.e("ContainerManager", "X86_64 컨테이너 생성 실패", e);
            return null;
        }
    }

    // Makes sure one container of each layer exists - ARM64EC first so it
    // gets the lower id ("container 1"), then X86_64. A layer whose
    // Wine/Proton is not installed is simply skipped. Blocking: background
    // thread only. Returns the ARM64EC container (or null)
    // 레이어마다 컨테이너가 하나씩 있게 함 - ARM64EC를 먼저 만들어 더 작은 id
    // ("1번 컨테이너")를 받게 하고, 그다음 X86_64를 만듦. Wine/Proton이 설치되지
    // 않은 레이어는 그냥 건너뜀. 동기식이라 백그라운드 스레드에서만 부름.
    // ARM64EC 컨테이너(또는 null)를 돌려줌
    public Container ensureDefaultContainers(ContentsManager contentsManager) {
        Container arm = getOrCreateContainerForArch(contentsManager, true);
        getOrCreateContainerForArch(contentsManager, false);
        return arm;
    }

    // 1) preferred version on x86_64, 2) any non-arm64ec build, else null
    // 1) x86_64의 우선 버전, 2) arm64ec가 아닌 아무 빌드, 없으면 null
    private String pickX86_64WineVersion(ContentsManager contentsManager) {
        ContentProfile anyX86 = null;
        for (ContentProfile.ContentType t : new ContentProfile.ContentType[]{
                ContentProfile.ContentType.CONTENT_TYPE_PROTON, ContentProfile.ContentType.CONTENT_TYPE_WINE}) {
            java.util.List<ContentProfile> l = contentsManager.getProfiles(t);
            if (l == null) continue;
            for (ContentProfile p : l) {
                if (p.remoteUrl != null || p.verName == null) continue;
                String v = p.verName.toLowerCase(java.util.Locale.ENGLISH);
                if (v.contains("arm64ec")) continue;
                if (v.contains(PREFERRED_WINE_HINT)) return ContentsManager.getEntryName(p);
                if (anyX86 == null) anyX86 = p;
            }
        }
        return anyX86 != null ? ContentsManager.getEntryName(anyX86) : null;
    }

    // DXVK/VKD3D/FEXCore/Box64 are referenced by "<verName>-<verCode>" without the type
    // prefix, unlike wineVersion. Mixing the two formats is what made every lookup fail.
    // DXVK/VKD3D/FEXCore/Box64는 wineVersion과 달리 타입 접두사 없이
    // "<verName>-<verCode>" 형식으로 참조됨. 두 형식을 섞은 게 조회 실패의 원인이었음
    private String pickVersionKey(ContentsManager contentsManager,
                                  ContentProfile.ContentType type,
                                  boolean preferArm64EC,
                                  String fallback) {
        java.util.List<ContentProfile> list = contentsManager.getProfiles(type);
        if (list == null || list.isEmpty()) {
            Log.w("ContainerManager", type + " 프로필이 설치되어 있지 않음, 기본값 사용: " + fallback);
            return fallback;
        }

        ContentProfile chosen = null;
        for (ContentProfile p : list) {
            if (p.verName == null) continue;
            boolean isArm64EC = p.verName.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec");
            if (isArm64EC == preferArm64EC) { chosen = p; break; }
        }
        if (chosen == null) chosen = list.get(0);

        return chosen.verName + "-" + chosen.verCode;
    }

    public void duplicateContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            duplicateContainer(container);
            handler.post(callback);
        });
    }

    public void removeContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            removeContainer(container);
            handler.post(callback);
        });
    }

    // DXVK + VKD3D wrapper settings string, shared by every automatic container
    // 자동으로 만드는 모든 컨테이너가 같이 쓰는 DXVK + VKD3D 래퍼 설정 문자열
    private static String dxwrapperConfig(String dxvk, String vkd3d) {
        return "version=" + dxvk
                + ",framerate=0,maxDeviceMemory=0,async=0,asyncCache=0"
                + ",vkd3dVersion=" + vkd3d
                + ",vkd3dLevel=12_1";
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    // Container of one layer tier (LayerTiers.createTier()). Same defaults as an
    // automatic container, with the tier's exact component builds and the tier id
    // stored as an extra. Synchronous: call it on a worker thread.
    // 레이어 단계 하나의 컨테이너(LayerTiers.createTier()). 자동 컨테이너와 같은
    // 기본값에 그 단계의 정확한 구성요소 빌드를 넣고, 단계 id를 extra로 저장함.
    // 동기식이라 작업 스레드에서 호출함
    public Container createTierContainer(ContentsManager contentsManager, String tierId, String tierLabel,
                                         ContentProfile layer, ContentProfile dxvk, ContentProfile vkd3d,
                                         ContentProfile emulator, ContentProfile wowbox64) {
        try {
            String wineVersion = ContentsManager.getEntryName(layer);
            boolean arm64ec = wineVersion.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec");
            JSONObject data = buildContainerDataForWine(contentsManager, wineVersion);
            // Plain layer name ("Proton 11.0-2-arm64ec", "-1", "-2"... when taken), the
            // same as every other container; the tier is kept as the extra below
            // 다른 컨테이너와 같은 레이어 이름("Proton 11.0-2-arm64ec", 겹치면 "-1",
            // "-2"...). 단계는 아래 extra로 저장함

            // Exact builds of the tier; a missing one keeps the default chosen above
            // 단계의 정확한 빌드. 빠진 것은 위에서 정한 기본값을 유지함
            String cfg = data.optString("dxwrapperConfig");
            String curDxvk = cfg.replaceAll(".*(?:^|,)version=([^,]*).*", "$1");
            String curVkd3d = cfg.replaceAll(".*vkd3dVersion=([^,]*).*", "$1");
            data.put("dxwrapperConfig", dxwrapperConfig(
                    firstNonNull(LayerTiers.versionKey(dxvk), curDxvk),
                    firstNonNull(LayerTiers.versionKey(vkd3d), curVkd3d)));
            if (arm64ec) {
                if (emulator != null) data.put("fexcoreVersion", LayerTiers.versionKey(emulator));
                if (wowbox64 != null) data.put("box64Version", LayerTiers.versionKey(wowbox64));
            } else if (emulator != null) {
                data.put("box64Version", LayerTiers.versionKey(emulator));
            }

            Container container = createContainer(data, contentsManager);
            if (container != null) {
                container.putExtra(LayerTiers.EXTRA_TIER, tierId);
                container.saveData();
            }
            return container;
        } catch (JSONException e) {
            Log.e("ContainerManager", "tier container failed: " + tierId, e);
            return null;
        }
    }

    private Container createContainer(JSONObject data, ContentsManager contentsManager) {
        try {
            // Lowest free number, not max + 1 (see nextFreeId())
            // 최댓값 + 1이 아니라 비어 있는 가장 작은 번호(nextFreeId() 참고)
            int id = nextFreeId();
            data.put("id", id);

            File containerDir = new File(homeDir, ImageFs.USER+"-"+id);
            if (!containerDir.mkdirs()) return null;

            Container container = new Container(id, this);
            container.setRootDir(containerDir);
            container.loadData(data);

            container.setWineVersion(data.getString("wineVersion"));

            if (!extractContainerPatternFile(container, container.getWineVersion(), contentsManager, containerDir, null)) {
                FileUtils.delete(containerDir);
                return null;
            }

            container.saveData();
            maxContainerId++;
            containers.add(container);
            // Number and name of every new container, so number reuse can be checked in logcat
            // 새 컨테이너마다 번호와 이름을 남김. logcat에서 번호 재사용을 확인할 수 있게 함
            Log.i("ContainerManager", "container created: id=" + id + " (xuser-" + id + ") name=" + container.getName());
            return container;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }


    private void duplicateContainer(Container srcContainer) {
        // Lowest free number, like a new container (see nextFreeId())
        // 새 컨테이너와 같이 비어 있는 가장 작은 번호(nextFreeId() 참고)
        int id = nextFreeId();

        File dstDir = new File(homeDir, ImageFs.USER + "-" + id);
        if (!dstDir.mkdirs()) return;

        // Use the refactored copy method that doesn't require a Context for File operations
        if (!FileUtils.copy(srcContainer.getRootDir(), dstDir, file -> FileUtils.chmod(file, 0771))) {
            FileUtils.delete(dstDir);
            return;
        }

        Container dstContainer = new Container(id, this);
        dstContainer.setRootDir(dstDir);
        // Clones get -1, -2, -3... instead of "(copy)" (see makeCloneName())
        // 클론은 "(copy)" 대신 -1, -2, -3...을 붙임(makeCloneName() 참고)
        dstContainer.setName(makeCloneName(srcContainer.getName()));
        dstContainer.setScreenSize(srcContainer.getScreenSize());
        dstContainer.setEnvVars(srcContainer.getEnvVars());
        dstContainer.setCPUList(srcContainer.getCPUList());
        dstContainer.setCPUListWoW64(srcContainer.getCPUListWoW64());
        dstContainer.setGraphicsDriver(srcContainer.getGraphicsDriver());
        dstContainer.setDXWrapper(srcContainer.getDXWrapper());
        dstContainer.setDXWrapperConfig(srcContainer.getDXWrapperConfig());
        dstContainer.setAudioDriver(srcContainer.getAudioDriver());
        dstContainer.setWinComponents(srcContainer.getWinComponents());
        dstContainer.setDrives(srcContainer.getDrives());
        dstContainer.setShowFPS(srcContainer.isShowFPS());
        dstContainer.setWoW64Mode(srcContainer.isWoW64Mode());
        dstContainer.setStartupSelection(srcContainer.getStartupSelection());
        dstContainer.setBox64Preset(srcContainer.getBox64Preset());
        dstContainer.setDesktopTheme(srcContainer.getDesktopTheme());
        dstContainer.setRcfileId(srcContainer.getRCFileId());
        dstContainer.setWineVersion(srcContainer.getWineVersion());
        dstContainer.saveData();

        maxContainerId++;
        containers.add(dstContainer);
    }


    private void removeContainer(Container container) {
        // A newer Visual C++ runtime the user installed in this container and not
        // kept yet (the app closed before the game's end) is kept for that game first
        // 이 컨테이너에 사용자가 설치했는데 아직 보관하지 못한(게임이 끝나기 전에 앱이 닫힘)
        // 더 새 Visual C++ 런타임을 먼저 그 게임용으로 보관함
        String lastGame = container.getExtra(com.winlator.cmod.core.VCRedistInstaller.LAST_GAME_EXTRA);
        if (!lastGame.isEmpty()) {
            com.winlator.cmod.core.VCRedistInstaller.collectNewer(context, ImageFs.find(context),
                    new File(container.getRootDir(), ".wine/drive_c/windows"), lastGame,
                    container.getExtra(com.winlator.cmod.core.VCRedistInstaller.LAST_GAME_NAME_EXTRA));
        }
        if (FileUtils.delete(container.getRootDir())) {
            containers.remove(container);
            // Removal next to the creation log, so number reuse can be followed in logcat
            // 생성 로그와 짝이 되는 삭제 로그. logcat에서 번호 재사용을 따라갈 수 있게 함
            Log.i("ContainerManager", "container removed: id=" + container.id + " name=" + container.getName());
            // The number is free again and the next container may take it, so
            // nothing may keep pointing at it (see unbindLegacySaves())
            // 이 번호가 다시 비어 다음 컨테이너가 쓸 수 있으므로, 이 번호를 가리키는
            // 것이 남지 않게 함(unbindLegacySaves() 참고)
            unbindLegacySaves(container.id);
        }
    }

    // Entries of the Saves screen (files/saves/*.json) store "ContainerID". With
    // numbers reused, an entry of a removed container would silently attach to
    // the next container that gets the same number, so the key is dropped and the
    // entry stays without a container (Save handles a missing ContainerID)
    // 세이브 화면 항목(files/saves/*.json)은 "ContainerID"를 저장함. 번호를 다시
    // 쓰게 되면 지운 컨테이너의 항목이 같은 번호를 받은 다음 컨테이너에 조용히
    // 붙으므로, 그 키를 지워 컨테이너 없는 항목으로 남김(Save는 ContainerID가
    // 없는 경우를 처리함)
    private void unbindLegacySaves(int id) {
        File[] files = new File(context.getFilesDir(), "saves").listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try {
                JSONObject data = new JSONObject(FileUtils.readString(f));
                if (data.optInt("ContainerID", -1) != id) continue;
                data.remove("ContainerID");
                FileUtils.writeString(f, data.toString());
                Log.i("ContainerManager", "save entry unbound from removed container " + id + ": " + f.getName());
            } catch (Exception e) {
                Log.w("ContainerManager", "could not check save entry " + f.getName(), e);
            }
        }
    }

    public ArrayList<Shortcut> loadShortcuts() {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();

        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            File[] list = (desktopDir.exists() ? desktopDir.listFiles() : null);
            if (list == null) continue;

            for (File file : list) {
                if (!file.getName().toLowerCase().endsWith(".desktop")) continue;

                try {
                    shortcuts.add(new Shortcut(container, file));
                } catch (Exception ex) {
                    Log.w("ContainerManager",
                            "Skipping malformed shortcut: " + file.getAbsolutePath(), ex);
                    // TODO: move the bad file to a “quarantine” folder or delete it
                }
            }
        }

        shortcuts.sort(Comparator.comparing(a -> a.name, String::compareToIgnoreCase));
        return shortcuts;
    }


    public int getNextContainerId() {
        return nextFreeId();
    }

    // Lowest container number that is not in use: after removing container 3 of
    // 1..7, the next container is 3 again instead of 8. A number is also skipped
    // while a xuser-<n> folder is still on disk (e.g. a removal that could not
    // delete everything, or a folder whose settings failed to load), so an old
    // folder is never reused as a new container
    // 쓰이지 않는 가장 작은 컨테이너 번호임: 1~7 중 3번을 지우면 다음 컨테이너는
    // 8번이 아니라 다시 3번이 됨. xuser-<번호> 폴더가 디스크에 남아 있는 번호도
    // 건너뜀(예: 지우다가 일부가 남은 경우, 설정을 못 읽은 폴더). 그래서 예전
    // 폴더를 새 컨테이너로 다시 쓰는 일이 없음
    private int nextFreeId() {
        for (int id = 1; ; id++) {
            if (getContainerById(id) != null) continue;
            if (new File(homeDir, ImageFs.USER + "-" + id).exists()) continue;
            return id;
        }
    }

    /**
     * Display name for a Wine/Proton identifier. The stored identifier is
     * ContentsManager.getEntryName() = "<Type>-<verName>-<verCode>", e.g.
     * "Proton-10.0-4-arm64ec-0" -> "Proton 10.0-4-arm64ec".
     * Wine/Proton 식별자를 표시용 이름으로 바꿈. 저장되는 식별자는
     * ContentsManager.getEntryName() = "<타입>-<verName>-<verCode>" 형식임.
     * 예: "Proton-10.0-4-arm64ec-0" -> "Proton 10.0-4-arm64ec"
     */
    public static String buildLayerName(String wineVersion, ContentsManager contentsManager) {
        if (wineVersion == null || wineVersion.isEmpty()) return "Container";

        // Exact profile match first: gives the real type label and verName
        // 먼저 프로필과 정확히 대조함: 실제 타입 이름과 verName을 그대로 얻음
        if (contentsManager != null) {
            ContentProfile.ContentType[] types = {
                    ContentProfile.ContentType.CONTENT_TYPE_PROTON,
                    ContentProfile.ContentType.CONTENT_TYPE_WINE};
            for (ContentProfile.ContentType type : types) {
                java.util.List<ContentProfile> list = contentsManager.getProfiles(type);
                if (list == null) continue;
                for (ContentProfile p : list) {
                    if (p.remoteUrl == null && wineVersion.equals(ContentsManager.getEntryName(p)))
                        return p.type.toString() + " " + p.verName;
                }
            }
        }

        // Fallback on the string itself: drop a trailing "-<digits>" verCode
        // only when the identifier has the Type-verName-verCode shape
        // 문자열로 폴백함: Type-verName-verCode 형태일 때만 끝의 "-숫자" verCode를 뗌
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([A-Za-z]+)-(.+)-(\\d+)$").matcher(wineVersion);
        if (m.matches()) return m.group(1) + " " + m.group(2);
        int dash = wineVersion.indexOf('-');
        return dash > 0 ? wineVersion.substring(0, dash) + " " + wineVersion.substring(dash + 1) : wineVersion;
    }

    private boolean containerNameExists(String name) {
        for (Container c : containers) if (name.equals(c.getName())) return true;
        return false;
    }

    /**
     * base if free, otherwise base-1, base-2... (lowest free number).
     * base가 비어 있으면 그대로, 아니면 base-1, base-2... 중 가장 작은 빈 번호
     */
    public String makeUniqueName(String base) {
        if (!containerNameExists(base)) return base;
        for (int n = 1; ; n++) {
            String candidate = base + "-" + n;
            if (!containerNameExists(candidate)) return candidate;
        }
    }

    /**
     * Name for a clone. Cloning "X" or "X-2" both yield the next free "X-n",
     * so clones never stack up as "X-1-1". The "-n" suffix is only stripped
     * when "X" itself still exists, which keeps names like "Proton 10.0-4"
     * (whose "-4" is part of the version) intact.
     * 클론용 이름임. "X"를 복제하든 "X-2"를 복제하든 다음 빈 "X-n"이 나와서
     * "X-1-1"처럼 쌓이지 않음. "-n"은 "X"가 실제로 존재할 때만 떼어내므로,
     * "-4"가 버전의 일부인 "Proton 10.0-4" 같은 이름은 망가지지 않음
     */
    public String makeCloneName(String srcName) {
        String base = srcName;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+)-(\\d+)$").matcher(srcName);
        if (m.matches() && containerNameExists(m.group(1))) base = m.group(1);
        return makeUniqueName(base);
    }

    public Container getContainerById(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }

    private void extractCommonDlls(WineInfo wineInfo, String srcName, String dstName, File containerDir, OnExtractFileListener onExtractFileListener) throws JSONException {
        File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);

        File[] srcfiles = srcDir.listFiles(file -> file.isFile());

        for (File file : srcfiles) {
            String dllName = file.getName();
            if (dllName.equals("iexplore.exe") && wineInfo.isArm64EC() && srcName.equals("aarch64-windows"))
                file = new File(wineInfo.path + "/lib/wine/" + "i386-windows/iexplore.exe");
            File dstFile = new File(containerDir, ".wine/drive_c/windows/" + dstName + "/" + dllName);
            if (dstFile.exists()) continue;
            if (onExtractFileListener != null ) {
                dstFile = onExtractFileListener.onExtractFile(dstFile, 0);
                if (dstFile == null) continue;
            }
            FileUtils.copy(file, dstFile);
        }
    }

    public boolean extractContainerPatternFile(Container container, String wineVersion, ContentsManager contentsManager, File containerDir, OnExtractFileListener onExtractFileListener) {
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        String containerPattern = wineVersion + "_container_pattern.tzst";
        boolean result = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, containerPattern, containerDir, onExtractFileListener);

        if (!result) {
            File containerPatternFile = new File(wineInfo.path + "/prefixPack.txz");
            result = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, containerPatternFile, containerDir);
        }

        if (result) {
            try {
                if (wineInfo.isArm64EC())
                    extractCommonDlls(wineInfo, "aarch64-windows", "system32", containerDir, onExtractFileListener); // arm64ec only
                else
                    extractCommonDlls(wineInfo, "x86_64-windows", "system32", containerDir, onExtractFileListener);

                extractCommonDlls(wineInfo, "i386-windows", "syswow64", containerDir, onExtractFileListener);
            }
            catch (JSONException e) {
                return false;
            }
        }
   
        return result;
    }

    public Container getContainerForShortcut(Shortcut shortcut) {
        // Search for the container by its ID
        for (Container container : containers) {
            if (container.id == shortcut.getContainerId()) {
                return container;
            }
        }
        return null;  // Return null if no matching container is found
    }

    public void importContainer(File importDir, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (!importDir.exists() || !importDir.isDirectory()) {
                    Log.e("ContainerManager", "Invalid container directory for import: " + importDir.getPath());
                    return;
                }

                // Get the next container ID and set the new container name
                int newContainerId = getNextContainerId();
                String newContainerName = ImageFs.USER + "-" + newContainerId;
                File newContainerDir = new File(homeDir, newContainerName);

                if (newContainerDir.exists()) {
                    Log.e("ContainerManager", "Container directory already exists: " + newContainerDir.getPath());
                    return;
                }

                if (!newContainerDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create directory: " + newContainerDir.getPath());
                    return;
                }

                // Copy the files from the import directory to the new container directory
                if (!FileUtils.copy(importDir, newContainerDir, file -> FileUtils.chmod(file, 0771))) {
                    FileUtils.delete(newContainerDir);
                    Log.e("ContainerManager", "Failed to copy container files to: " + newContainerDir.getPath());
                    return;
                }

                // Create the new container object and save its data
                Container newContainer = new Container(newContainerId, this);
                newContainer.setRootDir(newContainerDir);
                // Keep the settings of the imported container (Wine version, Win
                // Components such as Visual C++ 2015-2022, ...). Before, saveData()
                // below wrote the defaults over the copied config
                // 가져온 컨테이너의 설정(Wine 버전, Visual C++ 2015~2022 같은 Win
                // Components 등)을 유지함. 예전에는 아래 saveData()가 복사한 설정 파일을
                // 기본값으로 덮어썼음
                try {
                    String config = FileUtils.readString(newContainer.getConfigFile());
                    if (config != null && !config.trim().isEmpty()) newContainer.loadData(new JSONObject(config));
                } catch (Exception e) {
                    Log.w("ContainerManager", "Imported config not readable, defaults are used", e);
                }
                newContainer.setName(importDir.getName());
                newContainer.saveData();
                containers.add(newContainer);
                maxContainerId++;

                Log.d("ContainerManager", "Container imported successfully to: " + newContainerDir.getPath());
                // Make sure to run the callback after successful import
                if (callback != null) {
                    callback.run();
                }
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to import container from: " + importDir.getPath(), e);
            }
        });
    }



    public void exportContainer(Container container, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // Create the export directory path
                File exportDir = com.winlator.cmod.core.AppDirs.downloads("Backups/Containers");

                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create export directory: " + exportDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                File containerDir = container.getRootDir();
                File destinationDir = new File(exportDir, containerDir.getName());

                if (destinationDir.exists()) {
                    Log.e("ContainerManager", "Export directory already exists: " + destinationDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                if (!destinationDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create directory: " + destinationDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                // Visual C++ 2015-2022 DLLs that EZlator put into the prefix are left
                // out (Microsoft's files are not shared); the mark is kept, so the
                // container gets them again from the store where it is imported
                // EZlator가 프리픽스에 넣은 Visual C++ 2015~2022 DLL은 뺌(Microsoft 파일은
                // 공유하지 않음). 표시 파일은 남기므로 가져온 곳의 보관소에서 다시 받음
                java.util.Set<String> vcExcluded = com.winlator.cmod.core.VCRedistInstaller.exportExcluded(
                        ImageFs.find(context), new File(containerDir, ".wine/drive_c/windows"));
                if (!vcExcluded.isEmpty()) Log.d("ContainerManager", "Export leaves out " + vcExcluded.size() + " Visual C++ DLL(s)");

                if (!FileUtils.copy(containerDir, destinationDir, file -> FileUtils.chmod(file, 0771), vcExcluded)) {
                    Log.e("ContainerManager", "Failed to export some container files to: " + destinationDir.getPath());
                    FileUtils.delete(destinationDir); // Optional: Delete partially copied directory
                }

                Log.d("ContainerManager", "Container exported successfully to: " + destinationDir.getPath());
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to export container: " + container.getName(), e);
            } finally {
                runOnUiThread(callback); // Ensure the callback runs and preloader dialog closes
            }
        });
    }

    // Utility method to run on UI thread
    private void runOnUiThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }



}
