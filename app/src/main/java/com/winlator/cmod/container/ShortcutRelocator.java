package com.winlator.cmod.container;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds games that were moved with a file manager (e.g. from E:\Windows Game
 * to E:\Custom Games) and points their shortcuts at the new place, so play
 * time, per-game settings and pictures stay with the shortcut.
 *
 * Matching: the old path minus its drive is tried, longest tail first, under
 * every drive root and under each drive's "Custom Games" folder. At least the
 * game's folder plus the exe must match (the exe name alone is never enough:
 * RPG Maker games are all "Game.exe"). The longest tail that exists wins; when
 * that tail exists in more than one place the shortcut is left alone.
 * Pure Java (no Android classes) so it can be tested off the device.
 *
 * 파일 관리자로 옮긴 게임(예: E:\Windows Game -> E:\Custom Games)을 찾아 숏컷이 새
 * 위치를 가리키게 함. 그래서 플레이 시간, 게임별 설정, 그림이 숏컷에 그대로 남음.
 *
 * 찾는 방법: 드라이브를 뺀 예전 경로를 긴 꼬리부터 차례로, 모든 드라이브 루트와 각
 * 드라이브의 "Custom Games" 폴더 아래에서 찾아봄. 최소한 게임 폴더 + exe가 맞아야 함
 * (exe 이름만으로는 안 됨: RPG 메이커 게임은 전부 "Game.exe"임). 존재하는 가장 긴
 * 꼬리를 씀. 그 꼬리가 두 곳 이상에 있으면 숏컷을 건드리지 않음.
 * 안드로이드 클래스를 쓰지 않는 순수 Java라 기기 밖에서 시험할 수 있음
 */
public final class ShortcutRelocator {
    private ShortcutRelocator() {}

    public enum Status { FOUND, AMBIGUOUS, NOT_FOUND, UNSUPPORTED }

    /** Result of one lookup / 찾기 결과 하나 */
    public static final class Match {
        public final Status status;
        // New Windows exe path (without arguments) when FOUND
        // FOUND일 때 새 Windows exe 경로(실행 인자 제외)
        public final String newExePath;

        Match(Status status, String newExePath) {
            this.status = status;
            this.newExePath = newExePath;
        }
    }

    /**
     * Looks for the moved exe. drives maps a lower-case letter ('d', 'e', ...)
     * to the real folder of that drive; C: and Z: are never searched (inside
     * the container / the whole device). subFolder is searched under every
     * drive as well (e.g. "Custom Games").
     * 옮겨진 exe를 찾음. drives는 소문자 문자('d', 'e' ...)를 그 드라이브의 실제 폴더로
     * 대응시킴. C:와 Z:는 찾지 않음(컨테이너 안 / 기기 전체). subFolder도 모든 드라이브
     * 아래에서 찾음(예: "Custom Games")
     */
    public static Match find(String oldExePath, Map<Character, File> drives, String subFolder) {
        if (oldExePath == null || oldExePath.length() < 4 || oldExePath.charAt(1) != ':') {
            return new Match(Status.UNSUPPORTED, null);
        }
        char oldDrive = Character.toLowerCase(oldExePath.charAt(0));
        if (oldDrive == 'c' || oldDrive == 'z') return new Match(Status.UNSUPPORTED, null);
        List<String> parts = new ArrayList<>();
        for (String p : oldExePath.substring(2).split("[\\\\/]+")) if (!p.isEmpty()) parts.add(p);
        if (parts.size() < 2) return new Match(Status.UNSUPPORTED, null);

        // Search roots as "<letter>:\<prefix>" -> real folder
        // 찾을 곳: "<문자>:\<앞부분>" -> 실제 폴더
        Map<String, File> roots = new LinkedHashMap<>();
        for (Map.Entry<Character, File> d : drives.entrySet()) {
            char letter = Character.toLowerCase(d.getKey());
            if (letter == 'c' || letter == 'z' || d.getValue() == null) continue;
            String winRoot = Character.toUpperCase(letter) + ":\\";
            roots.put(winRoot, d.getValue());
            if (subFolder != null && !subFolder.isEmpty()) {
                roots.put(winRoot + subFolder + "\\", new File(d.getValue(), subFolder));
            }
        }

        // Longest tail first; at least folder + exe
        // 긴 꼬리부터. 최소 폴더 + exe
        for (int len = parts.size(); len >= 2; len--) {
            List<String> tail = parts.subList(parts.size() - len, parts.size());
            Set<String> realHits = new LinkedHashSet<>();
            String firstWinPath = null;
            for (Map.Entry<String, File> root : roots.entrySet()) {
                File f = root.getValue();
                for (String t : tail) f = new File(f, t);
                if (!f.isFile()) continue;
                String real;
                try {
                    real = f.getCanonicalPath();
                } catch (IOException e) {
                    real = f.getAbsolutePath();
                }
                // The same file can be reached from two drives (D: and Y:); counted once
                // 같은 파일이 두 드라이브(D:와 Y:)로 보일 수 있음. 한 번만 셈
                if (realHits.add(real) && firstWinPath == null) {
                    firstWinPath = root.getKey() + String.join("\\", tail);
                }
            }
            if (realHits.size() == 1) return new Match(Status.FOUND, firstWinPath);
            if (realHits.size() > 1) return new Match(Status.AMBIGUOUS, null);
        }
        return new Match(Status.NOT_FOUND, null);
    }

    /** Exe part of a shortcut path ("D:\a\Game.exe -arg" -> "D:\a\Game.exe") / 숏컷 경로의 exe 부분 */
    public static String exePart(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.startsWith("\"")) p = p.substring(1);
        int quote = p.indexOf('"');
        if (quote >= 0) p = p.substring(0, quote);
        int exeEnd = p.toLowerCase(Locale.ENGLISH).indexOf(".exe");
        return exeEnd > 0 ? p.substring(0, exeEnd + 4) : p;
    }

    /**
     * Rewrites the shortcut file for the new exe path: the Windows path after
     * "wine " in Exec= (arguments after the exe are kept) and the working folder
     * in Path=. Every other line, including [Extra Data] (play time, settings),
     * is written back unchanged. Exec uses the same escaping new shortcuts get
     * (each backslash written four times).
     * 새 exe 경로에 맞게 숏컷 파일을 고침: Exec=의 "wine " 뒤 Windows 경로(exe 뒤 실행
     * 인자는 유지)와 Path=의 작업 폴더. 그 밖의 줄은 [Extra Data](플레이 시간, 설정)를
     * 포함해 그대로 씀. Exec는 새 숏컷과 같은 이스케이프를 씀(역슬래시를 네 번씩 씀)
     */
    public static boolean rewrite(File desktopFile, String oldPath, String newExePath, File genericDosdevices) throws IOException {
        String oldExe = exePart(oldPath);
        String args = oldPath != null && oldExe != null && oldPath.trim().length() > 0
                ? argsAfterExe(oldPath) : "";
        List<String> lines = Files.readAllLines(desktopFile.toPath(), StandardCharsets.UTF_8);
        boolean execDone = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!execDone && line.startsWith("Exec=")) {
                int w = line.lastIndexOf("wine ");
                if (w < 0) return false;
                // A path written in quotes keeps its quotes / 따옴표로 쓴 경로는 따옴표를 유지함
                boolean quoted = oldPath != null && oldPath.trim().startsWith("\"");
                String exe = quoted ? "\"" + newExePath + "\"" : newExePath;
                lines.set(i, line.substring(0, w + 5) + (exe + args).replace("\\", "\\\\\\\\"));
                execDone = true;
            } else if (line.startsWith("Path=")) {
                String afterDrive = newExePath.substring(3);
                int slash = Math.max(afterDrive.lastIndexOf('\\'), afterDrive.lastIndexOf('/'));
                String dir = slash > 0 ? afterDrive.substring(0, slash).replace('\\', '/') : "";
                File drive = new File(genericDosdevices, Character.toLowerCase(newExePath.charAt(0)) + ":");
                lines.set(i, "Path=" + (dir.isEmpty() ? drive : new File(drive, dir)).getAbsolutePath());
            }
        }
        if (!execDone) return false;
        File tmp = new File(desktopFile.getParentFile(), desktopFile.getName() + ".tmp");
        Files.write(tmp.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(desktopFile)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return true;
    }

    // " -arg" part after the exe, quotes kept as written / exe 뒤 " -arg" 부분(따옴표는 그대로)
    private static String argsAfterExe(String path) {
        String p = path.trim();
        int exeEnd = p.toLowerCase(Locale.ENGLISH).indexOf(".exe");
        if (exeEnd < 0) return "";
        String rest = p.substring(exeEnd + 4);
        if (p.startsWith("\"") && rest.startsWith("\"")) rest = rest.substring(1);
        return rest;
    }
}
