package com.winlator.cmod.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Looks for Microsoft redistributable installers that came with a game
 * (for example _CommonRedist\vcredist\2019\VC_redist.x64.exe), so the app can
 * suggest turning on the matching Win Component. Only Visual C++ 2015-2022 is
 * reported for now (the other components are checked one by one first).
 * Pure Java (no Android classes) so it can be tested off the device.
 * 게임에 함께 들어 있는 Microsoft 재배포 설치 파일을 찾음
 * (예: _CommonRedist\vcredist\2019\VC_redist.x64.exe). 그래서 앱이 맞는 Win
 * Components 항목을 켜자고 권할 수 있음. 지금은 Visual C++ 2015~2022만 알려 줌
 * (다른 구성요소는 하나씩 먼저 검토함).
 * 안드로이드 클래스를 쓰지 않는 순수 Java라 기기 밖에서 시험할 수 있음
 */
public final class RedistDetector {
    private RedistDetector() {}

    // Folders searched below the game folder (name contains one of these)
    // 게임 폴더 아래에서 찾아 들어갈 폴더(이름에 이 중 하나가 들어감)
    private static final String[] HOT_DIRS = {"redist", "commonredist", "prereq", "installer", "support", "vcredist", "vc_redist", "msvc"};
    private static final int MAX_DEPTH = 4;
    private static final int MAX_FILES = 4000;

    /**
     * Visual C++ 2015-2022 installers under gameDir (searched in the game folder
     * itself and in redistributable-looking folders, 4 levels deep). Empty when
     * none. The 2015+ installers are named vc_redist.*.exe; the older vcredist_*.exe
     * name counts only inside a folder named 2015, 2017, 2019 or 2022.
     * gameDir 아래의 Visual C++ 2015~2022 설치 파일(게임 폴더 자체와 재배포 파일로
     * 보이는 폴더를 4단계까지 찾음). 없으면 빈 목록. 2015 이상 설치 파일 이름은
     * vc_redist.*.exe이고, 옛 이름 vcredist_*.exe는 2015·2017·2019·2022 폴더 안에
     * 있을 때만 셈
     */
    public static List<File> findVcRedist2015(File gameDir) {
        List<File> out = new ArrayList<>();
        if (gameDir == null || !gameDir.isDirectory()) return out;
        int[] budget = {MAX_FILES};
        walk(gameDir, 0, false, out, budget);
        return out;
    }

    private static void walk(File dir, int depth, boolean inHot, List<File> out, int[] budget) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (--budget[0] < 0) return;
            String name = f.getName().toLowerCase(Locale.ENGLISH);
            if (f.isDirectory()) {
                if (depth >= MAX_DEPTH) continue;
                boolean hot = inHot || isHotDir(name);
                // Outside redistributable folders only the first level is opened
                // (games keep _CommonRedist next to the exe or one folder up)
                // 재배포 폴더 밖에서는 첫 단계만 열어 봄(게임은 _CommonRedist를 exe 옆이나
                // 한 단계 위에 둠)
                if (hot || depth == 0) walk(f, depth + 1, hot, out, budget);
            } else if (isVc2015Installer(name, f.getParentFile())) {
                out.add(f);
            }
        }
    }

    private static boolean isHotDir(String lowerName) {
        for (String h : HOT_DIRS) if (lowerName.contains(h)) return true;
        return false;
    }

    static boolean isVc2015Installer(String lowerName, File parent) {
        if (!lowerName.endsWith(".exe")) return false;
        if (lowerName.startsWith("vc_redist")) return true;
        if (lowerName.startsWith("vcredist") && parent != null) {
            // Walk up a few folders looking for the year (e.g. vcredist\2019\x64\vcredist_x64.exe)
            // 몇 단계 위까지 연도 폴더를 찾음(예: vcredist\2019\x64\vcredist_x64.exe)
            File p = parent;
            for (int i = 0; i < 3 && p != null; i++, p = p.getParentFile()) {
                String n = p.getName().toLowerCase(Locale.ENGLISH);
                if (n.contains("2015") || n.contains("2017") || n.contains("2019") || n.contains("2022")) return true;
            }
        }
        return false;
    }
}
