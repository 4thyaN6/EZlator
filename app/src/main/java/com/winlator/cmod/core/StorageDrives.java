package com.winlator.cmod.core;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import com.winlator.cmod.container.Container;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Builds extra drive mappings for removable volumes (SD cards, USB storage).
 *
 * The volume UUID is part of its path (/storage/08DA-D29F/...), so a hardcoded drive
 * mapping breaks the moment the card is swapped or remounted. These mappings are
 * therefore recomputed on every container launch and never written to the container
 * config - see Container.drivesIteratorWithAuto().
 *
 * 착탈식 볼륨(SD카드, USB 저장장치)에 대한 추가 드라이브 매핑을 만듦.
 *
 * 볼륨 UUID가 경로에 들어 있어서(/storage/08DA-D29F/...) 매핑을 하드코딩해두면 카드를
 * 교체하거나 재마운트하는 순간 깨짐. 그래서 컨테이너를 실행할 때마다 다시 계산하고
 * 컨테이너 설정에는 저장하지 않음. Container.drivesIteratorWithAuto() 참고
 */
public final class StorageDrives {
    private static final String TAG = "StorageDrives";

    /** Folder created at the volume root so users have one obvious place to put games.
     *  사용자가 게임을 넣을 곳이 한눈에 보이도록 볼륨 루트에 만드는 폴더 */
    public static final String GAMES_DIR_NAME = "EZlator";

    /** Sub-folders of an EZlator folder: archives the user puts in (internal
     *  storage), and games unpacked from them (E:, or D: without an SD card).
     *  Games added by hand come from many places, so they get their own
     *  "Custom Games" folder, apart from archives and from Steam games.
     *  EZlator 폴더의 하위 폴더: 사용자가 넣는 압축 파일(내장 메모리)과, 거기서 푼
     *  게임(E:, SD카드가 없으면 D:). 직접 추가하는 게임은 여러 곳에서 오므로 압축
     *  파일·스팀 게임과 나눠 "Custom Games" 폴더에 따로 둠 */
    public static final String ARCHIVES_DIR_NAME = "Archives";
    public static final String UNPACKED_DIR_NAME = "Custom Games";

    /** Auto assignment starts at E: (the app-private E: default was removed).
     *  A container that still has E: in its saved list keeps it - used letters
     *  are skipped below, so the SD card simply moves on to F:.
     *  자동 할당은 E:부터 시작함(앱 전용 E: 기본값을 제거했기 때문임).
     *  저장된 목록에 E:가 남아 있는 기존 컨테이너는 그대로 유지되고,
     *  아래에서 사용 중 문자를 건너뛰므로 SD카드는 F:로 밀려남 */
    private static final char FIRST_AUTO_LETTER = 'E';

    private StorageDrives() {}

    /**
     * Returns the auto-assigned portion only, in the same "F:<path>G:<path>" format the
     * container uses. Empty string when there is nothing to add.
     * 자동 할당분만 컨테이너와 동일한 "F:<경로>G:<경로>" 형식으로 반환함.
     * 추가할 게 없으면 빈 문자열임
     */
    /**
     * Release builds create and use the EZlator games folders; debug builds
     * (FLAG_DEBUGGABLE, i.e. the builds used while testing) leave storage alone
     * and keep D: on Download, so testing does not litter the device.
     * 릴리즈 빌드는 EZlator 게임 폴더를 만들어 쓰고, 디버그 빌드(FLAG_DEBUGGABLE,
     * 즉 테스트용 빌드)는 저장소를 건드리지 않고 D:를 다운로드 폴더로 둠.
     * 테스트 중 기기에 폴더가 생기지 않게 하기 위함
     */
    public static boolean isReleaseBuild(Context context) {
        return context != null
                && (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0;
    }

    /**
     * Default drive list for a NEW container: D: = internal EZlator (release) or
     * Download (debug). E: is not listed - the SD card's EZlator folder is added
     * automatically at launch by buildAutoDrives(), since its path contains the
     * card's UUID.
     * 새 컨테이너의 기본 드라이브 목록: D: = 내장 EZlator(릴리즈) 또는
     * 다운로드(디버그). E:는 목록에 넣지 않음 - SD카드 경로엔 카드 UUID가 들어가서
     * 실행 시 buildAutoDrives()가 SD카드의 EZlator 폴더를 자동으로 붙임
     */
    public static String defaultDrives(Context context) {
        if (isReleaseBuild(context)) {
            File dir = getInternalGamesDir();
            if (ensureGamesFolder(dir)) return "D:" + dir.getAbsolutePath();
        }
        return Container.DEFAULT_DRIVES;
    }

    /** Internal-storage games folder: /storage/emulated/0/EZlator
     *  내장 메모리 게임 폴더: /storage/emulated/0/EZlator */
    public static File getInternalGamesDir() {
        return new File(Environment.getExternalStorageDirectory(), GAMES_DIR_NAME);
    }

    /**
     * Creates a games folder plus a ".nomedia" file inside it. A top-level folder
     * keeps thousands of game files out of Download (and out of the Downloads view
     * of Google's Files app); .nomedia keeps game images/audio/video out of the
     * gallery and music apps. The folder itself stays visible so users can copy
     * games into it - a dot-named folder would be hidden from them too.
     * 게임 폴더와 그 안의 ".nomedia" 파일을 만듦. 최상위 폴더라서 게임 파일 수천 개가
     * 다운로드 폴더(구글 파일 앱의 다운로드 보기 포함)에 섞이지 않고, .nomedia 덕분에
     * 게임 이미지/소리/영상이 갤러리·음악 앱에 뜨지 않음. 폴더 자체는 보이므로
     * 사용자가 게임을 복사해 넣을 수 있음 - 점(.)으로 시작하는 폴더면 사용자에게도
     * 숨겨짐
     */
    public static boolean ensureGamesFolder(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create " + dir.getPath());
            return false;
        }
        File noMedia = new File(dir, ".nomedia");
        try {
            if (!noMedia.exists()) noMedia.createNewFile();
        } catch (Exception e) {
            Log.w(TAG, "Could not create " + noMedia.getPath(), e);
        }
        return true;
    }

    // Letters for whole-storage access. A game copied somewhere
    // other than an EZlator folder still gets a Windows path: Y: is the root of
    // internal storage, X: (then W:, V:) the root of each removable volume.
    // Taken from the end of the alphabet so they never collide with D:/E:/F:...
    // 저장소 전체 접근용 문자임. EZlator 폴더가 아닌 곳에 복사한 게임도
    // 윈도우 경로를 갖게 함: Y:는 내장 메모리 루트, X:(그다음 W:, V:)는 각 착탈식
    // 볼륨의 루트임. D:/E:/F:...와 겹치지 않게 알파벳 끝에서 가져옴
    private static final char INTERNAL_ROOT_LETTER = 'Y';
    private static final char FIRST_REMOVABLE_ROOT_LETTER = 'X';

    /**
     * Launch-time drives that are always the same, in the container's
     * "D:<path>E:<path>" format. These letters OVERRIDE the user's own list
     * (see Container.drivesIteratorWithAuto()):
     *   D: internal storage EZlator folder   (release builds; debug keeps the user's D:)
     *   E: EZlator folder of the first removable volume (SD card / USB)
     *   F:, G: ... EZlator folders of further removable volumes
     *   Y: internal storage root, X:/W:/V: removable volume roots
     * 실행 시점에 항상 같은 드라이브임. 형식은 컨테이너와 같은
     * "D:<경로>E:<경로>"임. 이 문자들은 사용자 목록보다 우선함
     * (Container.drivesIteratorWithAuto() 참고):
     *   D: 내장 메모리 EZlator 폴더   (릴리즈 빌드. 디버그는 사용자 D:를 유지)
     *   E: 첫 번째 착탈식 볼륨(SD카드 / USB)의 EZlator 폴더
     *   F:, G: ... 나머지 착탈식 볼륨의 EZlator 폴더
     *   Y: 내장 메모리 루트, X:/W:/V: 착탈식 볼륨 루트
     */
    public static String buildAutoDrives(Context context, String userDrives) {
        if (context == null) return "";
        // Folders are only created in release builds (see isReleaseBuild())
        // 폴더는 릴리즈 빌드에서만 만듦(isReleaseBuild() 참고)
        final boolean release = isReleaseBuild(context);
        StringBuilder result = new StringBuilder();

        // D: fixed to the internal EZlator folder
        // D:는 내장 EZlator 폴더로 고정
        if (release) {
            File internalGames = getInternalGamesDir();
            if (ensureGamesFolder(internalGames)) {
                result.append("D:").append(internalGames.getAbsolutePath());
                // Folder for archives to add (read from internal storage, unpacked to E:)
                // 추가할 압축 파일을 넣는 폴더(내장에서 읽고 E:로 풂)
                File archives = new File(internalGames, ARCHIVES_DIR_NAME);
                if (!archives.isDirectory() && !archives.mkdirs()) Log.w(TAG, "Could not create " + archives.getPath());
            }
        }

        // User letters other than D:/E: are respected; removable volumes after
        // the first one skip them
        // D:/E: 이외의 사용자 문자는 존중함. 두 번째 이후 착탈식 볼륨은 그 문자를 건너뜀
        LinkedHashSet<Character> usedLetters = new LinkedHashSet<>();
        if (userDrives != null && !userDrives.isEmpty()) {
            for (String[] drive : Container.drivesIterator(userDrives)) {
                if (drive[0] != null && !drive[0].isEmpty()) {
                    usedLetters.add(Character.toUpperCase(drive[0].charAt(0)));
                }
            }
        }
        usedLetters.add('C');
        usedLetters.add('D');
        usedLetters.add('Z');
        usedLetters.add(INTERNAL_ROOT_LETTER);

        List<File> volumes = findRemovableVolumes(context);
        char rootLetter = FIRST_REMOVABLE_ROOT_LETTER;
        for (int i = 0; i < volumes.size(); i++) usedLetters.add((char) (rootLetter - i));

        char nextLetter = 'F';
        for (int i = 0; i < volumes.size(); i++) {
            File volumeDir = volumes.get(i);
            // Create the suggested games folder. Failure here is not fatal - the
            // volume root is mapped instead
            // 권장 게임 폴더를 만듦. 실패해도 치명적이지 않음 - 대신 볼륨 루트를 매핑함
            File gamesDir = new File(volumeDir, GAMES_DIR_NAME);
            if (release) ensureGamesFolder(gamesDir);
            File mapped = gamesDir.isDirectory() ? gamesDir : volumeDir;

            char letter;
            if (i == 0) {
                letter = FIRST_AUTO_LETTER; // E: fixed / E: 고정
            } else {
                while (usedLetters.contains(nextLetter) && nextLetter < 'V') nextLetter++;
                if (nextLetter >= 'V') {
                    Log.w(TAG, "No free drive letter left for " + volumeDir.getPath());
                    continue;
                }
                letter = nextLetter++;
            }
            usedLetters.add(letter);
            result.append(letter).append(':').append(mapped.getAbsolutePath());
            Log.d(TAG, "Auto-mapped " + letter + ": -> " + mapped.getAbsolutePath());
        }

        // Whole-storage roots for games outside the EZlator folders
        // EZlator 폴더 밖 게임용 저장소 전체 루트
        result.append(INTERNAL_ROOT_LETTER).append(':')
                .append(Environment.getExternalStorageDirectory().getAbsolutePath());
        for (int i = 0; i < volumes.size() && i < 3; i++) {
            result.append((char) (rootLetter - i)).append(':').append(volumes.get(i).getAbsolutePath());
        }
        return result.toString();
    }

    // Launch-time drive list: auto letters replace a user entry with the same
    // letter (D:/E: are fixed), other user entries are kept in front
    // 실행 시점 드라이브 목록: 같은 문자의 사용자 항목은 자동 항목이 대체함(D:/E:
    // 고정), 나머지 사용자 항목은 앞쪽에 그대로 둠
    public static String mergeDrives(String userDrives, String autoDrives) {
        if (autoDrives == null || autoDrives.isEmpty()) return userDrives != null ? userDrives : "";
        LinkedHashSet<Character> autoLetters = new LinkedHashSet<>();
        for (String[] d : Container.drivesIterator(autoDrives)) autoLetters.add(Character.toUpperCase(d[0].charAt(0)));
        StringBuilder out = new StringBuilder();
        if (userDrives != null && !userDrives.isEmpty()) {
            for (String[] d : Container.drivesIterator(userDrives)) {
                if (d[0] == null || d[0].isEmpty()) continue;
                if (autoLetters.contains(Character.toUpperCase(d[0].charAt(0)))) continue;
                out.append(d[0]).append(':').append(d[1]);
            }
        }
        return out.append(autoDrives).toString();
    }

    /**
     * Mounted removable volumes, primary shared storage excluded (that is already D:).
     * 마운트된 착탈식 볼륨임. 기본 공유 저장소는 이미 D:라서 제외함
     */
    private static List<File> findRemovableVolumes(Context context) {
        ArrayList<File> dirs = new ArrayList<>();

        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return dirs;

        try {
            for (StorageVolume volume : sm.getStorageVolumes()) {
                if (volume.isPrimary()) continue;
                if (!Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;

                File dir = resolveDirectory(volume);
                if (dir != null && dir.isDirectory()) dirs.add(dir);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to enumerate storage volumes", e);
        }
        return dirs;
    }

    /**
     * getDirectory() is API 30+. Older releases only expose the path through a hidden
     * getPath(), so fall back to reflection and then to /storage/<uuid>.
     * getDirectory()는 API 30 이상임. 그 이하에서는 숨겨진 getPath()로만 경로를 얻을 수
     * 있어 리플렉션을 쓰고, 그것도 안 되면 /storage/<uuid>로 폴백함
     */
    private static File resolveDirectory(StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File dir = volume.getDirectory();
            if (dir != null) return dir;
        }

        try {
            Object path = StorageVolume.class.getMethod("getPath").invoke(volume);
            if (path instanceof String) return new File((String) path);
        } catch (Exception ignored) {}

        String uuid = volume.getUuid();
        return uuid != null ? new File("/storage/" + uuid) : null;
    }

    private static String normalize(String path) {
        String p = path.trim();
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static boolean isAlreadyCovered(String path, LinkedHashSet<String> usedPaths) {
        String lower = path.toLowerCase(Locale.ENGLISH);
        for (String used : usedPaths) {
            String u = used.toLowerCase(Locale.ENGLISH);
            if (lower.equals(u) || lower.startsWith(u + "/") || u.startsWith(lower + "/")) {
                return true;
            }
        }
        return false;
    }
}