package com.winlator.cmod.container;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// Layer tiers: Latest / Stable / Legacy. Each tier is one Proton layer per
// architecture plus the component builds that go with it. Only the Proton layer
// needs its own container; DXVK / VKD3D / FEXCore / Box64 / WOWBox64 are stored
// in that container's settings (and can still be changed per shortcut).
// Sources: The412Banner first (Nightlies fixed tags and the winlator-contents
// catalog), GameNative's Winlator CMOD package only where The412Banner has no
// build. GameNative's "Proton type" packages are made for the GameNative app
// only and are never used.
// 레이어 단계: Latest(최신) / Stable(안정) / Legacy(레거시). 단계마다 아키텍처별 Proton 레이어 하나와
// 거기에 맞춘 구성요소 빌드 묶음임. 컨테이너가 따로 필요한 것은 Proton 레이어
// 뿐이고, DXVK / VKD3D / FEXCore / Box64 / WOWBox64는 그 컨테이너 설정에 들어감
// (숏컷별로 바꾸는 것도 그대로 가능함). 출처: The412Banner 우선(Nightlies 고정
// 태그와 winlator-contents 카탈로그), The412Banner에 빌드가 없는 것만 GameNative의
// Winlator CMOD용 패키지를 씀. GameNative의 "Proton 타입" 패키지는 GameNative 앱
// 전용이라 쓰지 않음
public final class LayerTiers {
    private static final String TAG = "LayerTiers";

    // Container extra holding the tier id, and the tier ids
    // 단계 id를 담는 컨테이너 extra 이름과 단계 id들
    public static final String EXTRA_TIER = "layerTier";
    public static final String LATEST = "latest";
    public static final String STABLE = "stable";
    public static final String LEGACY = "legacy";

    // SharedPreferences remembering which installed package a download URL produced
    // (its entry name), so a tier is not downloaded twice and the exact component
    // versions can be written into a container
    // 어떤 다운로드 주소가 어떤 설치 항목(entry 이름)이 됐는지 기억하는 설정 파일.
    // 같은 단계를 두 번 받지 않고, 컨테이너에 정확한 구성요소 버전을 넣기 위함
    private static final String PREFS = "layer_tiers";

    private static final String NIGHTLIES = "https://github.com/The412Banner/Nightlies/releases/download/";
    private static final String BANNER_LAYERS = "https://github.com/The412Banner/winlator-contents/releases/download/bionic-layers-20260908-xinput/";
    private static final String GAMENATIVE = "https://github.com/GameNative/proton-wine/releases/download/";

    // One downloadable component: display label, content type, URLs (first = primary,
    // the rest are fallbacks tried in order when a download fails)
    // 받을 구성요소 하나: 표시 이름, 종류, 주소(첫 번째가 기본, 나머지는 다운로드
    // 실패 시 차례로 시도하는 대체 주소)
    public static final class Component {
        public final String label;
        public final ContentProfile.ContentType type;
        public final String[] urls;

        Component(String label, ContentProfile.ContentType type, String... urls) {
            this.label = label;
            this.type = type;
            this.urls = urls;
        }
    }

    // Everything one container of a tier uses. emulator = FEXCore (arm64ec) or
    // Box64 (x86_64); wowbox64 is arm64ec only
    // 한 단계의 컨테이너 하나가 쓰는 전부. emulator = FEXCore(arm64ec) 또는
    // Box64(x86_64). wowbox64는 arm64ec 전용임
    public static final class ArchSet {
        public final boolean arm64ec;
        public final Component layer, dxvk, vkd3d, emulator, wowbox64;

        ArchSet(boolean arm64ec, Component layer, Component dxvk, Component vkd3d, Component emulator, Component wowbox64) {
            this.arm64ec = arm64ec;
            this.layer = layer;
            this.dxvk = dxvk;
            this.vkd3d = vkd3d;
            this.emulator = emulator;
            this.wowbox64 = wowbox64;
        }

        public Component[] components() {
            return wowbox64 != null
                    ? new Component[]{layer, dxvk, vkd3d, emulator, wowbox64}
                    : new Component[]{layer, dxvk, vkd3d, emulator};
        }
    }

    public static final class Tier {
        public final String id;
        public final int labelRes;
        public final ArchSet arm64ec, x86_64;

        Tier(String id, int labelRes, ArchSet arm64ec, ArchSet x86_64) {
            this.id = id;
            this.labelRes = labelRes;
            this.arm64ec = arm64ec;
            this.x86_64 = x86_64;
        }

        public ArchSet arch(boolean arm64ec) {
            return arm64ec ? this.arm64ec : this.x86_64;
        }
    }

    private static final ContentProfile.ContentType PROTON = ContentProfile.ContentType.CONTENT_TYPE_PROTON;
    private static final ContentProfile.ContentType DXVK = ContentProfile.ContentType.CONTENT_TYPE_DXVK;
    private static final ContentProfile.ContentType VKD3D = ContentProfile.ContentType.CONTENT_TYPE_VKD3D;
    private static final ContentProfile.ContentType FEX = ContentProfile.ContentType.CONTENT_TYPE_FEXCORE;
    private static final ContentProfile.ContentType BOX64 = ContentProfile.ContentType.CONTENT_TYPE_BOX64;
    private static final ContentProfile.ContentType WOW = ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64;

    // Latest: Proton 11.0-2 (The412Banner bionic layers v6, GameNative CMOD as fallback)
    // Latest(최신): Proton 11.0-2(The412Banner bionic 레이어 v6, 대체는 GameNative CMOD용)
    public static final Tier TIER_LATEST = new Tier(LATEST, R.string.tier_latest,
            new ArchSet(true,
                    new Component("Proton 11.0-2", PROTON,
                            BANNER_LAYERS + "proton-11.0-2-arm64ec.wcp",
                            GAMENATIVE + "proton-11.0-2-20260925/proton-wine-11.0-2-arm64ec.wcp.xz"),
                    new Component("DXVK gplasync 3.1-1", DXVK, NIGHTLIES + "Dxvk-gplasync-arm64ec/dxvk-gplasync-arm64ec-3.1-1.wcp"),
                    new Component("VKD3D 3.0.1", VKD3D, NIGHTLIES + "VKD3D-PROTON-ARM/vkd3d-proton-arm64ec-3.0.1.wcp"),
                    new Component("FEXCore 2609", FEX, NIGHTLIES + "FexCore/FEXCore-2609.wcp"),
                    new Component("WOWBox64 0.4.3", WOW, NIGHTLIES + "Wowbox64/WOWBox64-0.4.3.wcp")),
            new ArchSet(false,
                    new Component("Proton 11.0-2", PROTON,
                            BANNER_LAYERS + "proton-11.0-2-x86_64.wcp",
                            GAMENATIVE + "proton-11.0-2-20260925/proton-wine-11.0-2-x86_64.wcp.xz"),
                    new Component("DXVK gplasync 3.1-1", DXVK, NIGHTLIES + "Dxvk-gplasync/dxvk-gplasync-3.1-1.wcp"),
                    new Component("VKD3D 3.0.1", VKD3D, NIGHTLIES + "Vkd3d-proton/vkd3d-proton-3.0.1.wcp"),
                    new Component("Box64 0.4.4", BOX64, NIGHTLIES + "Box64-Bionic/Box64-0.4.4-Bionic.wcp"),
                    null));

    // Stable: Proton 10.0-4. The412Banner has no x86_64 10.0-4 layer, so that one is
    // GameNative's CMOD package (installed as "...-proton-wine", see ContentsManager)
    // Stable(안정): Proton 10.0-4. The412Banner에 x86_64 10.0-4 레이어가 없어서 그것만
    // GameNative CMOD용 패키지를 씀("...-proton-wine"으로 설치됨, ContentsManager 참고)
    public static final Tier TIER_STABLE = new Tier(STABLE, R.string.tier_stable,
            new ArchSet(true,
                    new Component("Proton 10.0-4", PROTON, BANNER_LAYERS + "proton-10.0-4-arm64ec.wcp"),
                    new Component("DXVK gplasync 2.4.1-1", DXVK, NIGHTLIES + "Dxvk-gplasync-arm64ec/dxvk-gplasync-arm64ec-2.4.1-1.wcp"),
                    new Component("VKD3D 2.14.1", VKD3D, NIGHTLIES + "VKD3D-PROTON-ARM/vkd3d-proton-arm64ec-2.14.1.wcp"),
                    new Component("FEXCore 2605", FEX, NIGHTLIES + "FexCore/FEXCore-2605.wcp"),
                    new Component("WOWBox64 0.4.3", WOW, NIGHTLIES + "Wowbox64/WOWBox64-0.4.3.wcp")),
            new ArchSet(false,
                    // The412Banner proton-wine 10.0-4 x86_64 SDK 35 build (the GameNative CMOD
                    // build froze while games ran). Marked untested by its publisher
                    // The412Banner proton-wine 10.0-4 x86_64 SDK 35 빌드(GameNative CMOD
                    // 빌드는 게임 실행 중 멈췄음). 배포처가 미검증으로 표시한 빌드임
                    new Component("Proton 10.0-4", PROTON,
                            "https://github.com/The412Banner/proton-wine/releases/download/build-arm64ec-refresh-20260722/proton-10.0-4-x86_64-sdk35.wcp"),
                    new Component("DXVK gplasync 2.4.1-1", DXVK, NIGHTLIES + "Dxvk-gplasync/dxvk-gplasync-2.4.1-1.wcp"),
                    new Component("VKD3D 2.14.1", VKD3D, NIGHTLIES + "Vkd3d-proton/vkd3d-proton-2.14.1.wcp"),
                    new Component("Box64 0.4.2", BOX64, NIGHTLIES + "Box64-Bionic/Box64-0.4.2.wcp"),
                    null));

    // Legacy: Proton 9.0 (Nightlies). x86_64 uses plain DXVK 1.10.3
    // Legacy(레거시): Proton 9.0(Nightlies). x86_64는 일반 DXVK 1.10.3을 씀
    public static final Tier TIER_LEGACY = new Tier(LEGACY, R.string.tier_legacy,
            new ArchSet(true,
                    new Component("Proton 9.0", PROTON, NIGHTLIES + "Proton/wine/proton-9.0-arm64ec.wcp"),
                    new Component("DXVK gplasync 2.4.1-1", DXVK, NIGHTLIES + "Dxvk-gplasync-arm64ec/dxvk-gplasync-arm64ec-2.4.1-1.wcp"),
                    new Component("VKD3D 2.14.1", VKD3D, NIGHTLIES + "VKD3D-PROTON-ARM/vkd3d-proton-arm64ec-2.14.1.wcp"),
                    new Component("FEXCore 2508", FEX, NIGHTLIES + "FexCore/FEX-2508.wcp"),
                    new Component("WOWBox64 0.3.8", WOW, NIGHTLIES + "Wowbox64/wowbox64-0.3.8.wcp")),
            new ArchSet(false,
                    new Component("Proton 9.0", PROTON, NIGHTLIES + "Proton/wine/proton-9.0-x86_64.wcp"),
                    new Component("DXVK 1.10.3", DXVK, NIGHTLIES + "Dxvk/dxvk-1.10.3.wcp"),
                    new Component("VKD3D 2.14.1", VKD3D, NIGHTLIES + "Vkd3d-proton/vkd3d-proton-2.14.1.wcp"),
                    new Component("Box64 0.3.8", BOX64, NIGHTLIES + "Box64-Bionic/box64-bionic-0.3.8.wcp"),
                    null));

    // Display / menu order
    // 표시·메뉴 순서
    public static final Tier[] ALL = {TIER_LATEST, TIER_STABLE, TIER_LEGACY};

    private LayerTiers() {}

    public static Tier get(String id) {
        for (Tier t : ALL) if (t.id.equals(id)) return t;
        return null;
    }

    // First-run download list (Latest tier, both architectures, no duplicates):
    // {label, primary URL, fallback URL...}
    // 첫 실행 다운로드 목록(Latest(최신) 단계, 두 아키텍처, 중복 없음):
    // {표시 이름, 기본 주소, 대체 주소...}
    public static String[][] firstRunContents() {
        Map<String, String[]> list = new LinkedHashMap<>();
        for (ArchSet set : new ArchSet[]{TIER_LATEST.arm64ec, TIER_LATEST.x86_64}) {
            for (Component c : set.components()) {
                if (list.containsKey(c.urls[0])) continue;
                String[] item = new String[c.urls.length + 1];
                item[0] = c.label + (set.arm64ec ? " arm64ec" : " x86_64");
                System.arraycopy(c.urls, 0, item, 1, c.urls.length);
                list.put(c.urls[0], item);
            }
        }
        return list.values().toArray(new String[0][]);
    }

    // Multi-line description for a tier button: one line per architecture
    // 단계 버튼용 여러 줄 설명: 아키텍처마다 한 줄
    public static String describe(ArchSet set) {
        StringBuilder sb = new StringBuilder(set.arm64ec ? "ARM64EC: " : "X86_64: ");
        Component[] cs = set.components();
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(cs[i].label);
        }
        return sb.toString();
    }

    // Tier of a container: the stored extra, or for containers made before tiers
    // existed, guessed from the Proton version in its layer name
    // 컨테이너의 단계: 저장된 extra 값. 단계 기능 이전에 만든 컨테이너는 레이어
    // 이름의 Proton 버전으로 추정함
    public static String tierOf(Container container) {
        String id = container.getExtra(EXTRA_TIER);
        if (id != null && !id.isEmpty()) return id;
        return tierForWine(container.getWineVersion());
    }

    // Tier guessed from a Wine/Proton version name (containers made before tiers,
    // or automatic ones): 11.0-2 = Latest, 10.0-4 = Stable, 9.0 = Legacy
    // Wine/Proton 버전 이름으로 추정한 단계(단계 기능 이전 컨테이너나 자동 컨테이너):
    // 11.0-2 = Latest(최신), 10.0-4 = Stable(안정), 9.0 = Legacy(레거시)
    public static String tierForWine(String wineVersion) {
        if (wineVersion == null) return null;
        String w = wineVersion.toLowerCase(Locale.ENGLISH);
        if (w.contains("11.0-2")) return LATEST;
        if (w.contains("10.0-4")) return STABLE;
        if (w.contains("9.0")) return LEGACY;
        return null;
    }

    // Containers made before tiers existed get the tier mark (names are not
    // changed: containers keep their plain layer names). A tier name that an
    // earlier build put in front of "Proton ..." is taken off again.
    // 단계 기능 이전에 만든 컨테이너에 단계 표시를 붙임(이름은 바꾸지 않음:
    // 컨테이너는 레이어 이름을 그대로 씀). 예전 빌드가 "Proton ..." 앞에 붙였던
    // 단계 이름은 다시 떼어 냄
    public static void adoptUntagged(Context context, ContainerManager manager) {
        for (Container c : manager.getContainers()) {
            boolean changed = false;
            String name = c.getName();
            for (Tier t : ALL) {
                String prefix = context.getString(t.labelRes) + " Proton ";
                if (name != null && name.startsWith(prefix)) {
                    c.setName(name.substring(prefix.length() - "Proton ".length()));
                    changed = true;
                    break;
                }
            }
            String id = c.getExtra(EXTRA_TIER);
            if (id == null || id.isEmpty()) {
                String tierId = tierForWine(c.getWineVersion());
                if (tierId != null) {
                    c.putExtra(EXTRA_TIER, tierId);
                    changed = true;
                }
            }
            if (changed) c.saveData();
        }
    }

    // CUSTOM: a tier container whose components differ from the tier's builds,
    // either in the container itself or through the shortcut's own overrides
    // (DXVK / VKD3D, FEXCore, Box64 / WOWBox64). Builds the tier did not install
    // are not compared.
    // CUSTOM: 구성요소가 단계의 빌드와 다른 단계 컨테이너. 컨테이너 자체가 바뀌었거나
    // 숏컷의 개별 설정(DXVK / VKD3D, FEXCore, Box64 / WOWBox64)으로 달라진 경우임.
    // 단계가 설치하지 않은 빌드는 비교하지 않음
    public static boolean isCustom(Context context, Container container, Shortcut shortcut) {
        String tierId = tierOf(container);
        Tier tier = tierId != null ? get(tierId) : null;
        if (tier == null) return false;
        ArchSet set = tier.arch(isArm64EC(container));
        ContentsManager cm = new ContentsManager(context);
        cm.syncContents();
        String dxvk = versionKey(installedProfile(context, cm, set.dxvk));
        String vkd3d = versionKey(installedProfile(context, cm, set.vkd3d));
        String emulator = versionKey(installedProfile(context, cm, set.emulator));
        String wow = set.wowbox64 != null ? versionKey(installedProfile(context, cm, set.wowbox64)) : null;

        String cfg = shortcut != null ? shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig())
                : container.getDXWrapperConfig();
        if (cfg == null) cfg = "";
        if (dxvk != null && !("," + cfg + ",").contains(",version=" + dxvk + ",")) return true;
        if (vkd3d != null && !("," + cfg + ",").contains(",vkd3dVersion=" + vkd3d + ",")) return true;
        String box = shortcut != null ? shortcut.getExtra("box64Version", container.getBox64Version()) : container.getBox64Version();
        if (set.arm64ec) {
            String fex = shortcut != null ? shortcut.getExtra("fexcoreVersion", container.getFEXCoreVersion()) : container.getFEXCoreVersion();
            if (emulator != null && !emulator.equals(fex)) return true;
            if (wow != null && !wow.equals(box)) return true;
        } else if (emulator != null && !emulator.equals(box)) {
            return true;
        }
        return false;
    }

    // Shortcut extras that pick component builds; cleared when a shortcut is put
    // back on a tier so it uses that tier's builds again
    // 구성요소 빌드를 고르는 숏컷 extra. 숏컷을 단계로 되돌릴 때 지워서 그 단계의
    // 빌드를 다시 쓰게 함
    public static final String[] COMPONENT_EXTRAS = {"dxwrapper", "dxwrapperConfig", "box64Version", "fexcoreVersion"};

    public static boolean isArm64EC(Container container) {
        String wine = container.getWineVersion();
        return wine != null && wine.toLowerCase(Locale.ENGLISH).contains("arm64ec");
    }

    // First container of the given tier and architecture, or null
    // 해당 단계·아키텍처의 첫 컨테이너. 없으면 null
    public static Container findContainer(ContainerManager manager, String tierId, boolean arm64ec) {
        for (Container c : manager.getContainers()) {
            if (tierId.equals(tierOf(c)) && isArm64EC(c) == arm64ec) return c;
        }
        return null;
    }

    // Remembers which installed entry a component URL produced
    // 구성요소 주소가 어떤 설치 항목이 됐는지 기록함
    public static void record(Context context, String primaryUrl, ContentProfile profile) {
        if (profile == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(primaryUrl, ContentsManager.getEntryName(profile)).apply();
    }

    // The installed profile a component URL produced earlier, if it is still installed
    // 구성요소 주소로 예전에 설치된 프로필. 아직 설치되어 있을 때만 돌려줌
    public static ContentProfile installedProfile(Context context, ContentsManager contentsManager, Component c) {
        String entry = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(c.urls[0], null);
        return entry != null ? contentsManager.getProfileByEntryName(entry) : null;
    }

    // Version key as containers store it ("<verName>-<verCode>"), or null
    // 컨테이너가 저장하는 형식의 버전 키("<verName>-<verCode>"). 없으면 null
    public static String versionKey(ContentProfile profile) {
        return profile != null ? profile.verName + "-" + profile.verCode : null;
    }

    // Progress callback for createTier(); message is shown as is
    // createTier()의 진행 알림. message는 그대로 표시됨
    public interface Progress {
        void onMessage(String message);
    }

    // Downloads (only what is missing) and installs every component of the tier's
    // missing containers, then creates those containers. Runs on a worker thread.
    // Returns the number of containers created; a container whose Proton layer could
    // not be installed is skipped.
    // 단계에서 빠진 컨테이너의 구성요소를 (없는 것만) 받아 설치하고 그 컨테이너를
    // 만듦. 작업 스레드에서 실행함. 만든 컨테이너 수를 돌려줌. Proton 레이어를
    // 설치하지 못한 컨테이너는 건너뜀
    public static int createTier(Context context, Tier tier, boolean[] archs, Progress progress) {
        ContentsManager cm = new ContentsManager(context);
        cm.syncContents();
        ContainerManager manager = new ContainerManager(context);
        String tierLabel = context.getString(tier.labelRes);
        int created = 0;

        for (boolean arm64ec : archs) {
            if (findContainer(manager, tier.id, arm64ec) != null) continue;
            ArchSet set = tier.arch(arm64ec);
            Component[] cs = set.components();
            ContentProfile[] profiles = new ContentProfile[cs.length];
            for (int i = 0; i < cs.length; i++) {
                progress.onMessage(tierLabel + " · " + (arm64ec ? "ARM64EC" : "X86_64")
                        + " (" + (i + 1) + "/" + cs.length + ")\n" + cs[i].label);
                profiles[i] = ensureInstalled(context, cm, cs[i]);
            }
            if (profiles[0] == null) {
                Log.w(TAG, "layer not installed, skipping " + tier.id + (arm64ec ? " arm64ec" : " x86_64"));
                continue;
            }
            progress.onMessage(tierLabel + " · " + (arm64ec ? "ARM64EC" : "X86_64") + "\n"
                    + context.getString(R.string.tier_creating_container));
            Container c = manager.createTierContainer(cm, tier.id, tierLabel, profiles[0],
                    profiles[1], profiles[2], profiles[3], cs.length > 4 ? profiles[4] : null);
            if (c != null) created++;
        }
        return created;
    }

    // Returns the installed profile of a component, downloading and installing it
    // first if needed (primary URL, then each fallback). null when every URL failed.
    // 구성요소의 설치된 프로필을 돌려줌. 필요하면 먼저 받아서 설치함(기본 주소,
    // 그다음 대체 주소 차례). 모든 주소가 실패하면 null
    private static ContentProfile ensureInstalled(Context context, ContentsManager cm, Component c) {
        ContentProfile existing = installedProfile(context, cm, c);
        if (existing != null) return existing;

        for (String url : c.urls) {
            File temp = new File(context.getCacheDir(), "tier_" + System.nanoTime() + ".wcp");
            try {
                if (!Downloader.downloadFile(url, temp)) {
                    Log.w(TAG, "download failed: " + url);
                    continue;
                }
                final ContentProfile[] got = {null};
                cm.extraContentFile(Uri.fromFile(temp), new ContentsManager.OnInstallFinishedCallback() {
                    private boolean first = true;

                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                        // ERROR_EXIST means the same package is already installed;
                        // the profile captured below is still the right one
                        // ERROR_EXIST는 같은 패키지가 이미 설치됐다는 뜻임.
                        // 아래에서 잡아 둔 프로필이 그대로 맞는 값임
                        Log.w(TAG, "install " + c.label + ": " + reason);
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        if (first) {
                            first = false;
                            got[0] = profile;
                            cm.finishInstallContent(profile, this);
                        }
                    }
                });
                if (got[0] == null) continue;
                cm.syncContents();
                ContentProfile installed = cm.getProfileByEntryName(ContentsManager.getEntryName(got[0]));
                if (installed != null) {
                    record(context, c.urls[0], installed);
                    return installed;
                }
            } catch (Exception e) {
                Log.e(TAG, "component failed: " + c.label, e);
            } finally {
                temp.delete();
            }
        }
        return null;
    }

    // Whether the tier already has its ARM64EC / X86_64 container, for the
    // Containers screen buttons: {arm64ec, x86_64}
    // 단계의 ARM64EC / X86_64 컨테이너가 이미 있는지. 컨테이너 화면 버튼용:
    // {arm64ec, x86_64}
    public static boolean[] existing(ContainerManager manager, Tier tier) {
        return new boolean[]{findContainer(manager, tier.id, true) != null,
                findContainer(manager, tier.id, false) != null};
    }

    // Architectures of a tier that have no container yet
    // 단계에서 아직 컨테이너가 없는 아키텍처
    public static boolean[] missingArchs(ContainerManager manager, Tier tier) {
        ArrayList<Boolean> list = new ArrayList<>();
        if (findContainer(manager, tier.id, true) == null) list.add(true);
        if (findContainer(manager, tier.id, false) == null) list.add(false);
        boolean[] out = new boolean[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
