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
 * shipped in the APK and the app does not download them. They are taken out of
 * Microsoft's own installers (vc_redist.x86.exe / vc_redist.x64.exe) that are
 * already on the device:
 *   1. installers that come with Steam games (steamapps/common, e.g. the game's
 *      _CommonRedist folder or "Steamworks Shared")
 *   2. installers the user put in Download/.EZlator/VCRedist
 * The installers are not run: the DLLs are read from the cabinets inside them
 * (no Windows code runs). The newest version of each DLL is kept in imagefs, and
 * turning the component on copies them into the prefix at every launch that needs it.
 * Visual C++ 2015~2022 런타임(Win Components "vcrun2015")임. DLL은 APK에 넣지 않고
 * 앱이 받지도 않음. 기기에 이미 있는 Microsoft 설치 파일(vc_redist.x86.exe /
 * vc_redist.x64.exe)에서 꺼냄:
 *   1. 스팀 게임과 함께 받은 설치 파일(steamapps/common, 예: 게임의 _CommonRedist
 *      폴더나 "Steamworks Shared")
 *   2. 사용자가 Download/.EZlator/VCRedist에 넣은 설치 파일
 * 설치 프로그램은 실행하지 않음: 그 안의 캐비닛에서 DLL을 읽어 냄(Windows 코드는
 * 실행하지 않음). DLL마다 가장 새 버전을 imagefs에 보관하고, 항목을 켜면 필요할 때마다
 * 실행 시 프리픽스에 복사함
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

    /**
     * Copies the kept DLLs into windows/system32 (64-bit) and windows/syswow64
     * (32-bit) and marks the prefix. false when nothing is kept yet.
     * 보관한 DLL을 windows/system32(64비트)와 windows/syswow64(32비트)에 복사하고
     * 프리픽스에 표시를 남김. 아직 보관한 것이 없으면 false
     */
    public static boolean applyTo(ImageFs imageFs, File windowsDir) {
        if (!isInstalled(imageFs)) return false;
        File store = storeDir(imageFs);
        int copied = 0;
        for (String sub : new String[]{"system32", "syswow64"}) {
            File[] dlls = new File(store, sub).listFiles();
            File dst = new File(windowsDir, sub);
            if (dlls == null || !dst.isDirectory()) continue;
            for (File dll : dlls) {
                if (FileUtils.copy(dll, new File(dst, dll.getName()))) copied++;
            }
        }
        FileUtils.writeString(new File(windowsDir, "system32/" + APPLIED_MARK), readStamp(imageFs));
        Log.i(TAG, "applied " + copied + " DLLs to " + windowsDir);
        return true;
    }

    // True when this prefix already has the current contents of the store
    // 이 프리픽스에 보관소의 지금 내용이 이미 적용돼 있으면 true
    public static boolean isAppliedTo(ImageFs imageFs, File windowsDir) {
        if (!isInstalled(imageFs)) return true; // nothing to apply / 적용할 것이 없음
        try {
            String applied = FileUtils.readString(new File(windowsDir, "system32/" + APPLIED_MARK)).trim();
            return applied.equals(readStamp(imageFs));
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearAppliedMark(File windowsDir) {
        new File(windowsDir, "system32/" + APPLIED_MARK).delete();
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
        roots.add(new File(SteamGameInstaller.getLibraryDir(context), "common"));
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
        Activity activity = findActivity(context);
        if (activity == null) {
            done.accept(false);
            return;
        }
        if (isInstalled(ImageFs.find(activity))) {
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
                usable = harvest(app, steamRoots(app), text -> activity.runOnUiThread(
                        () -> dialog.setFileStatus(activity.getString(R.string.vcredist_reading, text))));
            } catch (Exception e) {
                Log.w(TAG, "harvest failed", e);
                error = String.valueOf(e.getMessage());
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
