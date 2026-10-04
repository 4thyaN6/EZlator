package com.winlator.cmod.container;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.core.AppDirs;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

// Shared save folders. The Windows user folders that games write saves into are
// kept once, in <imagefs>/home/ezlator-saves, and every container links to them,
// so moving a game to another layer keeps its saves. Only one container runs at a
// time, so there is no concurrent writing.
// 공용 세이브 폴더. 게임이 세이브를 쓰는 Windows 사용자 폴더를
// <imagefs>/home/ezlator-saves 한 곳에 두고 모든 컨테이너가 그곳을 링크로 가리킴.
// 그래서 게임을 다른 레이어로 옮겨도 세이브가 그대로 따라감. 한 번에 컨테이너
// 하나만 실행되므로 동시에 쓰는 일은 없음
public final class SharedSaves {
    private static final String TAG = "SharedSaves";

    // Folder names under <imagefs>/home
    // <imagefs>/home 아래의 폴더 이름
    public static final String SHARED_DIR = "ezlator-saves";
    private static final String CONFLICT_DIR = "ezlator-saves-conflicts";
    // Conflict folders (one per <time>) kept; older ones are deleted, see pruneConflicts()
    // 남겨 두는 충돌 폴더 수(<시각>마다 하나). 더 오래된 것은 지움. pruneConflicts() 참고
    private static final int KEEP_CONFLICTS = 10;

    // Folders shared, relative to C:\ (drive_c). The usual Windows save places:
    // the user's AppData (Roaming / Local / LocalLow), Documents (incl. My Games),
    // Saved Games, the Public Documents folder some games write for every user,
    // and Steam's userdata (Steam Cloud saves of games run through Steam)
    // 공유하는 폴더. C:\(drive_c) 기준 상대 경로. Windows의 대표 세이브 위치임:
    // 사용자 AppData(Roaming / Local / LocalLow), Documents(My Games 포함),
    // Saved Games, 일부 게임이 모든 사용자용으로 쓰는 공용 문서 폴더, 스팀
    // userdata(스팀으로 실행한 게임의 스팀 클라우드 세이브)
    private static final String[] ENTRIES = {
            "users/" + ImageFs.USER + "/AppData/Roaming",
            "users/" + ImageFs.USER + "/AppData/Local",
            "users/" + ImageFs.USER + "/AppData/LocalLow",
            "users/" + ImageFs.USER + "/Documents",
            "users/" + ImageFs.USER + "/Saved Games",
            "users/Public/Documents",
            "Program Files (x86)/Steam/userdata"
    };

    private SharedSaves() {}

    public static File sharedRoot(Context context) {
        return new File(ImageFs.find(context).getRootDir(), "home/" + SHARED_DIR);
    }

    // Makes every shared folder of the container a link into the shared root.
    // A real folder is merged into the shared one first: files that exist only on
    // one side are simply moved (a rename on the same storage, so it is instant);
    // when both sides have the same file the newer one is kept in the shared
    // folder and the other is moved to ezlator-saves-conflicts/<time>/<container>.
    // Two files with the same content are not a conflict: the container copy is
    // simply deleted. Only the newest KEEP_CONFLICTS conflict folders are kept.
    // Links are relative so they resolve the same way however the folder is
    // reached. A folder that is already a link to somewhere else is left alone.
    // Safe to call on every launch: already linked folders are skipped.
    // 컨테이너의 공유 폴더를 모두 공용 폴더를 가리키는 링크로 만듦. 진짜 폴더면
    // 먼저 공용 폴더로 합침: 한쪽에만 있는 파일은 그냥 옮김(같은 저장소 안의 이름
    // 변경이라 즉시 끝남). 양쪽에 같은 파일이 있으면 더 최근 것을 공용 폴더에 두고
    // 다른 쪽은 ezlator-saves-conflicts/<시각>/<컨테이너>로 옮김. 내용이 같은 두
    // 파일은 충돌이 아님: 컨테이너 쪽 사본을 그냥 지움. 충돌 폴더는 최근
    // KEEP_CONFLICTS개만 남김. 링크는 상대 경로라
    // 어느 경로로 접근해도 똑같이 풀림. 이미 다른 곳을 가리키는 링크인 폴더는
    // 건드리지 않음. 실행할 때마다 불러도 안전함: 이미 연결된 폴더는 건너뜀
    public static void link(Context context, Container container) {
        File userDir = new File(container.getRootDir(), ".wine/drive_c");
        if (!new File(userDir, "users/" + ImageFs.USER).isDirectory()) return;
        File root = sharedRoot(context);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH).format(new Date());
        File conflictDir = new File(root.getParentFile(), CONFLICT_DIR + "/" + stamp + "/" + container.id);

        for (String entry : ENTRIES) {
            File local = new File(userDir, entry);
            File shared = new File(root, entry);
            try {
                shared.mkdirs();
                if (FileUtils.isSymlink(local)) continue;
                if (local.isDirectory()) {
                    merge(local, shared, new File(conflictDir, entry));
                    if (!FileUtils.delete(local)) {
                        Log.w(TAG, "could not clear " + local + ", left unlinked");
                        continue;
                    }
                } else if (local.exists()) {
                    continue;
                }
                local.getParentFile().mkdirs();
                String target = local.getParentFile().getAbsoluteFile().toPath()
                        .relativize(shared.getAbsoluteFile().toPath()).toString();
                FileUtils.symlink(target, local.getAbsolutePath());
                // FileUtils.symlink() swallows errors, so the result is checked here
                // FileUtils.symlink()는 오류를 삼키므로 결과를 여기서 확인함
                if (FileUtils.isSymlink(local)) Log.i(TAG, "linked " + container.getName() + ": " + entry + " -> " + target);
                else Log.e(TAG, "link NOT created for " + container.getName() + ": " + entry);
            } catch (Exception e) {
                Log.e(TAG, "link failed: " + entry, e);
            }
        }
        pruneConflicts(new File(root.getParentFile(), CONFLICT_DIR));
    }

    // Moves the contents of src into dst (see link() for the conflict rule)
    // src의 내용을 dst로 옮김(충돌 규칙은 link() 참고)
    private static void merge(File src, File dst, File conflictDir) {
        File[] children = src.listFiles();
        if (children == null) return;
        dst.mkdirs();
        for (File child : children) {
            File target = new File(dst, child.getName());
            if (!target.exists() && !FileUtils.isSymlink(target)) {
                if (!child.renameTo(target)) Log.w(TAG, "move failed: " + child);
            } else if (child.isDirectory() && !FileUtils.isSymlink(child) && target.isDirectory()) {
                merge(child, target, new File(conflictDir, child.getName()));
            } else if (sameContent(child, target)) {
                // Same file on both sides: nothing to keep, the container copy goes
                // 양쪽이 같은 파일임: 남길 것이 없으므로 컨테이너 쪽 사본을 지움
                if (!child.delete()) Log.w(TAG, "could not delete duplicate: " + child);
            } else {
                conflictDir.mkdirs();
                if (child.lastModified() > target.lastModified()) {
                    target.renameTo(new File(conflictDir, target.getName()));
                    child.renameTo(target);
                } else {
                    child.renameTo(new File(conflictDir, child.getName()));
                }
            }
        }
    }

    // True when both are regular files (not links) with the same bytes. Saves are
    // small, so the files are read in full in 64 KB blocks
    // 둘 다 일반 파일(링크 아님)이고 내용이 바이트 단위로 같으면 true. 세이브는
    // 작으므로 64KB 단위로 끝까지 읽어 비교함
    private static boolean sameContent(File a, File b) {
        if (FileUtils.isSymlink(a) || FileUtils.isSymlink(b)) return false;
        if (!a.isFile() || !b.isFile() || a.length() != b.length()) return false;
        try (java.io.InputStream ia = new java.io.BufferedInputStream(new java.io.FileInputStream(a));
             java.io.InputStream ib = new java.io.BufferedInputStream(new java.io.FileInputStream(b))) {
            byte[] ba = new byte[65536], bb = new byte[65536];
            while (true) {
                int na = readBlock(ia, ba);
                int nb = readBlock(ib, bb);
                if (na != nb) return false;
                if (na == 0) return true;
                for (int i = 0; i < na; i++) if (ba[i] != bb[i]) return false;
            }
        } catch (java.io.IOException e) {
            Log.w(TAG, "compare failed: " + a, e);
            return false;
        }
    }

    // Fills buf as far as the stream allows and returns the byte count (0 at the
    // end). Written out because InputStream.readNBytes() needs API 33 (minSdk 26)
    // 스트림이 허락하는 만큼 buf를 채우고 읽은 바이트 수를 돌려줌(끝이면 0).
    // InputStream.readNBytes()는 API 33부터라(minSdk 26) 직접 구현함
    private static int readBlock(java.io.InputStream in, byte[] buf) throws java.io.IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    // Keeps the newest KEEP_CONFLICTS conflict folders and deletes the rest. The
    // folder names are yyyyMMdd-HHmmss, so name order is time order. A conflict
    // copy is the side that lost on modification time; the side that won stays in
    // the shared folder, and older states are also in the save backups if any
    // 최근 KEEP_CONFLICTS개의 충돌 폴더만 남기고 나머지를 지움. 폴더 이름이
    // yyyyMMdd-HHmmss라 이름 순서가 곧 시간 순서임. 충돌 사본은 수정 시각에서 진
    // 쪽이고, 이긴 쪽은 공용 폴더에 남음. 백업이 있으면 그 이전 상태도 백업에 있음
    private static void pruneConflicts(File conflictsRoot) {
        File[] dirs = conflictsRoot.listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= KEEP_CONFLICTS) return;
        Arrays.sort(dirs, (x, y) -> x.getName().compareTo(y.getName()));
        for (int i = 0; i < dirs.length - KEEP_CONFLICTS; i++) {
            if (FileUtils.delete(dirs[i])) Log.i(TAG, "old conflict folder deleted: " + dirs[i].getName());
            else Log.w(TAG, "could not delete conflict folder: " + dirs[i].getName());
        }
    }

    // ==================== Per-game save export ====================
    // ==================== 게임별 세이브 추출 ====================

    // Wine / driver caches that change during any game and are not saves
    // 어떤 게임을 하든 바뀌는 Wine·드라이버 캐시. 세이브가 아님
    private static final String[] NOISE = {
            "users/" + ImageFs.USER + "/AppData/Local/Temp",
            "users/" + ImageFs.USER + "/AppData/Local/Microsoft",
            "users/" + ImageFs.USER + "/AppData/Roaming/Microsoft",
            "users/" + ImageFs.USER + "/AppData/Local/D3DSCache",
            "users/" + ImageFs.USER + "/AppData/Local/mesa_shader_cache"
    };

    // Exports one game's saves: the folders of the shared save folder whose files
    // changed while that game was running (shortcut extra "saveWindows", written
    // by XServerDisplayActivity). A save folder is taken two levels below its
    // location (e.g. AppData/Roaming/<company>/<game>), or the file itself when it
    // sits higher. Written to Download/.EZlator/Saves/<game>-<time>.tzst, with the
    // same layout as the shared folder. Returns the file, or null when no
    // changed folder was found. Runs on the caller's thread.
    // 게임 하나의 세이브를 추출함: 그 게임이 실행되는 동안(숏컷 extra
    // "saveWindows", XServerDisplayActivity가 기록) 파일이 바뀐 공용 세이브 폴더의
    // 폴더들임. 세이브 폴더는 위치에서 두 단계 아래까지로 잡고(예: AppData/Roaming/
    // <회사>/<게임>), 그보다 위에 있는 파일은 파일 자체를 잡음. 공용 폴더와 같은
    // 구조로 Download/.EZlator/Saves/<게임>-<시각>.tzst에 씀. 만든 파일을 돌려주고,
    // 바뀐 폴더를 못 찾으면 null. 부른 스레드에서 실행됨
    public static File exportGame(Context context, Shortcut shortcut) {
        java.util.List<long[]> windows = new java.util.ArrayList<>();
        for (String w : shortcut.getExtra("saveWindows", "").split(";")) {
            String[] se = w.split("-");
            if (se.length != 2) continue;
            try {
                // A minute of slack on both sides for files written while closing
                // 종료 중에 쓰인 파일을 위해 양쪽에 1분 여유를 둠
                windows.add(new long[]{Long.parseLong(se[0]) - 60_000L, Long.parseLong(se[1]) + 60_000L});
            } catch (NumberFormatException ignored) {}
        }
        if (windows.isEmpty()) return null;

        File root = sharedRoot(context);
        java.util.Set<String> units = new java.util.LinkedHashSet<>();
        for (String entry : ENTRIES) collect(new File(root, entry), entry, 0, windows, units);
        if (units.isEmpty()) return null;

        File dir = AppDirs.downloads("Saves");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH).format(new Date());
        String name = shortcut.name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File out = new File(dir, name + "-" + stamp + ".tzst");
        String rootPath = root.getAbsolutePath();
        TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, root, out, 3, file -> {
            String path = file.getAbsolutePath();
            if (path.equals(rootPath)) return true;
            String rel = path.substring(rootPath.length() + 1);
            for (String unit : units) {
                if (rel.equals(unit) || rel.startsWith(unit + "/") || unit.startsWith(rel + "/")) return true;
            }
            return false;
        });
        Log.i(TAG, "exported " + shortcut.name + ": " + units);
        if (out.isFile() && out.length() > 0) return out;
        out.delete();
        return null;
    }

    private static void collect(File dir, String rel, int depth, java.util.List<long[]> windows, java.util.Set<String> units) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String r = rel + "/" + child.getName();
            boolean noise = false;
            for (String n : NOISE) if (r.equals(n) || r.startsWith(n + "/")) { noise = true; break; }
            if (noise || FileUtils.isSymlink(child)) continue;
            if (child.isDirectory()) {
                collect(child, r, depth + 1, windows, units);
            } else {
                long m = child.lastModified();
                for (long[] w : windows) {
                    if (m >= w[0] && m <= w[1]) {
                        // Unit: the save location plus at most two levels below it
                        // 단위: 세이브 위치에서 최대 두 단계 아래까지
                        units.add(unitOf(r, depth));
                        break;
                    }
                }
            }
        }
    }

    // Cuts a path to its save location (ENTRIES item) plus two more levels
    // 경로를 세이브 위치(ENTRIES 항목)와 그 아래 두 단계까지로 자름
    private static String unitOf(String path, int depth) {
        for (String entry : ENTRIES) {
            if (path.startsWith(entry + "/")) {
                String[] rest = path.substring(entry.length() + 1).split("/");
                int keep = Math.min(2, rest.length);
                StringBuilder sb = new StringBuilder(entry);
                for (int i = 0; i < keep; i++) sb.append('/').append(rest[i]);
                return sb.toString();
            }
        }
        return path;
    }

    // ==================== Backup / restore ====================
    // ==================== 백업 / 복원 ====================

    public static File backupDir() {
        return AppDirs.downloads("SaveBackups");
    }

    // Packs the whole shared save folder into Download/.EZlator/SaveBackups/
    // saves-<time>[-<reason>].tzst. Returns the file, or null when there is nothing
    // to back up or packing failed. Runs on the caller's thread.
    // 공용 세이브 폴더 전체를 Download/.EZlator/SaveBackups/
    // saves-<시각>[-<사유>].tzst로 묶음. 만든 파일을 돌려주고, 묶을 것이 없거나
    // 실패하면 null. 부른 스레드에서 실행됨
    public static File backup(Context context, String reason) {
        File root = sharedRoot(context);
        if (!root.isDirectory() || FileUtils.isEmpty(root)) return null;
        File dir = backupDir();
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH).format(new Date());
        File out = new File(dir, "saves-" + stamp + (reason != null ? "-" + reason : "") + ".tzst");
        TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, root, out, 3);
        if (out.isFile() && out.length() > 0) return out;
        out.delete();
        return null;
    }

    // Backup files, newest first
    // 백업 파일 목록. 최신순
    public static File[] listBackups() {
        File[] files = backupDir().listFiles((d, name) -> name.startsWith("saves-") && name.endsWith(".tzst"));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    // Restores a backup over the shared save folder. The current state is backed
    // up first ("before-restore"), so a wrong pick can be undone.
    // 백업을 공용 세이브 폴더 위에 되돌림. 현재 상태를 먼저 백업하므로("before-restore")
    // 잘못 골라도 되돌릴 수 있음
    public static boolean restore(Context context, File backup) {
        backup(context, "before-restore");
        File root = sharedRoot(context);
        File parent = root.getParentFile();
        File old = new File(parent, SHARED_DIR + ".old");
        FileUtils.delete(old);
        if (root.exists() && !root.renameTo(old)) return false;
        boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, backup, parent);
        if (ok && root.isDirectory()) {
            FileUtils.delete(old);
            return true;
        }
        // Extraction failed: put the previous folder back
        // 해제 실패: 이전 폴더를 되돌려 놓음
        FileUtils.delete(root);
        old.renameTo(root);
        return false;
    }
}
