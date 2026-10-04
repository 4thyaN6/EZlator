package com.winlator.cmod.container;

import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.steam.SteamGameInstaller;

import java.io.File;
import java.util.Locale;

// Moves a shortcut into another container. Shared by the shortcut settings
// dialog (container picker) and the detail sheet's layer button, so both
// paths copy the same files and refuse the same cases.
// 숏컷을 다른 컨테이너로 옮김. 숏컷 설정창(컨테이너 선택)과 상세창의 레이어
// 버튼이 함께 씀. 그래서 두 경로가 같은 파일을 복사하고 같은 경우를 거부함
public final class ShortcutMover {
    public static final int OK = 0;
    // The exe sits in the current container's own drive_c, which the target
    // container cannot see
    // exe가 현재 컨테이너 자체의 drive_c 안에 있어서 대상 컨테이너에서 안 보임
    public static final int C_DRIVE_BLOCKED = 1;
    public static final int NAME_EXISTS = 2;
    public static final int FAILED = 3;

    public static final class Result {
        public final int code;
        public final File newFile;

        Result(int code, File newFile) {
            this.code = code;
            this.newFile = newFile;
        }
    }

    private ShortcutMover() {}

    // Checks shared by move and clone: OK, C_DRIVE_BLOCKED or NAME_EXISTS
    // 이동과 복제가 함께 쓰는 검사임: OK, C_DRIVE_BLOCKED, NAME_EXISTS 중 하나
    public static int check(Shortcut shortcut, Container target) {
        String exePath = shortcut.path != null ? shortcut.path.trim() : "";
        if (exePath.startsWith("\"")) exePath = exePath.substring(1);
        // Steam-library games live outside C: and are linked into every container,
        // so their C:\Program Files (x86)\Steam\steamapps\common path may move
        // Steam 라이브러리 게임은 C: 밖에 있고 모든 컨테이너에 링크되므로,
        // C:\Program Files (x86)\Steam\steamapps\common 경로여도 옮겨도 됨
        boolean steamLibraryGame = exePath.toLowerCase(Locale.ENGLISH)
                .startsWith(SteamGameInstaller.CONTAINER_STEAM_COMMON_WIN.toLowerCase(Locale.ENGLISH));
        if (!steamLibraryGame && exePath.regionMatches(true, 0, "C:", 0, 2)) return C_DRIVE_BLOCKED;
        if (new File(target.getDesktopDir(), shortcut.file.getName()).exists()) return NAME_EXISTS;
        return OK;
    }

    private static boolean isComponentExtra(String line) {
        for (String key : LayerTiers.COMPONENT_EXTRAS) if (line.startsWith(key + "=")) return true;
        return false;
    }

    public static Result move(Shortcut shortcut, Container target) {
        int checked = check(shortcut, target);
        if (checked != OK) return new Result(checked, null);

        File targetDesktop = target.getDesktopDir();
        if (!targetDesktop.isDirectory()) targetDesktop.mkdirs();
        File oldFile = shortcut.file;
        File newFile = new File(targetDesktop, oldFile.getName());

        // Rewrite container_id (both "=" and the older ":" form are in use)
        // container_id를 고쳐 씀("="와 예전 ":" 형식이 둘 다 쓰이고 있음)
        // Every other setting (exec arguments, controls, locale, images, play time...)
        // is copied as it is. Only when the architecture changes, the shortcut's own
        // component builds (DXVK / VKD3D, Box64 / WOWBox64, FEXCore) are dropped: they
        // are builds of the old architecture, so the target container's builds apply
        // 나머지 설정(실행 인수, 컨트롤, 로캘, 이미지, 플레이 시간 등)은 그대로 복사함.
        // 아키텍처가 바뀔 때만 숏컷의 개별 구성요소 빌드(DXVK / VKD3D, Box64 /
        // WOWBox64, FEXCore)를 뺌: 예전 아키텍처용 빌드이므로 대상 컨테이너의 빌드를 씀
        boolean archChanged = ContainerManager.isArm64EC(shortcut.container) != ContainerManager.isArm64EC(target);
        StringBuilder out = new StringBuilder();
        for (String line : FileUtils.readLines(oldFile)) {
            if (line.startsWith("container_id=") || line.startsWith("container_id:"))
                line = "container_id=" + target.id;
            if (archChanged && isComponentExtra(line)) continue;
            out.append(line).append("\n");
        }
        if (!FileUtils.writeString(newFile, out.toString())) return new Result(FAILED, null);

        // Icons live per container; copy every size that exists
        // 아이콘은 컨테이너별로 저장되므로 있는 크기를 전부 복사함
        String iconName = shortcut.iconFile != null ? shortcut.iconFile.getName() : null;
        if (iconName != null) {
            for (int size : new int[]{64, 48, 32, 16}) {
                File src = new File(shortcut.container.getIconsDir(size), iconName);
                if (src.isFile()) {
                    File dstDir = target.getIconsDir(size);
                    dstDir.mkdirs();
                    FileUtils.copy(src, new File(dstDir, iconName));
                }
            }
        }

        // Tile image / cover copies follow the shortcut into the new container;
        // the old copies are removed only once the new ones exist
        // 타일 이미지 / 커버 사본도 새 컨테이너로 따라감. 옛 사본은 새 사본이
        // 생긴 뒤에만 지움
        try {
            Shortcut moved = new Shortcut(target, newFile);
            moved.adoptForeignImages();
            shortcut.releaseImagesAfterMove(moved);
        } catch (Exception e) {
            Log.w("ShortcutMover", "Moved, but image copy failed", e);
        }

        // .lnk twin of the current file name
        // 현재 파일 이름과 짝인 .lnk
        File oldLnk = new File(oldFile.getParentFile(), FileUtils.getBasename(oldFile.getPath()) + ".lnk");
        if (oldLnk.isFile()) {
            FileUtils.copy(oldLnk, new File(targetDesktop, oldLnk.getName()));
            oldLnk.delete();
        }
        if (oldFile.exists() && !oldFile.delete()) {
            Log.e("ShortcutMover", "Failed to delete old file: " + oldFile.getPath());
        }
        return new Result(OK, newFile);
    }
}
