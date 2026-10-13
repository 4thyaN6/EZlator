package com.winlator.cmod.container;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.steam.SteamAccountStore;
import com.winlator.cmod.steam.SteamGameInstaller;
import com.winlator.cmod.steam.SteamSession;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes save archives in the layouts of SaveTransfer, from and into
 * the shared save folder (SharedSaves). Blocking: call off the UI thread.
 * Export:  plan...() lists the files and the total size first (shown to the
 *          user before anything is written), write() packs the plan into a zip.
 * Import:  importArchive() backs the shared save folder up first
 *          ("before-import"), then recognises the archive: GameNative zip
 *          (manifest.json), drive_c zip (GameHub / Winlator family), Winlator
 *          family Saves .tar.xz (save info JSON + folder) and EZlator's own .tzst
 *          (per-game export / backup: shared folder layout).
 * Visual C++ DLLs and game files are never part of an export: only the save
 * locations (and, for GameNative, the game folder paths the game itself
 * declares as save locations) are read.
 * SaveTransfer 형식의 세이브 묶음을 공용 세이브 폴더(SharedSaves)에서 만들고 그곳으로
 * 가져옴. 블로킹이라 UI 스레드 밖에서 불러야 함.
 * 내보내기: plan...()이 파일 목록과 전체 크기를 먼저 만들고(쓰기 전에 사용자에게
 *          보여 줌), write()가 그 목록을 zip으로 묶음.
 * 가져오기: importArchive()가 먼저 공용 세이브 폴더를 백업하고("before-import"),
 *          묶음 종류를 알아봄: GameNative zip(manifest.json), drive_c zip(GameHub /
 *          Winlator 계열), Winlator 계열 Saves .tar.xz(세이브 정보 JSON + 폴더),
 *          EZlator 자체 .tzst(게임별 추출 / 백업: 공용 폴더 구조).
 * Visual C++ DLL과 게임 파일은 내보내기에 들어가지 않음: 세이브 위치(GameNative는 게임
 * 스스로 세이브 위치로 선언한 게임 폴더 경로 포함)만 읽음
 */
public final class SaveTransferIO {
    private static final String TAG = "SaveTransferIO";
    private static final String USER = ImageFs.USER;

    private SaveTransferIO() {}

    // ==================== Export / 내보내기 ====================

    /** Files to pack: entry name -> file, plus the total size / 묶을 파일(항목 이름 -> 파일)과 전체 크기 */
    public static final class Plan {
        public final Map<String, File> files = new LinkedHashMap<>();
        public long bytes;
        // GameNative only: manifest.json text / GameNative 전용: manifest.json 내용
        public String manifest;
        public String name;

        void add(String entry, File f) {
            if (files.containsKey(entry)) return;
            files.put(entry, f);
            bytes += f.length();
        }
    }

    /**
     * Every save location of the shared save folder (the 7 locations of
     * SharedSaves, Steam userdata included), in a drive_c layout. Wine and
     * driver caches are left out.
     * 공용 세이브 폴더의 모든 세이브 위치(SharedSaves의 7곳, 스팀 userdata 포함)를
     * drive_c 형식으로 묶음. Wine·드라이버 캐시는 뺌
     */
    public static Plan planAll(Context context, SaveTransfer.Format format) {
        Plan plan = new Plan();
        File root = SharedSaves.sharedRoot(context);
        for (String entry : SharedSaves.entries()) walkShared(new File(root, entry), entry, format, plan, 0);
        return plan;
    }

    /**
     * One game's save folders (SharedSaves.gameUnits) in a drive_c layout.
     * null when the game has no recorded runs.
     * 게임 하나의 세이브 폴더(SharedSaves.gameUnits)를 drive_c 형식으로 묶음.
     * 실행 기록이 없으면 null
     */
    public static Plan planGame(Context context, Shortcut shortcut, SaveTransfer.Format format) {
        java.util.Set<String> units = SharedSaves.gameUnits(context, shortcut);
        if (units == null) return null;
        Plan plan = new Plan();
        File root = SharedSaves.sharedRoot(context);
        for (String unit : units) {
            File f = new File(root, unit);
            if (f.isFile()) {
                if (!FileUtils.isSymlink(f)) plan.add(SaveTransfer.toArchiveEntry(format, unit, USER), f);
            } else {
                walkShared(f, unit, format, plan, 0);
            }
        }
        return plan;
    }

    private static void walkShared(File dir, String rel, SaveTransfer.Format format, Plan plan, int depth) {
        if (depth > 24 || SharedSaves.isNoise(rel)) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String r = rel + "/" + child.getName();
            if (FileUtils.isSymlink(child) || SharedSaves.isNoise(r)) continue;
            if (child.isDirectory()) walkShared(child, r, format, plan, depth + 1);
            else if (child.isFile()) plan.add(SaveTransfer.toArchiveEntry(format, r, USER), child);
        }
    }

    /**
     * One Steam game in GameNative's layout. The save locations come from the
     * game's Steam app info (Steam Cloud save patterns), the same source
     * GameNative uses, so its import finds every root again. Steam userdata
     * is always added. null when the game declares no save locations and has
     * no userdata files.
     * 스팀 게임 하나를 GameNative 형식으로 묶음. 세이브 위치는 게임의 스팀 앱 정보
     * (스팀 클라우드 세이브 패턴)에서 가져옴. GameNative가 쓰는 출처와 같아서 그쪽
     * 가져오기가 모든 루트를 다시 찾음. 스팀 userdata는 항상 넣음. 게임이 세이브 위치를
     * 선언하지 않았고 userdata 파일도 없으면 null
     */
    public static Plan planGameNative(Context context, SteamSession session, Shortcut shortcut, int appId) throws Exception {
        long steamId = new SteamAccountStore(context).getSteamId64();
        File shared = SharedSaves.sharedRoot(context);
        File driveC = new File(shortcut.container.getRootDir(), ".wine/drive_c");
        Plan plan = new Plan();
        plan.name = shortcut.name;
        Map<String, File> roots = new LinkedHashMap<>();

        for (SteamSession.SavePattern p : session.getSavePatterns(appId)) {
            String type = p.root.toLowerCase(Locale.ENGLISH);
            if (type.equals(SaveTransfer.ROOT_STEAM_USERDATA)) continue;
            String sub = SaveTransfer.substitute(p.path, steamId);
            if (!SaveTransfer.isSafe(sub)) continue;
            File base;
            if (type.equals(SaveTransfer.ROOT_GAME_INSTALL)) {
                File game = gameInstallDir(context, appId);
                if (game == null) continue;
                base = resolveIgnoreCase(game, sub);
            } else {
                String folder = SaveTransfer.rootFolder(type, USER);
                if (folder == null) continue;   // Linux / macOS roots / 리눅스·맥 루트
                String rel = sub.isEmpty() ? folder : folder + "/" + sub;
                // Shared locations live in the shared folder; ProgramData in the container
                // 공유 위치는 공용 폴더에, ProgramData는 컨테이너에 있음
                File top = SaveTransfer.isShared(rel, SharedSaves.entries()) ? shared : driveC;
                base = resolveIgnoreCase(top, rel);
            }
            String rootId = SaveTransfer.rootId(p.root, p.path);
            int depth = p.recursive != 0 ? 8 : 0;
            int before = plan.files.size();
            addPattern(base, "", p.pattern, depth, SaveTransfer.GAMENATIVE_FILES + rootId + "/", plan);
            if (plan.files.size() > before) roots.put(rootId, base);
        }
        File userData = resolveIgnoreCase(shared, SaveTransfer.steamUserDataFolder(steamId, appId));
        int before = plan.files.size();
        addPattern(userData, "", "*", 8, SaveTransfer.GAMENATIVE_FILES + SaveTransfer.ROOT_STEAM_USERDATA + "/", plan);
        if (plan.files.size() > before) roots.put(SaveTransfer.ROOT_STEAM_USERDATA, userData);
        if (roots.isEmpty()) return null;

        JSONObject m = new JSONObject();
        m.put("version", SaveTransfer.GAMENATIVE_VERSION);
        m.put("steamAppId", appId);
        m.put("gameName", shortcut.name);
        m.put("exportedAt", System.currentTimeMillis());
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, File> e : roots.entrySet()) {
            arr.put(new JSONObject().put("rootId", e.getKey()).put("path", e.getValue().getAbsolutePath()));
        }
        m.put("roots", arr);
        plan.manifest = m.toString(2);
        return plan;
    }

    // Files under base matching pattern; depth 0 = only base itself
    // base 아래에서 pattern에 맞는 파일. depth 0 = base 바로 아래만
    private static void addPattern(File base, String rel, String pattern, int depth, String prefix, Plan plan) {
        File[] children = base.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (FileUtils.isSymlink(child)) continue;
            String r = rel.isEmpty() ? child.getName() : rel + "/" + child.getName();
            if (child.isDirectory()) {
                if (depth > 0) addPattern(child, r, pattern, depth - 1, prefix, plan);
            } else if (child.isFile() && SaveTransfer.matchesPattern(child.getName(), pattern)) {
                plan.add(prefix + r, child);
            }
        }
    }

    // <library>/common/<installdir> of an installed Steam game, or null
    // 설치된 스팀 게임의 <라이브러리>/common/<installdir>, 없으면 null
    private static File gameInstallDir(Context context, int appId) {
        File lib = SteamGameInstaller.libraryOf(context, appId);
        if (lib == null) return null;
        String dir = SteamGameInstaller.readAcfValue(new File(lib, "appmanifest_" + appId + ".acf"), "installdir");
        if (dir == null || dir.isEmpty() || !SaveTransfer.isSafe(dir)) return null;
        File f = new File(lib, "common/" + dir);
        return f.isDirectory() ? f : null;
    }

    /** Packs a plan into out (zip). Returns the number of files / 목록을 out(zip)으로 묶음. 파일 수를 돌려줌 */
    public static int write(Plan plan, File out) throws IOException {
        int count = 0;
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("no folder " + parent);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            if (plan.manifest != null) {
                zip.putNextEntry(new ZipEntry(SaveTransfer.GAMENATIVE_MANIFEST));
                zip.write(plan.manifest.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            byte[] buf = new byte[65536];
            for (Map.Entry<String, File> e : plan.files.entrySet()) {
                File f = e.getValue();
                if (!f.isFile()) continue;
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(f.lastModified());
                zip.putNextEntry(ze);
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
                count++;
            }
        }
        Log.i(TAG, "exported " + count + " files (" + SaveTransfer.sizeText(plan.bytes) + ") to " + out);
        return count;
    }

    // ==================== Import / 가져오기 ====================

    /** Import result / 가져오기 결과 */
    public static final class ImportResult {
        public int written, skipped;
        public String kind = "";
        public File backup;
    }

    public static ImportResult importArchive(Context context, Uri uri) throws Exception {
        ImportResult result = new ImportResult();
        byte[] head = new byte[8];
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open");
            int n = in.read(head);
            if (n < 4) throw new IOException("file too small");
        }
        SaveTransfer.Kind kind = SaveTransfer.kindOf(head);
        if (kind == SaveTransfer.Kind.UNKNOWN) throw new IOException("unknown archive");
        // The current saves first, so a wrong import can be undone (Settings > Restore saves)
        // 먼저 현재 세이브를 백업함. 잘못 가져와도 되돌릴 수 있음(설정 > 세이브 복원)
        result.backup = SharedSaves.backup(context, "before-import");

        switch (kind) {
            case ZIP: importZip(context, uri, result); break;
            case XZ: importWinlatorTarXz(context, uri, result); break;
            case ZSTD: importSharedTzst(context, uri, result); break;
            default: break;
        }
        Log.i(TAG, "imported " + result.kind + ": written " + result.written + ", skipped " + result.skipped);
        return result;
    }

    private static void importZip(Context context, Uri uri, ImportResult result) throws Exception {
        // Pass 1: a GameNative manifest? / 1차: GameNative 매니페스트가 있는가
        JSONObject manifest = null;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(context.getContentResolver().openInputStream(uri)))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().equals(SaveTransfer.GAMENATIVE_MANIFEST)) {
                    manifest = new JSONObject(new String(readAll(zip), StandardCharsets.UTF_8));
                    break;
                }
            }
        }
        if (manifest != null) importGameNative(context, uri, manifest, result);
        else importDriveC(context, uri, result);
    }

    // GameHub / Winlator family: drive_c entries into the shared save folder
    // GameHub / Winlator 계열: drive_c 항목을 공용 세이브 폴더로
    private static void importDriveC(Context context, Uri uri, ImportResult result) throws Exception {
        result.kind = "drive_c zip";
        File shared = SharedSaves.sharedRoot(context);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(context.getContentResolver().openInputStream(uri)))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String rel = SaveTransfer.fromArchiveEntry(e.getName(), USER);
                String spelled = rel != null ? SaveTransfer.sharedSpelling(rel, SharedSaves.entries()) : null;
                // Only save locations are written; anything else in drive_c is skipped
                // 세이브 위치만 씀. drive_c의 그 밖의 것은 건너뜀
                if (spelled == null) { result.skipped++; continue; }
                if (writeEntry(zip, shared, spelled, e.getTime())) result.written++;
                else result.skipped++;
            }
        }
    }

    private static void importGameNative(Context context, Uri uri, JSONObject manifest, ImportResult result) throws Exception {
        result.kind = "GameNative";
        int appId = manifest.optInt("steamAppId", 0);
        long steamId = new SteamAccountStore(context).getSteamId64();
        File shared = SharedSaves.sharedRoot(context);
        Shortcut shortcut = findSteamShortcut(context, appId);
        File driveC = shortcut != null ? new File(shortcut.container.getRootDir(), ".wine/drive_c") : null;
        File game = gameInstallDir(context, appId);

        // rootId -> (top folder, relative folder) / rootId -> (최상위 폴더, 상대 폴더)
        Map<String, File> targets = new HashMap<>();
        JSONArray roots = manifest.optJSONArray("roots");
        if (roots != null) {
            for (int i = 0; i < roots.length(); i++) {
                JSONObject r = roots.getJSONObject(i);
                String id = r.optString("rootId");
                String folder = SaveTransfer.importFolderFromPath(r.optString("path", null), id, USER, steamId, appId);
                if (folder == null) folder = SaveTransfer.importFolderFromRootId(id, USER, steamId, appId);
                File target = targetFor(folder, shared, driveC, game);
                if (target != null) targets.put(id, target);
            }
        }
        List<String> ids = new ArrayList<>(targets.keySet());
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(context.getContentResolver().openInputStream(uri)))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || !name.startsWith(SaveTransfer.GAMENATIVE_FILES)) continue;
                String rel = name.substring(SaveTransfer.GAMENATIVE_FILES.length());
                String id = SaveTransfer.matchRoot(rel, ids);
                if (id == null) { result.skipped++; continue; }
                String inside = SaveTransfer.normalize(rel.substring(id.length() + 1));
                if (inside.isEmpty() || !SaveTransfer.isSafe(inside)) { result.skipped++; continue; }
                if (writeEntry(zip, targets.get(id), inside, e.getTime())) result.written++;
                else result.skipped++;
            }
        }
    }

    // Real folder for an import folder ("gameinstall:<install folder>/<rest>",
    // "gamepath:<rest>", shared, or other drive_c)
    // 가져올 폴더의 실제 위치("gameinstall:<설치 폴더>/<나머지>", "gamepath:<나머지>",
    // 공유 위치, 또는 그 밖의 drive_c)
    private static File targetFor(String folder, File shared, File driveC, File game) {
        if (folder == null) return null;
        if (folder.startsWith("gamepath:")) {
            return game != null ? resolveIgnoreCase(game, folder.substring("gamepath:".length())) : null;
        }
        if (folder.startsWith("gameinstall:")) {
            if (game == null) return null;
            String rest = folder.substring("gameinstall:".length());
            // The exporting device's install folder name comes first in the path
            // 경로 맨 앞은 내보낸 기기의 설치 폴더 이름임
            int slash = rest.indexOf('/');
            return resolveIgnoreCase(game, slash > 0 ? rest.substring(slash + 1) : "");
        }
        String spelled = SaveTransfer.sharedSpelling(folder, SharedSaves.entries());
        if (spelled != null) return resolveIgnoreCase(shared, spelled);
        return driveC != null ? resolveIgnoreCase(driveC, folder) : null;
    }

    // Winlator family Saves: .tar.xz with one folder = save info JSON (Title, Path,
    // ContainerID) + the save folder named like the last part of Path
    // Winlator 계열 Saves: .tar.xz 안에 폴더 하나 = 세이브 정보 JSON(Title, Path,
    // ContainerID) + Path 마지막 이름과 같은 세이브 폴더
    private static void importWinlatorTarXz(Context context, Uri uri, ImportResult result) throws Exception {
        result.kind = "Winlator tar.xz";
        File temp = new File(context.getCacheDir(), "save_import_xz");
        FileUtils.delete(temp);
        if (!temp.mkdirs()) throw new IOException("no temp folder");
        try {
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, temp)) throw new IOException("xz extract failed");
            File[] dirs = temp.listFiles(File::isDirectory);
            if (dirs == null || dirs.length != 1) throw new IOException("unexpected layout");
            File[] jsons = dirs[0].listFiles((d, n) -> n.endsWith(".json"));
            if (jsons == null || jsons.length != 1) throw new IOException("save info not found");
            JSONObject info = new JSONObject(FileUtils.readString(jsons[0]));
            String path = info.optString("Path").replace('\\', '/');
            int dc = path.indexOf("drive_c/");
            if (dc < 0) throw new IOException("save path without drive_c");
            String rel = SaveTransfer.renameUser(SaveTransfer.normalize(path.substring(dc + 8)), USER);
            File src = new File(dirs[0], new File(path).getName());
            if (!src.exists() || !SaveTransfer.isSafe(rel)) throw new IOException("save folder not found");
            String spelled = SaveTransfer.sharedSpelling(rel, SharedSaves.entries());
            File dst;
            if (spelled != null) {
                dst = resolveIgnoreCase(SharedSaves.sharedRoot(context), spelled);
            } else {
                Container c = new ContainerManager(context).getContainerById(info.optInt("ContainerID", -1));
                if (c == null) throw new IOException("container not found for " + rel);
                dst = resolveIgnoreCase(new File(c.getRootDir(), ".wine/drive_c"), rel);
            }
            result.written += copyTree(src, dst);
        } finally {
            FileUtils.delete(temp);
        }
    }

    // EZlator .tzst (per-game export of earlier builds / backup): shared folder layout
    // EZlator .tzst(예전 빌드의 게임별 추출 / 백업): 공용 폴더 구조
    private static void importSharedTzst(Context context, Uri uri, ImportResult result) throws Exception {
        result.kind = "EZlator tzst";
        File temp = new File(context.getCacheDir(), "save_import_tzst");
        FileUtils.delete(temp);
        if (!temp.mkdirs()) throw new IOException("no temp folder");
        try {
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, uri, temp)) throw new IOException("tzst extract failed");
            // A backup holds the "ezlator-saves" folder itself; an export holds its contents
            // 백업은 "ezlator-saves" 폴더 자체를, 추출본은 그 안의 내용을 담음
            File base = new File(temp, SharedSaves.SHARED_DIR).isDirectory() ? new File(temp, SharedSaves.SHARED_DIR) : temp;
            File shared = SharedSaves.sharedRoot(context);
            for (String entry : SharedSaves.entries()) {
                File src = new File(base, entry);
                if (src.isDirectory()) result.written += copyTree(src, new File(shared, entry));
            }
        } finally {
            FileUtils.delete(temp);
        }
    }

    // ==================== Helpers / 보조 ====================

    // Writes the current zip entry to <top>/<rel>; refuses links and paths that leave top
    // 지금 zip 항목을 <top>/<rel>에 씀. 링크와 top 밖으로 나가는 경로는 거부함
    private static boolean writeEntry(ZipInputStream zip, File top, String rel, long time) throws IOException {
        if (top == null) return false;
        File dst = resolveIgnoreCase(top, rel);
        String topPath = top.getCanonicalPath();
        File parent = dst.getParentFile();
        if (parent == null) return false;
        if (!parent.isDirectory() && !parent.mkdirs()) return false;
        if (!parent.getCanonicalPath().startsWith(topPath)) {
            Log.w(TAG, "entry leaves its folder, skipped: " + rel);
            return false;
        }
        if (Files.isSymbolicLink(dst.toPath())) return false;
        try (OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (time > 0) //noinspection ResultOfMethodCallIgnored
            dst.setLastModified(time);
        return true;
    }

    // Copies src into dst (files overwrite), returns the number of files
    // src를 dst로 복사함(파일은 덮어씀). 복사한 파일 수를 돌려줌
    private static int copyTree(File src, File dst) {
        if (FileUtils.isSymlink(src)) return 0;
        if (src.isFile()) {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            return FileUtils.copy(src, dst) ? 1 : 0;
        }
        int n = 0;
        File[] children = src.listFiles();
        if (children == null) return 0;
        dst.mkdirs();
        for (File c : children) n += copyTree(c, new File(dst, c.getName()));
        return n;
    }

    /**
     * top/rel where every existing part is matched ignoring case (Windows paths
     * are not case-sensitive, the Android storage is); missing parts keep the
     * given spelling.
     * top/rel을 만듦. 이미 있는 부분은 대소문자를 무시하고 맞춤(Windows 경로는 대소문자를
     * 가리지 않지만 안드로이드 저장소는 가림). 없는 부분은 주어진 철자를 씀
     */
    static File resolveIgnoreCase(File top, String rel) {
        File cur = top;
        for (String part : SaveTransfer.normalize(rel).split("/")) {
            if (part.isEmpty()) continue;
            File exact = new File(cur, part);
            if (!exact.exists()) {
                String[] names = cur.list();
                if (names != null) {
                    for (String n : names) {
                        if (n.equalsIgnoreCase(part)) { exact = new File(cur, n); break; }
                    }
                }
            }
            cur = exact;
        }
        return cur;
    }

    // Steam shortcut with this AppID (its container is used for ProgramData), or null
    // 이 AppID의 스팀 바로가기(ProgramData에 그 컨테이너를 씀). 없으면 null
    public static Shortcut findSteamShortcut(Context context, int appId) {
        if (appId <= 0) return null;
        for (Shortcut s : new ContainerManager(context).loadShortcuts()) {
            if (String.valueOf(appId).equals(s.getExtra("steamAppId"))) return s;
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
