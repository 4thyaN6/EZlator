package com.winlator.cmod.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.core.cab.CabReader;
import com.winlator.cmod.steam.SteamGameInstaller;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Visual C++ 2015-2022 runtime (Win Components "vcrun2015"). The DLLs are not
 * shipped in the APK. They are taken out of Microsoft's own installers
 * (vc_redist.x86.exe / vc_redist.x64.exe):
 *   1. downloaded from Microsoft's official addresses (aka.ms/vc14) after the
 *      user agrees to the license terms; kept only in the cache until read
 *   2. if that fails, installers already on the device: those that come with
 *      Steam games (steamapps/common, e.g. _CommonRedist or "Steamworks Shared")
 *      and those the user put in Download/.EZlator/VCRedist
 * The installers are not run: the DLLs are read from the cabinets inside them
 * (no Windows code runs). The newest version of each DLL is kept in imagefs, and
 * turning the component on copies them into the prefix at every launch that needs it.
 * Files put into a prefix are not taken out again: turning the component off only
 * makes Wine use its builtin DLLs, and a newer file the user installed is kept.
 * Such a newer file is also kept on the device for that game only (games/<key>),
 * so it comes back when the game runs in another container.
 * Visual C++ 2015~2022 런타임(Win Components "vcrun2015")임. DLL은 APK에 넣지 않음.
 * Microsoft 설치 파일(vc_redist.x86.exe / vc_redist.x64.exe)에서 꺼냄:
 *   1. 사용자가 사용 조건에 동의하면 Microsoft 공식 주소(aka.ms/vc14)에서 받음.
 *      읽을 때까지만 캐시에 둠
 *   2. 실패하면 기기에 이미 있는 설치 파일: 스팀 게임과 함께 받은 것(steamapps/common,
 *      예: _CommonRedist나 "Steamworks Shared")과 Download/.EZlator/VCRedist에 직접
 *      넣은 것
 * 설치 프로그램은 실행하지 않음: 그 안의 캐비닛에서 DLL을 읽어 냄(Windows 코드는
 * 실행하지 않음). DLL마다 가장 새 버전을 imagefs에 보관하고, 항목을 켜면 필요할 때마다
 * 실행 시 프리픽스에 복사함. 프리픽스에 넣은 파일은 다시 빼지 않음: 항목을 끄면
 * Wine 기본 DLL을 쓰게만 하고, 사용자가 설치한 더 새 파일은 그대로 둠. 그런 더 새
 * 파일은 기기에도 그 게임 전용(games/<키>)으로 보관하므로, 다른 컨테이너에서 그 게임을
 * 실행해도 다시 들어감
 *
 * Layout of vc_redist (WiX Burn bundle): the .exe carries a cabinet whose entries
 * ("a10", "a12", ...) are cabinets again; those hold the DLLs, named either plainly
 * ("msvcp140.dll") or with an arch suffix ("msvcp140.dll_amd64"). The entry names
 * change between releases, so every cabinet is searched instead of fixed names.
 * vc_redist 구조(WiX Burn 묶음): .exe 안에 캐비닛이 있고, 그 안의 항목("a10", "a12" 등)이
 * 다시 캐비닛임. 그 안에 DLL이 그냥 이름("msvcp140.dll")이나 아키텍처 꼬리표가 붙은
 * 이름("msvcp140.dll_amd64")으로 들어 있음. 항목 이름은 릴리즈마다 바뀌므로 정해진
 * 이름 대신 모든 캐비닛을 찾아봄
 */
public final class VCRedistInstaller {
    private static final String TAG = "VCRedistInstaller";

    // Win Components id (wincomponents.json key)
    // Win Components 식별자(wincomponents.json의 키)
    public static final String ID = "vcrun2015";

    // Microsoft's license terms for the runtime, linked from Microsoft Learn
    // Microsoft Learn이 안내하는 이 런타임의 사용 조건 주소
    public static final String LICENSE_URL = "https://aka.ms/VCRedistLicense";

    // Folder for installers put by hand (Download/.EZlator/VCRedist)
    // 직접 넣는 설치 파일용 폴더(Download/.EZlator/VCRedist)
    public static final String MANUAL_SUBDIR = "VCRedist";

    // Microsoft's official download addresses (Microsoft Learn, "Latest supported
    // Visual C++ Redistributable downloads"); they always point at the latest build
    // Microsoft 공식 받기 주소(Microsoft Learn "최신 지원 Visual C++ 재배포 가능 패키지
    // 다운로드"). 항상 최신 빌드를 가리킴
    static final String URL_X64 = "https://aka.ms/vc14/vc_redist.x64.exe";
    static final String URL_X86 = "https://aka.ms/vc14/vc_redist.x86.exe";

    // Cache folder for the downloaded installers, deleted after the DLLs are taken
    // 받은 설치 파일을 두는 캐시 폴더. DLL을 꺼낸 뒤 지움
    private static final String DOWNLOAD_SUBDIR = "vcredist-download";

    // Installer file names (any letter case; Microsoft writes "VC_redist.x64.exe")
    // 설치 파일 이름(대소문자 무관. Microsoft는 "VC_redist.x64.exe"로 씀)
    private static final String NAME_X86 = "vc_redist.x86.exe";
    private static final String NAME_X64 = "vc_redist.x64.exe";

    // Without these an installer's set is not taken; the rest of the list is taken when present
    // 이것들이 없으면 그 설치 파일의 묶음은 쓰지 않음. 목록의 나머지는 있으면 가져감
    private static final String[] REQUIRED_X86 = {"msvcp140", "vcruntime140"};
    private static final String[] REQUIRED_X64 = {"msvcp140", "vcruntime140", "vcruntime140_1"};

    // How deep the Steam library is searched (common/<game>/_CommonRedist/vcredist/<year>/x.exe)
    // 스팀 라이브러리를 찾는 깊이(common/<게임>/_CommonRedist/vcredist/<연도>/x.exe)
    private static final int MAX_DEPTH = 6;

    // PE machine types / PE 머신 종류
    private static final int MACHINE_I386 = 0x014C;
    private static final int MACHINE_AMD64 = 0x8664;

    // Stamp names: in the store (contents version), in a prefix's system32 (applied),
    // and the list of installers already read
    // 표시 파일 이름: 보관소(내용 버전), 프리픽스의 system32(적용됨), 이미 읽은 설치 파일 목록
    private static final String STAMP = ".installed";
    private static final String APPLIED_MARK = ".ezlator-vcrun2015";
    private static final String SCANNED = ".scanned";

    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private VCRedistInstaller() {}

    /** Status text while searching / 찾는 동안 보여 줄 상태 문구 */
    public interface Progress {
        void onStatus(String text);
    }

    // ==================== Store (imagefs) / 보관소(imagefs) ====================

    // <imagefs>/.winlator/vcrun2015/{system32,syswow64}
    private static File storeDir(ImageFs imageFs) {
        return new File(imageFs.getConfigDir(), ID);
    }

    public static boolean isInstalled(ImageFs imageFs) {
        return new File(storeDir(imageFs), STAMP).isFile();
    }

    private static String readStamp(ImageFs imageFs) {
        try {
            return FileUtils.readString(new File(storeDir(imageFs), STAMP)).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static File manualDir() {
        return AppDirs.downloads(MANUAL_SUBDIR);
    }

    // ==================== Apply to a prefix / 프리픽스에 적용 ====================

    // Folders of a prefix (and of the store) that hold the DLLs
    // DLL이 들어가는 프리픽스(와 보관소)의 폴더
    private static final String[] SUBDIRS = {"system32", "syswow64"};

    /*
     * Per-game copies: <store>/games/<game key>/{system32,syswow64} + game.txt.
     * The shared store is filled only from Microsoft's installers (cabinets).
     * A newer DLL the user installed inside a container (no installer found for
     * it) is kept here, for that game only: it comes back when that game runs
     * in any container, even after the container is deleted, and it is not
     * spread to other games (where it came from cannot be checked).
     * 게임별 사본: <보관소>/games/<게임 키>/{system32,syswow64} + game.txt.
     * 공용 보관소는 Microsoft 설치 파일(캐비닛)에서만 채움. 사용자가 컨테이너 안에서
     * 설치한 더 새 DLL(설치 파일을 못 찾은 것)은 여기에 그 게임 전용으로 둠: 컨테이너를
     * 지워도 그 게임을 어느 컨테이너에서 실행하든 다시 들어가고, 다른 게임에는 퍼지지
     * 않음(출처를 확인할 수 없으므로)
     */
    private static final String GAMES_DIR = "games";

    // Container extras: the game whose newer DLLs are not kept yet (set at launch,
    // cleared after the game ends and they are kept)
    // 컨테이너 extra: 더 새 DLL을 아직 보관하지 않은 게임(실행 때 적고, 게임이 끝나
    // 보관한 뒤 지움)
    public static final String LAST_GAME_EXTRA = "vcrunLastGame";
    public static final String LAST_GAME_NAME_EXTRA = "vcrunLastGameName";
    private static final String GAME_INFO = "game.txt";

    private static File gameDir(ImageFs imageFs, String gameKey) {
        return new File(new File(storeDir(imageFs), GAMES_DIR), gameKey);
    }

    /**
     * Key of a game for its own copies: "steam-<AppID>" for a Steam game,
     * otherwise "exe-" + a hash of the shortcut's exe path (the same game added
     * again finds its copies). null when neither is known.
     * 게임별 사본의 키: 스팀 게임은 "steam-<AppID>", 그 외는 "exe-" + 바로가기 exe
     * 경로의 해시(같은 게임을 다시 추가해도 사본을 찾음). 둘 다 모르면 null
     */
    public static String gameKey(String steamAppId, String exePath) {
        String id = steamAppId == null ? "" : steamAppId.replaceAll("[^0-9]", "");
        if (!id.isEmpty()) return "steam-" + id;
        if (exePath == null || exePath.trim().isEmpty()) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(exePath.trim().toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("exe-");
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True for Wine's own PE files ("Wine builtin DLL" / "Wine placeholder DLL"
     * at offset 0x40). Their version numbers say nothing about Microsoft's
     * runtime, so they always count as "none".
     * Wine 자체 PE 파일이면 true(0x40 위치의 "Wine builtin DLL" / "Wine placeholder
     * DLL"). 그 버전 값은 Microsoft 런타임과 무관하므로 항상 "없음"으로 봄
     */
    static boolean isWineBuiltin(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            if (f.length() < 0x60) return false;
            byte[] b = new byte[0x20];
            f.seek(0x40);
            f.readFully(b);
            String s = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
            return s.startsWith("Wine builtin DLL") || s.startsWith("Wine placeholder DLL");
        } catch (IOException e) {
            return false;
        }
    }

    // File version of a DLL in a prefix; -1 when missing, Wine's own, or unknown
    // 프리픽스 DLL의 파일 버전. 없거나 Wine 자체 파일이거나 모르면 -1
    static long prefixVersion(File file) {
        if (!file.isFile() || isWineBuiltin(file)) return -1;
        return fileVersion(file);
    }

    // "<sub>/<name>" -> kept file to use for this launch: the shared copy, or
    // this game's copy when it is newer
    // "<폴더>/<이름>" -> 이번 실행에 쓸 보관 파일: 공용 사본, 또는 더 새로우면 이 게임의 사본
    private static Map<String, File> sources(ImageFs imageFs, String gameKey) {
        Map<String, File> best = new HashMap<>();
        Map<String, Long> bestVer = new HashMap<>();
        List<File> roots = new ArrayList<>();
        if (isInstalled(imageFs)) roots.add(storeDir(imageFs));
        if (gameKey != null) roots.add(gameDir(imageFs, gameKey));
        for (File root : roots) {
            for (String sub : SUBDIRS) {
                File[] dlls = new File(root, sub).listFiles();
                if (dlls == null) continue;
                for (File dll : dlls) {
                    // Skip work files (.new while one is written)
                    // 작업 파일(쓰는 중인 .new)은 건너뜀
                    if (!dll.getName().toLowerCase(Locale.ROOT).endsWith(".dll")) continue;
                    String key = sub + "/" + dll.getName().toLowerCase(Locale.ROOT);
                    long v = fileVersion(dll);
                    Long old = bestVer.get(key);
                    if (old == null || v > old) {
                        best.put(key, dll);
                        bestVer.put(key, v);
                    }
                }
            }
        }
        return best;
    }

    // "<sub>/<name>" -> every version kept on this device (shared + all games)
    // "<폴더>/<이름>" -> 이 기기에 보관한 모든 버전(공용 + 모든 게임)
    private static Map<String, Set<Long>> keptVersions(ImageFs imageFs) {
        Map<String, Set<Long>> all = new HashMap<>();
        List<File> roots = new ArrayList<>();
        roots.add(storeDir(imageFs));
        File[] games = new File(storeDir(imageFs), GAMES_DIR).listFiles();
        if (games != null) for (File g : games) if (g.isDirectory()) roots.add(g);
        for (File root : roots) {
            for (String sub : SUBDIRS) {
                File[] dlls = new File(root, sub).listFiles();
                if (dlls == null) continue;
                for (File dll : dlls) {
                    if (!dll.getName().toLowerCase(Locale.ROOT).endsWith(".dll")) continue;
                    long v = fileVersion(dll);
                    if (v == -1) continue;
                    String key = sub + "/" + dll.getName().toLowerCase(Locale.ROOT);
                    Set<Long> set = all.get(key);
                    if (set == null) all.put(key, set = new HashSet<>());
                    set.add(v);
                }
            }
        }
        return all;
    }

    private static long max(Set<Long> set) {
        long m = -1;
        if (set != null) for (long v : set) m = Math.max(m, v);
        return m;
    }

    /*
     * The mark (windows/system32/.ezlator-vcrun2015) lists the files EZlator
     * counts as its own in this prefix, one per line as "<sub>/<name>\t<file
     * version>"; the first line is the store stamp. A file is EZlator's while it
     * has the version of a kept copy (put in by EZlator, or the same version the
     * user installed). It is used to put back files a container export left out.
     * 표시 파일(windows/system32/.ezlator-vcrun2015)은 이 프리픽스에서 EZlator 것으로
     * 보는 파일 목록임. 한 줄에 "<폴더>/<이름>\t<파일 버전>" 하나씩이고 첫 줄은 보관소
     * 표시값임. 보관 사본과 같은 버전인 동안 EZlator 것임(EZlator가 넣었거나 사용자가
     * 같은 버전을 설치함). 컨테이너 내보내기에서 뺀 파일을 다시 채울 때 씀
     */
    private static File markFile(File windowsDir) {
        return new File(windowsDir, "system32/" + APPLIED_MARK);
    }

    public static boolean isMarked(File windowsDir) {
        return markFile(windowsDir).isFile();
    }

    // "<sub>/<name>" -> version from the mark; null when there is no mark,
    // an empty map for a mark of an older build (stamp only)
    // 표시 파일의 "<폴더>/<이름>" -> 버전. 표시 파일이 없으면 null,
    // 예전 빌드의 표시 파일(표시값만)이면 빈 목록
    private static Map<String, Long> readApplied(File windowsDir) {
        File mark = markFile(windowsDir);
        if (!mark.isFile()) return null;
        Map<String, Long> applied = new HashMap<>();
        try {
            String text = FileUtils.readString(mark);
            if (text == null) return applied;
            String[] lines = text.split("\n");
            // The first line is the store stamp
            // 첫 줄은 보관소 표시값임
            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].trim().split("\t");
                if (parts.length != 2) continue;
                try {
                    applied.put(parts[0], Long.parseLong(parts[1]));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return applied;
    }

    private static void writeApplied(ImageFs imageFs, File windowsDir, Map<String, Long> applied) {
        StringBuilder text = new StringBuilder(readStamp(imageFs));
        for (Map.Entry<String, Long> e : applied.entrySet()) {
            text.append('\n').append(e.getKey()).append('\t').append(e.getValue());
        }
        FileUtils.writeString(markFile(windowsDir), text.toString());
    }

    /**
     * Puts the kept DLLs (shared, or this game's copy when newer) into
     * windows/system32 (64-bit) and windows/syswow64 (32-bit). Only adds, never
     * takes out: a DLL is copied when the prefix has none, Wine's own, or an
     * older file version (e.g. an old one a game installer put back); a newer
     * one is left as it is. false when nothing is kept for this launch.
     * 보관한 DLL(공용, 또는 더 새로우면 이 게임의 사본)을 windows/system32(64비트)와
     * windows/syswow64(32비트)에 넣음. 넣기만 하고 빼지 않음: 프리픽스에 없거나, Wine
     * 자체 파일이거나, 파일 버전이 더 낮을 때(예: 게임 설치 프로그램이 옛 버전으로 덮은
     * 경우)만 복사하고, 더 높으면 그대로 둠. 이번 실행에 쓸 보관본이 없으면 false
     */
    public static boolean applyTo(ImageFs imageFs, File windowsDir, String gameKey) {
        Map<String, File> src = sources(imageFs, gameKey);
        if (src.isEmpty()) return false;
        Map<String, Long> before = readApplied(windowsDir);
        Map<String, Long> applied = new HashMap<>();
        int copied = 0, kept = 0;
        for (Map.Entry<String, File> e : src.entrySet()) {
            String key = e.getKey();
            File dst = new File(windowsDir, key);
            if (!dst.getParentFile().isDirectory()) continue;
            long srcVer = fileVersion(e.getValue());
            long dstVer = prefixVersion(dst);
            boolean replace = srcVer == -1
                    ? (!dst.isFile() || isWineBuiltin(dst))
                    : dstVer < srcVer;
            if (replace) {
                if (FileUtils.copy(e.getValue(), dst) && dst.isFile()) {
                    applied.put(key, srcVer);
                    copied++;
                }
            } else if (dstVer == srcVer) {
                // Same version as the kept copy: EZlator's (it can be put back)
                // 보관 사본과 같은 버전: EZlator 것으로 봄(다시 넣을 수 있음)
                applied.put(key, srcVer);
            } else {
                // Newer: left alone
                // 더 높음: 그대로 둠
                kept++;
            }
        }
        // Files of another game's copy that are still in place stay on the list
        // 다른 게임 사본에서 넣은 파일이 그대로 남아 있으면 목록에 유지함
        if (before != null) {
            for (Map.Entry<String, Long> e : before.entrySet()) {
                if (applied.containsKey(e.getKey()) || e.getValue() == -1) continue;
                if (prefixVersion(new File(windowsDir, e.getKey())) == e.getValue()) applied.put(e.getKey(), e.getValue());
            }
        }
        writeApplied(imageFs, windowsDir, applied);
        Log.i(TAG, "applied to " + windowsDir + " (game " + gameKey + "): copied " + copied + ", listed " + applied.size() + ", newer kept " + kept);
        return true;
    }

    /**
     * True when applyTo has something to do: a kept DLL is missing in the
     * prefix, Wine's own, or older there. Reads the version of each file, so a
     * game installer that put old files back is noticed too.
     * applyTo가 할 일이 있으면 true: 보관한 DLL이 프리픽스에 없거나, Wine 자체 파일이거나,
     * 더 낮은 버전임. 파일마다 버전을 읽으므로 게임 설치 프로그램이 옛 파일로 덮은 것도 알아챔
     */
    public static boolean needsApply(ImageFs imageFs, File windowsDir, String gameKey) {
        for (Map.Entry<String, File> e : sources(imageFs, gameKey).entrySet()) {
            File dst = new File(windowsDir, e.getKey());
            if (!dst.getParentFile().isDirectory()) continue;
            long srcVer = fileVersion(e.getValue());
            if (srcVer == -1 ? (!dst.isFile() || isWineBuiltin(dst)) : prefixVersion(dst) < srcVer) return true;
        }
        return false;
    }

    /**
     * "<sub>/<name>" of files on the mark that are gone from the prefix (a
     * container exported without them, see exportExcluded). When no kept copy
     * can fill them, Wine's builtin files are put back in their place.
     * 표시 파일 목록에 있지만 프리픽스에서 없어진 파일의 "<폴더>/<이름>"(이 파일 없이
     * 내보낸 컨테이너, exportExcluded 참고). 보관 사본으로 채울 수 없으면 그 자리에 Wine
     * 기본 파일을 다시 넣음
     */
    public static List<String> missingApplied(File windowsDir) {
        List<String> missing = new ArrayList<>();
        Map<String, Long> applied = readApplied(windowsDir);
        if (applied == null) return missing;
        for (String key : applied.keySet()) {
            if (!new File(windowsDir, key).isFile()) missing.add(key);
        }
        return missing;
    }

    /**
     * True when the launch has Visual C++ work: on and applyTo has something to
     * do, files on the mark are missing (on or off), or, while on, the registry
     * entries are not written yet / the kept copy changed (see syncRegistry).
     * 실행 때 Visual C++ 작업이 있으면 true: 켜져 있고 applyTo가 할 일이 있거나,
     * 표시 파일 목록의 파일이 없어졌거나(켜짐·꺼짐 무관), 켜져 있을 때 레지스트리
     * 항목을 아직 안 썼거나 보관본이 바뀌었음(syncRegistry 참고)
     */
    public static boolean needsWork(ImageFs imageFs, File windowsDir, boolean on, String gameKey) {
        if (on && needsApply(imageFs, windowsDir, gameKey)) return true;
        if (!missingApplied(windowsDir).isEmpty()) return true;
        return on && registryPending(imageFs, windowsDir);
    }

    // ==================== Registry / 레지스트리 ====================

    // Record of the registry entries EZlator wrote (windows/system32/.ezlator-vcrun2015-reg).
    // First line "stamp\t<store stamp when written>", then one line per arch:
    // "<arch>\t<Version text>", or "<arch>\t-" when the key was already there
    // (written by Microsoft's installer run inside the container) and is left alone
    // EZlator가 쓴 레지스트리 항목 기록(windows/system32/.ezlator-vcrun2015-reg).
    // 첫 줄 "stamp\t<쓸 때의 보관소 표시값>", 이어서 아키텍처마다 한 줄:
    // "<아키텍처>\t<Version 문자열>". 키가 이미 있었으면(컨테이너 안에서 실행한
    // Microsoft 설치 프로그램이 씀) "<아키텍처>\t-"로 적고 건드리지 않음
    private static final String REG_MARK = ".ezlator-vcrun2015-reg";
    private static final String REG_STAMP = "stamp";
    private static final String NOT_OURS = "-";

    // arch -> folder whose vcruntime140.dll gives that arch's version
    // 아키텍처 -> 그 아키텍처 버전을 알려 주는 vcruntime140.dll이 있는 폴더
    private static final String[][] REG_ARCHES = {{"x64", "system32"}, {"x86", "syswow64"}};

    // Microsoft's documented place is the 32-bit view (Wow6432Node); the same
    // values are also put in the 64-bit view so 64-bit programs that look there
    // find them too
    // Microsoft 문서에 적힌 위치는 32비트 보기(Wow6432Node)임. 같은 값을 64비트 보기에도
    // 넣어 그쪽을 보는 64비트 프로그램도 찾게 함
    private static String[] runtimeKeys(String arch) {
        return new String[] {
                "Software\\Wow6432Node\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\" + arch,
                "Software\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\" + arch
        };
    }

    private static File regMarkFile(File windowsDir) {
        return new File(windowsDir, "system32/" + REG_MARK);
    }

    // key -> recorded text ("stamp" and arches); empty when there is no record
    // 키 -> 기록된 문자열("stamp"와 아키텍처). 기록이 없으면 빈 목록
    private static Map<String, String> readRegMark(File windowsDir) {
        Map<String, String> rec = new HashMap<>();
        File f = regMarkFile(windowsDir);
        if (!f.isFile()) return rec;
        String text = FileUtils.readString(f);
        if (text == null) return rec;
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\t");
            if (parts.length == 2) rec.put(parts[0], parts[1]);
        }
        return rec;
    }

    private static void writeRegMark(File windowsDir, Map<String, String> rec) {
        StringBuilder text = new StringBuilder();
        text.append(REG_STAMP).append('\t').append(rec.getOrDefault(REG_STAMP, "")).append('\n');
        for (String[] a : REG_ARCHES) {
            String v = rec.get(a[0]);
            if (v != null) text.append(a[0]).append('\t').append(v).append('\n');
        }
        FileUtils.writeString(regMarkFile(windowsDir), text.toString());
    }

    // "v14.44.35211.00" style text, the form the installer writes; null when unknown
    // 설치 프로그램이 쓰는 "v14.44.35211.00" 형식 문자열. 모르면 null
    static String regVersionText(long v) {
        if (v == -1) return null;
        return String.format(Locale.ENGLISH, "v%d.%d.%d.%02d",
                v >>> 48, (v >>> 32) & 0xFFFF, (v >>> 16) & 0xFFFF, v & 0xFFFF);
    }

    // Version from Microsoft's vcruntime140.dll in the prefix; null when only
    // Wine's own file (or nothing) is there
    // 프리픽스의 Microsoft vcruntime140.dll에서 읽은 버전. Wine 자체 파일뿐이거나
    // 파일이 없으면 null
    private static String prefixRegVersion(File windowsDir, String sub) {
        return regVersionText(prefixVersion(new File(windowsDir, sub + "/vcruntime140.dll")));
    }

    /**
     * True when syncRegistry has something to do: an arch with Microsoft's file
     * and no entry yet, or entries EZlator wrote while the kept copy has changed
     * since ("Get again" / a newer download).
     * syncRegistry가 할 일이 있으면 true: Microsoft 파일이 있는데 아직 항목이 없는
     * 아키텍처가 있거나, EZlator가 쓴 뒤 보관본이 바뀌었음([다시 받기] / 새로 받음)
     */
    static boolean registryPending(ImageFs imageFs, File windowsDir) {
        Map<String, String> rec = readRegMark(windowsDir);
        boolean stampChanged = !readStamp(imageFs).equals(rec.getOrDefault(REG_STAMP, ""));
        for (String[] a : REG_ARCHES) {
            String have = rec.get(a[0]);
            if (NOT_OURS.equals(have)) continue;
            String want = prefixRegVersion(windowsDir, a[1]);
            if (want == null) continue;
            if (have == null || (stampChanged && !have.equals(want))) return true;
        }
        // Same versions after a new download: only the stamp is brought up to date
        // 새로 받았어도 버전이 같으면 표시값만 맞춤
        return stampChanged && !rec.isEmpty();
    }

    /**
     * Writes the "installed" registry entries of the Visual C++ 2015-2022
     * runtime (HKLM\SOFTWARE[\WOW6432Node]\Microsoft\VisualStudio\14.0\VC\Runtimes\{x64|x86}:
     * Installed, Version, Major, Minor, Bld, Rbld) for games and launchers that
     * check these instead of the DLLs. Called while the option is on.
     *  - written once, from the version of Microsoft's vcruntime140.dll in the prefix
     *  - updated only when the kept copy changes ("Get again" / a newer download)
     *  - never removed (turning the option off or Wine's builtin files coming back
     *    leaves them as they are)
     *  - a key that was there before EZlator wrote one is left alone
     * Visual C++ 2015~2022 런타임의 "설치됨" 레지스트리 항목
     * (HKLM\SOFTWARE[\WOW6432Node]\Microsoft\VisualStudio\14.0\VC\Runtimes\{x64|x86}:
     * Installed, Version, Major, Minor, Bld, Rbld)을 씀. DLL 대신 이 값을 보는 게임과
     * 런처를 위함. 항목이 켜져 있을 때 부름.
     *  - 프리픽스의 Microsoft vcruntime140.dll 버전으로 한 번 씀
     *  - 보관본이 바뀔 때만 고침([다시 받기] / 새로 받음)
     *  - 지우지 않음(항목을 끄거나 Wine 기본 파일로 돌아가도 그대로 둠)
     *  - EZlator가 쓰기 전에 이미 있던 키는 건드리지 않음
     */
    public static void syncRegistry(ImageFs imageFs, File systemRegFile, File windowsDir) {
        if (!registryPending(imageFs, windowsDir)) return;
        Map<String, String> rec = readRegMark(windowsDir);
        String stamp = readStamp(imageFs);
        boolean stampChanged = !stamp.equals(rec.getOrDefault(REG_STAMP, ""));
        try (WineRegistryEditor reg = new WineRegistryEditor(systemRegFile)) {
            for (String[] a : REG_ARCHES) {
                String arch = a[0];
                String have = rec.get(arch);
                if (NOT_OURS.equals(have)) continue;
                String want = prefixRegVersion(windowsDir, a[1]);
                if (want == null) continue;
                if (have != null && (!stampChanged || have.equals(want))) continue;
                String[] keys = runtimeKeys(arch);
                if (have == null && reg.getDwordValue(keys[0], "Installed") != null) {
                    // Written by Microsoft's installer inside the container: left alone
                    // 컨테이너 안에서 Microsoft 설치 프로그램이 씀: 건드리지 않음
                    rec.put(arch, NOT_OURS);
                    Log.i(TAG, "registry " + arch + " already present, left alone");
                    continue;
                }
                long v = prefixVersion(new File(windowsDir, a[1] + "/vcruntime140.dll"));
                for (String key : keys) {
                    reg.setDwordValue(key, "Installed", 1);
                    reg.setStringValue(key, "Version", want);
                    reg.setDwordValue(key, "Major", (int) (v >>> 48));
                    reg.setDwordValue(key, "Minor", (int) ((v >>> 32) & 0xFFFF));
                    reg.setDwordValue(key, "Bld", (int) ((v >>> 16) & 0xFFFF));
                    reg.setDwordValue(key, "Rbld", (int) (v & 0xFFFF));
                }
                rec.put(arch, want);
                Log.i(TAG, "registry " + arch + " written: " + want);
            }
        }
        rec.put(REG_STAMP, stamp);
        writeRegMark(windowsDir, rec);
    }

    /**
     * Keeps, for this game only, the Visual C++ DLLs in the prefix that are
     * newer than every copy kept on this device (a runtime the user installed
     * inside the container). Only Microsoft's files of the right kind are taken:
     * not Wine's own, x64 in system32 and x86 in syswow64. Called after the game
     * ends, at the next launch (if the app was closed before that) and before
     * the container is deleted. Returns the number of files kept.
     * 프리픽스에 있는 Visual C++ DLL 중 이 기기에 보관한 모든 사본보다 새로운 것(사용자가
     * 컨테이너 안에서 설치한 런타임)을 이 게임 전용으로 보관함. 알맞은 종류의 Microsoft
     * 파일만 가져감: Wine 자체 파일 제외, system32는 x64, syswow64는 x86. 게임이 끝난 뒤,
     * (그 전에 앱이 닫혔으면) 다음 실행 때, 컨테이너를 지우기 전에 부름. 보관한 파일 수를 돌려줌
     */
    public static int collectNewer(Context context, ImageFs imageFs, File windowsDir, String gameKey, String label) {
        if (gameKey == null || !new File(windowsDir, "system32").isDirectory()) return 0;
        int count = 0;
        File store = storeDir(imageFs);
        try {
            List<String> names = dllNames(context);
            if (!store.isDirectory() && !store.mkdirs()) return 0;
            File gameDir = gameDir(imageFs, gameKey);
            try (RandomAccessFile lockRaf = new RandomAccessFile(new File(store, ".lock"), "rw");
                 FileChannel channel = lockRaf.getChannel();
                 FileLock ignored = channel.lock()) {
                Map<String, Set<Long>> kept = keptVersions(imageFs);
                for (String sub : SUBDIRS) {
                    int machine = "system32".equals(sub) ? MACHINE_AMD64 : MACHINE_I386;
                    for (String dll : names) {
                        String key = sub + "/" + dll + ".dll";
                        File f = new File(windowsDir, key);
                        long v = prefixVersion(f);
                        if (v == -1 || v <= max(kept.get(key))) continue;
                        if (peMachine(f) != machine) continue;
                        File dstDir = new File(gameDir, sub);
                        if (!dstDir.isDirectory() && !dstDir.mkdirs()) continue;
                        File out = new File(dstDir, dll + ".dll");
                        File tmp = new File(dstDir, dll + ".dll.new");
                        if (!FileUtils.copy(f, tmp) || !tmp.isFile()) continue;
                        out.delete();
                        if (!tmp.renameTo(out)) {
                            tmp.delete();
                            continue;
                        }
                        Log.i(TAG, "kept for " + gameKey + ": " + key + " " + versionText(v));
                        count++;
                    }
                }
                if (count > 0) FileUtils.writeString(new File(gameDir, GAME_INFO), (label == null ? "" : label) + "\n" + gameKey);
            }
        } catch (Exception e) {
            Log.w(TAG, "collect for " + gameKey + " failed", e);
        }
        return count;
    }

    /**
     * Files of a prefix that a container export leaves out: Visual C++ DLLs with
     * the version of a copy kept on this device (shared or any game's).
     * Microsoft's files are not to be shared; an imported container gets them
     * again from the kept copies of the device it is imported on. A newer file
     * not kept anywhere is exported as it is.
     * 컨테이너 내보내기에서 뺄 프리픽스 파일: 이 기기에 보관한 사본(공용 또는 어느 게임의
     * 것)과 버전이 같은 Visual C++ DLL. Microsoft 파일은 공유하지 않아야 하며, 가져온
     * 컨테이너는 가져온 기기의 보관 사본에서 다시 받음. 어디에도 보관되지 않은 더 새 파일은
     * 그대로 내보냄
     */
    public static Set<String> exportExcluded(ImageFs imageFs, File windowsDir) {
        Set<String> paths = new HashSet<>();
        for (Map.Entry<String, Set<Long>> e : keptVersions(imageFs).entrySet()) {
            File f = new File(windowsDir, e.getKey());
            if (!f.isFile()) {
                // The prefix may spell the name in another letter case
                // 프리픽스 쪽 이름의 대소문자가 다를 수 있음
                f = findIgnoreCase(windowsDir, e.getKey());
                if (f == null) continue;
            }
            if (e.getValue().contains(prefixVersion(f))) paths.add(f.getAbsolutePath());
        }
        return paths;
    }

    private static File findIgnoreCase(File windowsDir, String key) {
        int slash = key.indexOf('/');
        File[] files = new File(windowsDir, key.substring(0, slash)).listFiles();
        if (files == null) return null;
        String name = key.substring(slash + 1);
        for (File f : files) if (f.getName().equalsIgnoreCase(name) && f.isFile()) return f;
        return null;
    }

    // ==================== Harvest / 꺼내 보관하기 ====================

    /**
     * Reads every installer not read before under the given folders (and the
     * manual folder) and keeps the newest DLLs. Blocking; call off the UI thread.
     * Returns the number of installers that gave a usable set. Safe to call from
     * both app processes (the store is locked while it is written).
     * 주어진 폴더들(과 직접 넣기 폴더) 아래에서 전에 읽지 않은 설치 파일을 모두 읽어
     * 가장 새 DLL을 보관함. 블로킹이므로 UI 스레드 밖에서 불러야 함. 쓸 수 있는 묶음을
     * 준 설치 파일 수를 돌려줌. 앱의 두 프로세스에서 불러도 됨(쓰는 동안 보관소를 잠금)
     */
    public static int harvest(Context context, List<File> roots, Progress progress) throws IOException {
        ImageFs imageFs = ImageFs.find(context);
        List<String> dllNames = dllNames(context);
        File store = storeDir(imageFs);
        if (!store.isDirectory() && !store.mkdirs()) throw new IOException("cannot create " + store);

        // Installers to read: the manual folder first, then the given folders
        // 읽을 설치 파일: 직접 넣기 폴더가 먼저, 그다음 주어진 폴더들
        List<File> installers = new ArrayList<>();
        findInstallers(manualDir(), 1, installers);
        for (File root : roots) findInstallers(root, MAX_DEPTH, installers);

        File lockFile = new File(store, ".lock");
        try (RandomAccessFile lockRaf = new RandomAccessFile(lockFile, "rw");
             FileChannel channel = lockRaf.getChannel();
             FileLock ignored = channel.lock()) {
            Set<String> scanned = readScanned(store);
            int usable = 0;
            boolean changed = false;
            File work = new File(context.getCacheDir(), "vcredist");
            for (File exe : installers) {
                String key = exe.getAbsolutePath() + "|" + exe.length() + "|" + exe.lastModified();
                if (scanned.contains(key)) continue;
                boolean x64 = exe.getName().equalsIgnoreCase(NAME_X64);
                if (progress != null) progress.onStatus(exe.getName());
                FileUtils.delete(work);
                File outDir = new File(work, "out");
                outDir.mkdirs();
                try {
                    extractRuntime(exe, new File(work, "tmp"), outDir, dllNames, x64);
                    boolean complete = true;
                    for (String required : x64 ? REQUIRED_X64 : REQUIRED_X86) {
                        if (!new File(outDir, required + ".dll").isFile()) complete = false;
                    }
                    if (complete) {
                        usable++;
                        if (mergeNewer(outDir, new File(store, x64 ? "system32" : "syswow64"))) changed = true;
                    } else {
                        Log.w(TAG, "required DLLs missing in " + exe);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "could not read " + exe + ": " + e.getMessage());
                } finally {
                    FileUtils.delete(work);
                }
                scanned.add(key);
            }
            writeScanned(store, scanned);
            // A new stamp makes every prefix that has the component on copy again
            // 표시가 바뀌면 이 항목을 켠 모든 프리픽스가 다시 복사함
            if (changed) FileUtils.writeString(new File(store, STAMP), String.valueOf(System.currentTimeMillis()));
            Log.i(TAG, "harvest: " + installers.size() + " installer(s) found, " + usable + " usable, changed=" + changed);
            return usable;
        }
    }

    // Steam library (steamapps/common), the place Steam games and "Steamworks Shared" go
    // 스팀 라이브러리(steamapps/common). 스팀 게임과 "Steamworks Shared"가 들어가는 곳
    public static List<File> steamRoots(Context context) {
        List<File> roots = new ArrayList<>();
        // Both libraries (internal, SD card) / 두 라이브러리(내장, SD카드)
        for (File lib : SteamGameInstaller.getLibraryDirs(context)) roots.add(new File(lib, "common"));
        return roots;
    }

    /**
     * After a Steam download: reads the installers of the library (the new game
     * and "Steamworks Shared"). Never throws; failures are only logged.
     * 스팀 다운로드가 끝난 뒤 부름: 라이브러리의 설치 파일(새 게임과 "Steamworks
     * Shared")을 읽음. 예외를 던지지 않고 실패는 로그로만 남김
     */
    public static void harvestAfterSteamDownload(Context context) {
        try {
            harvest(context, steamRoots(context), null);
        } catch (Exception e) {
            Log.w(TAG, "harvest after download failed", e);
        }
    }

    // Replaces a kept DLL only by a newer file version (or adds a missing one)
    // 보관한 DLL은 파일 버전이 더 새로울 때만 바꿈(없으면 추가함)
    private static boolean mergeNewer(File fromDir, File storeSub) throws IOException {
        if (!storeSub.isDirectory() && !storeSub.mkdirs()) throw new IOException("cannot create " + storeSub);
        boolean changed = false;
        File[] dlls = fromDir.listFiles();
        if (dlls == null) return false;
        for (File dll : dlls) {
            File kept = new File(storeSub, dll.getName());
            long newVer = fileVersion(dll);
            long oldVer = kept.isFile() ? fileVersion(kept) : -1;
            // Unknown version (-1) never replaces a kept file
            // 버전을 모르는 파일(-1)은 보관한 파일을 바꾸지 않음
            if (kept.isFile() && newVer <= oldVer) continue;
            File tmp = new File(storeSub, dll.getName() + ".new");
            if (!FileUtils.copy(dll, tmp) || !tmp.isFile()) throw new IOException("cannot copy " + dll);
            kept.delete();
            if (!tmp.renameTo(kept)) throw new IOException("cannot store " + kept);
            Log.i(TAG, storeSub.getName() + "/" + kept.getName() + " " + versionText(oldVer) + " -> " + versionText(newVer));
            changed = true;
        }
        return changed;
    }

    // Collects vc_redist.x86.exe / vc_redist.x64.exe below dir, down to "depth" levels
    // dir 아래 depth 단계까지 vc_redist.x86.exe / vc_redist.x64.exe를 모음
    private static void findInstallers(File dir, int depth, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth > 1 && !FileUtils.isSymlink(f)) findInstallers(f, depth - 1, out);
            } else if (f.length() > 0 && (f.getName().equalsIgnoreCase(NAME_X86) || f.getName().equalsIgnoreCase(NAME_X64))) {
                out.add(f);
            }
        }
    }

    private static Set<String> readScanned(File store) {
        Set<String> set = new HashSet<>();
        try {
            String text = FileUtils.readString(new File(store, SCANNED));
            if (text != null) for (String line : text.split("\n")) if (!line.trim().isEmpty()) set.add(line.trim());
        } catch (Exception ignored) {}
        return set;
    }

    private static void writeScanned(File store, Set<String> set) {
        FileUtils.writeString(new File(store, SCANNED), String.join("\n", set));
    }

    /**
     * File version of a PE file from its VS_FIXEDFILEINFO block (signature
     * 0xFEEF04BD), as one number (major.minor in the high half, build.revision
     * in the low half); -1 when not found.
     * PE 파일의 VS_FIXEDFILEINFO 블록(서명 0xFEEF04BD)에서 읽은 파일 버전. 숫자 하나로
     * 돌려줌(위쪽 절반 major.minor, 아래쪽 절반 build.revision). 없으면 -1
     */
    static long fileVersion(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            int len = (int) Math.min(f.length(), 16L * 1024 * 1024);
            byte[] b = new byte[len];
            f.readFully(b);
            // The resource section is near the end, so search from the back
            // 리소스 영역은 끝쪽에 있으므로 뒤에서부터 찾음
            for (int i = len - 16; i >= 0; i--) {
                if ((b[i] & 0xFF) == 0xBD && (b[i + 1] & 0xFF) == 0x04 && (b[i + 2] & 0xFF) == 0xEF && (b[i + 3] & 0xFF) == 0xFE) {
                    long ms = le32(b, i + 8) & 0xFFFFFFFFL;
                    long ls = le32(b, i + 12) & 0xFFFFFFFFL;
                    return (ms << 32) | ls;
                }
            }
        } catch (IOException ignored) {}
        return -1;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static String versionText(long v) {
        if (v == -1) return "none";
        return (v >>> 48) + "." + ((v >>> 32) & 0xFFFF) + "." + ((v >>> 16) & 0xFFFF) + "." + (v & 0xFFFF);
    }

    // Two levels: the cabinets inside the .exe, then the cabinets among their
    // entries. DLLs are picked by name and checked by PE machine type
    // 두 단계임: .exe 안의 캐비닛, 그다음 그 항목 중 캐비닛인 것. DLL은 이름으로 고르고
    // PE 머신 종류로 확인함
    private static void extractRuntime(File exe, File work, File outDir, List<String> dllNames, boolean x64) throws IOException {
        File outer = new File(work, "outer");
        File cand = new File(work, "cand");
        outer.mkdirs();
        cand.mkdirs();

        // Level 1: every entry of every cabinet in the .exe, kept only if it is a cabinet
        // 1단계: .exe 안 모든 캐비닛의 모든 항목을 꺼내고, 캐비닛인 것만 남김
        List<File> innerCabs = new ArrayList<>();
        List<Long> offsets = CabReader.findCabinets(exe);
        Log.i(TAG, exe.getName() + ": cabinets at " + offsets);
        int n = 0;
        for (long off : offsets) {
            try (CabReader cab = new CabReader(exe, off)) {
                final List<File> written = new ArrayList<>();
                final int prefix = n++;
                cab.extract(new CabReader.Sink() {
                    @Override
                    public OutputStream open(CabReader.Entry e) throws IOException {
                        File f = new File(outer, prefix + "_" + safeName(e.name));
                        written.add(f);
                        return new FileOutputStream(f);
                    }

                    @Override
                    public void onFailed(CabReader.Entry e, IOException error) {
                        Log.w(TAG, "outer entry failed: " + e.name + " - " + error.getMessage());
                    }
                });
                for (File f : written) {
                    if (startsWithMscf(f)) innerCabs.add(f);
                    else f.delete();
                }
            } catch (IOException e) {
                Log.w(TAG, "outer cabinet at " + off + " skipped: " + e.getMessage());
            }
        }
        Log.i(TAG, exe.getName() + ": inner cabinets " + innerCabs.size());

        // Level 2: candidates for each wanted DLL. Score 2 = arch suffix matches
        // ("_amd64"/"_x64" or "_x86"), 1 = plain name; other suffixes (e.g. "_arm64") are skipped
        // 2단계: 원하는 DLL마다 후보를 모음. 점수 2 = 아키텍처 꼬리표가 맞음("_amd64"/"_x64"
        // 또는 "_x86"), 1 = 꼬리표 없는 이름. 다른 꼬리표(예: "_arm64")는 건너뜀
        Map<String, List<File>> best = new HashMap<>();
        Map<String, Integer> bestScore = new HashMap<>();
        int c = 0;
        for (File inner : innerCabs) {
            try (CabReader cab = new CabReader(inner, 0)) {
                final Map<CabReader.Entry, File> files = new HashMap<>();
                final Map<CabReader.Entry, String> dllOf = new HashMap<>();
                final Map<CabReader.Entry, Integer> scoreOf = new HashMap<>();
                final int base = c;
                c += cab.getEntries().size();
                cab.extract(new CabReader.Sink() {
                    int i = base;

                    @Override
                    public OutputStream open(CabReader.Entry e) throws IOException {
                        for (String dll : dllNames) {
                            int score = matchScore(e.name, dll, x64);
                            if (score == 0) continue;
                            Integer have = bestScore.get(dll);
                            if (have != null && have > score) return null;
                            File f = new File(cand, (i++) + "_" + dll + ".dll");
                            files.put(e, f);
                            dllOf.put(e, dll);
                            scoreOf.put(e, score);
                            return new FileOutputStream(f);
                        }
                        return null;
                    }

                    @Override
                    public void onFailed(CabReader.Entry e, IOException error) {
                        File f = files.remove(e);
                        if (f != null) f.delete();
                        Log.w(TAG, "inner entry failed: " + e.name + " - " + error.getMessage());
                    }
                });
                for (Map.Entry<CabReader.Entry, File> me : files.entrySet()) {
                    String dll = dllOf.get(me.getKey());
                    int score = scoreOf.get(me.getKey());
                    File f = me.getValue();
                    if (peMachine(f) != (x64 ? MACHINE_AMD64 : MACHINE_I386)) {
                        Log.i(TAG, "skipped (other machine type): " + me.getKey().name);
                        f.delete();
                        continue;
                    }
                    Integer have = bestScore.get(dll);
                    if (have == null || score > have) {
                        List<File> old = best.put(dll, new ArrayList<>());
                        if (old != null) for (File o : old) o.delete();
                        bestScore.put(dll, score);
                    }
                    if (bestScore.get(dll) == score) best.get(dll).add(f);
                    else f.delete();
                }
            } catch (IOException e) {
                Log.w(TAG, "inner cabinet " + inner.getName() + " skipped: " + e.getMessage());
            }
        }

        // Several equal candidates: the largest one (the full build rather than a stub)
        // 같은 점수의 후보가 여럿이면 가장 큰 것(껍데기가 아닌 전체 빌드)을 씀
        for (String dll : dllNames) {
            List<File> list = best.get(dll);
            if (list == null || list.isEmpty()) {
                Log.i(TAG, (x64 ? "x64" : "x86") + ": " + dll + ".dll not in this package");
                continue;
            }
            File pick = list.get(0);
            for (File f : list) if (f.length() > pick.length()) pick = f;
            File dst = new File(outDir, dll + ".dll");
            if (!pick.renameTo(dst) && !FileUtils.copy(pick, dst)) throw new IOException("cannot store " + dst);
            Log.i(TAG, (x64 ? "x64: " : "x86: ") + dst.getName() + " " + dst.length() + " bytes (" + list.size() + " candidate(s))");
        }
    }

    // DLL names of this component, from wincomponents.json
    // 이 항목의 DLL 이름 목록(wincomponents.json에서 읽음)
    private static List<String> dllNames(Context context) throws IOException {
        try {
            JSONArray arr = new JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json")).getJSONArray(ID);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i).toLowerCase(Locale.ENGLISH));
            return list;
        } catch (Exception e) {
            throw new IOException("wincomponents.json: " + e.getMessage());
        }
    }

    // How well a cabinet entry name matches "<dll>.dll" for this arch (0 = no match)
    // 캐비닛 항목 이름이 이 아키텍처의 "<dll>.dll"과 얼마나 맞는지(0 = 안 맞음)
    static int matchScore(String entryName, String dll, boolean x64) {
        String name = entryName.toLowerCase(Locale.ENGLISH);
        int slash = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
        if (slash >= 0) name = name.substring(slash + 1);
        String plain = dll + ".dll";
        if (name.equals(plain)) return 1;
        if (x64 && (name.equals(plain + "_amd64") || name.equals(plain + "_x64"))) return 2;
        if (!x64 && name.equals(plain + "_x86")) return 2;
        return 0;
    }

    // PE "Machine" field of a DLL, or -1 when it is not a PE file
    // DLL의 PE "Machine" 값. PE 파일이 아니면 -1
    static int peMachine(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            if (f.length() < 0x40) return -1;
            byte[] mz = new byte[0x40];
            f.readFully(mz);
            if (mz[0] != 'M' || mz[1] != 'Z') return -1;
            long peOff = (mz[0x3C] & 0xFF) | ((mz[0x3D] & 0xFF) << 8) | ((mz[0x3E] & 0xFF) << 16) | ((long) (mz[0x3F] & 0xFF) << 24);
            if (peOff + 6 > f.length()) return -1;
            byte[] pe = new byte[6];
            f.seek(peOff);
            f.readFully(pe);
            if (pe[0] != 'P' || pe[1] != 'E' || pe[2] != 0 || pe[3] != 0) return -1;
            return (pe[4] & 0xFF) | ((pe[5] & 0xFF) << 8);
        } catch (IOException e) {
            return -1;
        }
    }

    private static boolean startsWithMscf(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            return in.read(b) == 4 && b[0] == 'M' && b[1] == 'S' && b[2] == 'C' && b[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    // Entry names become local file names: keep letters, digits, '.', '_', '-'
    // 항목 이름을 로컬 파일 이름으로 씀: 영문자, 숫자, '.', '_', '-'만 남김
    private static String safeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            sb.append(Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-' ? ch : '_');
        }
        return sb.length() > 0 ? sb.toString() : "entry";
    }


    /**
     * Status of the kept DLLs for the line under the option: "14.44.35211.0 (x64 · x86)",
     * or null when nothing is kept yet. Reads two small files, fine on the UI thread.
     * 항목 아래 줄에 보여 줄 보관 DLL 상태: "14.44.35211.0 (x64 · x86)", 아직 없으면 null.
     * 작은 파일 두 개만 읽으므로 UI 스레드에서 불러도 됨
     */
    public static String keptSummary(Context context) {
        ImageFs imageFs = ImageFs.find(context);
        if (!isInstalled(imageFs)) return null;
        long v64 = fileVersion(new File(storeDir(imageFs), "system32/vcruntime140.dll"));
        long v32 = fileVersion(new File(storeDir(imageFs), "syswow64/vcruntime140.dll"));
        long v = Math.max(v64, v32);
        String arch = (v64 != -1 ? "x64" : "") + (v64 != -1 && v32 != -1 ? " \u00b7 " : "") + (v32 != -1 ? "x86" : "");
        return v == -1 ? "?" : versionText(v) + " (" + arch + ")";
    }

    // ==================== Official download / 공식 주소에서 받기 ====================

    /** Bytes received for one file (total -1 when unknown) / 파일 하나의 받은 크기(전체를 모르면 -1) */
    public interface DownloadProgress {
        void onBytes(String name, long done, long total);
    }

    /**
     * Downloads vc_redist.x64.exe and vc_redist.x86.exe from Microsoft's official
     * addresses into dir. Only a PE file (starts with "MZ") is kept; anything else
     * (an error page, a cut-off file) is deleted. Blocking. Returns null when both
     * were saved, otherwise what went wrong (a file that did arrive is kept).
     * Microsoft 공식 주소에서 vc_redist.x64.exe와 vc_redist.x86.exe를 dir에 받음. PE 파일
     * ("MZ"로 시작)만 남기고, 그 밖의 것(오류 페이지, 끊긴 파일)은 지움. 블로킹임. 둘 다
     * 저장했으면 null, 아니면 무엇이 잘못됐는지 돌려줌(받은 파일은 남겨 둠)
     */
    public static String downloadOfficial(File dir, DownloadProgress progress) {
        StringBuilder errors = new StringBuilder();
        String[][] files = {{URL_X64, NAME_X64}, {URL_X86, NAME_X86}};
        for (String[] f : files) {
            File out = new File(dir, f[1]);
            try {
                download(f[0], out, f[1], progress);
                if (!startsWithMz(out)) {
                    out.delete();
                    throw new IOException("not an installer");
                }
                Log.i(TAG, "downloaded " + f[1] + " (" + out.length() + " bytes)");
            } catch (IOException e) {
                out.delete();
                Log.w(TAG, "download " + f[1] + " failed: " + e.getMessage());
                if (errors.length() > 0) errors.append(", ");
                errors.append(f[1]).append(": ").append(e.getMessage());
            }
        }
        return errors.length() == 0 ? null : errors.toString();
    }

    // HTTPS only; redirects are followed by hand (aka.ms -> Microsoft's download host)
    // HTTPS만 씀. 리디렉션은 직접 따라감(aka.ms -> Microsoft 받기 서버)
    private static void download(String url, File out, String name, DownloadProgress progress) throws IOException {
        java.net.HttpURLConnection conn = null;
        try {
            for (int hop = 0; ; hop++) {
                if (hop > 5) throw new IOException("too many redirects");
                java.net.URL u = new java.net.URL(url);
                if (!"https".equalsIgnoreCase(u.getProtocol())) throw new IOException("not https: " + u.getHost());
                conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (next == null) throw new IOException("HTTP " + code + " without location");
                    url = new java.net.URL(u, next).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                break;
            }
            long total = conn.getContentLengthLong();
            long done = 0, lastReport = 0;
            try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (progress != null && done - lastReport >= 256 * 1024) {
                        lastReport = done;
                        progress.onBytes(name, done, total);
                    }
                }
            }
            if (total > 0 && done != total) throw new IOException("cut off at " + done + " of " + total + " bytes");
            if (progress != null) progress.onBytes(name, done, total);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean startsWithMz(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return in.read() == 'M' && in.read() == 'Z';
        } catch (IOException e) {
            return false;
        }
    }

    // "12.3 / 25.1 MB" for the progress line / 진행 줄용 "12.3 / 25.1 MB"
    private static String sizeText(long done, long total) {
        String d = String.format(Locale.ROOT, "%.1f", done / 1048576.0);
        return total > 0 ? d + " / " + String.format(Locale.ROOT, "%.1f MB", total / 1048576.0) : d + " MB";
    }

    // ==================== UI / 화면 ====================

    /**
     * Called when the component is turned on. Kept already -> done(true) at once.
     * Otherwise shows where the DLLs come from with Microsoft's license terms,
     * then searches the Steam library and the manual folder. The setting stays on
     * either way (done(true)); when nothing is found yet it is applied later, as
     * soon as a Steam download or a file put by hand brings the DLLs. Cancel ->
     * done(false) (the setting goes back).
     * 항목을 켤 때 부름. 이미 보관돼 있으면 바로 done(true). 아니면 DLL을 어디서
     * 가져오는지와 Microsoft 사용 조건을 보여 준 뒤, 스팀 라이브러리와 직접 넣기 폴더를
     * 찾아봄. 어느 쪽이든 설정은 켜진 채로 둠(done(true)). 아직 못 찾았으면 스팀
     * 다운로드나 직접 넣은 파일로 DLL이 생기는 대로 나중에 적용됨. 취소 ->
     * done(false)(설정이 원래대로 돌아감)
     */
    public static void ensureInstalled(Context context, Consumer<Boolean> done) {
        ensureInstalled(context, false, done);
    }

    /**
     * force = true: the license prompt and the download run even when DLLs are
     * already kept (the "Get again" action under the option)
     * force = true: 이미 보관한 DLL이 있어도 사용 조건 창과 받기를 진행함(항목 아래의
     * "다시 받기")
     */
    public static void ensureInstalled(Context context, boolean force, Consumer<Boolean> done) {
        Activity activity = findActivity(context);
        if (activity == null) {
            done.accept(false);
            return;
        }
        if (!force && isInstalled(ImageFs.find(activity))) {
            done.accept(true);
            return;
        }
        if (busy.get()) {
            Toast.makeText(activity, R.string.vcredist_busy, Toast.LENGTH_SHORT).show();
            done.accept(false);
            return;
        }
        final boolean[] answered = {false};
        // The installers are not run, so their license screen never shows: the
        // terms are linked here and the user agrees first
        // 설치 프로그램을 실행하지 않아 그 사용 조건 화면이 나오지 않으므로, 여기서
        // 사용 조건을 링크로 보여 주고 먼저 동의를 받음
        String message = escape(activity.getString(R.string.vcredist_enable_message, manualLabel()))
                + "<br><br><a href=\"" + LICENSE_URL + "\">" + escape(activity.getString(R.string.vcredist_license_link)) + "</a>";
        AlertDialog prompt = new AlertDialog.Builder(activity)
                .setTitle(R.string.vcrun2015)
                .setMessage(Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(R.string.vcredist_agree, (d, w) -> {
                    answered[0] = true;
                    runHarvest(activity, done);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    answered[0] = true;
                    done.accept(false);
                })
                .setOnDismissListener(d -> {
                    // Back / outside tap: same as cancel
                    // 뒤로가기 / 바깥 누름: 취소와 같음
                    if (!answered[0]) {
                        answered[0] = true;
                        done.accept(false);
                    }
                })
                .show();
        // Makes the license link tappable (opens the browser)
        // 사용 조건 링크를 누를 수 있게 함(브라우저로 열림)
        TextView tv = prompt.findViewById(android.R.id.message);
        if (tv != null) tv.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private static void runHarvest(Activity activity, Consumer<Boolean> done) {
        if (!busy.compareAndSet(false, true)) {
            done.accept(false);
            return;
        }
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.vcredist_searching);
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            String error = null;
            int usable = 0;
            try {
                // Make the manual folder so the user can find it later
                // 사용자가 나중에 찾을 수 있게 직접 넣기 폴더를 만들어 둠
                File manual = manualDir();
                if (!manual.isDirectory()) manual.mkdirs();
                // 1) Microsoft's official download first (agreed to above). Kept only
                //    in the app's cache until the DLLs are taken out
                // 1) 먼저 Microsoft 공식 주소에서 받음(위에서 동의함). DLL을 꺼낼 때까지만
                //    앱 캐시에 둠
                File dl = new File(app.getCacheDir(), DOWNLOAD_SUBDIR);
                FileUtils.delete(dl);
                dl.mkdirs();
                String dlError = downloadOfficial(dl, (name, got, total) -> activity.runOnUiThread(() -> {
                    dialog.setFileStatus(activity.getString(R.string.vcredist_downloading, name, sizeText(got, total)));
                    if (total > 0) dialog.setProgress((int) (got * 100 / total));
                }));
                if (dlError != null) error = activity.getString(R.string.vcredist_download_failed, dlError);
                // 2) The downloaded installers, then the ones already on the device
                //    (Steam library, manual folder): used when the download failed
                // 2) 받은 설치 파일, 그리고 기기에 이미 있는 것(스팀 라이브러리, 직접 넣기
                //    폴더): 받기에 실패했을 때 쓰임
                List<File> roots = steamRoots(app);
                roots.add(0, dl);
                try {
                    usable = harvest(app, roots, text -> activity.runOnUiThread(
                            () -> dialog.setFileStatus(activity.getString(R.string.vcredist_reading, text))));
                } finally {
                    FileUtils.delete(dl);
                }
            } catch (Exception e) {
                Log.w(TAG, "harvest failed", e);
                error = (error != null ? error + "\n" : "") + e.getMessage();
            } finally {
                busy.set(false);
            }
            final boolean ok = isInstalled(ImageFs.find(app));
            final String why = error;
            final int found = usable;
            activity.runOnUiThread(() -> {
                dialog.close();
                if (ok) {
                    Toast.makeText(activity, R.string.vcredist_done, Toast.LENGTH_SHORT).show();
                } else {
                    // Nothing usable yet: say where it will come from; the setting stays on
                    // 아직 쓸 것이 없음: 어디서 생기는지 안내함. 설정은 켜진 채로 둠
                    String text = activity.getString(R.string.vcredist_not_found, manualLabel());
                    if (why != null) text += "\n\n" + activity.getString(R.string.vcredist_details, why);
                    else if (found == 0) Log.i(TAG, "no usable installer yet");
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.vcrun2015)
                            .setMessage(text)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                done.accept(true);
            });
        }, "VCRedistHarvest").start();
    }

    /**
     * After a container is imported: when it has the component on but this
     * device has not kept the DLLs yet, says where they will come from (the
     * export left them out). The setting stays on.
     * 컨테이너를 가져온 뒤 부름: 항목이 켜져 있는데 이 기기에 아직 보관한 DLL이 없으면
     * 어디서 생기는지 안내함(내보낼 때 뺐으므로). 설정은 켜진 채로 둠
     */
    public static void notifyIfMissingAfterImport(Activity activity, String wincomponents) {
        if (activity == null || wincomponents == null) return;
        if (!"1".equals(new KeyValueSet(wincomponents).get(ID, "0"))) return;
        if (isInstalled(ImageFs.find(activity))) return;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.vcrun2015)
                .setMessage(activity.getString(R.string.vcredist_not_found, manualLabel()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // "Download/.EZlator/VCRedist" for messages
    // 안내 문구용 "Download/.EZlator/VCRedist"
    private static String manualLabel() {
        return "Download/" + AppDirs.DOWNLOAD_DIR + "/" + MANUAL_SUBDIR;
    }

    // Escapes text placed into the HTML message
    // HTML 메시지에 넣는 글자를 이스케이프함
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
