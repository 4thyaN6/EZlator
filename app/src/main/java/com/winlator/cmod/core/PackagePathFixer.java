package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Rewrites references to the upstream package path inside an extracted imagefs.
 * The imagefs archive is shared with Winlator Cmod, so config/script files may
 * point at /data/data/com.winlator.cmod/... which does not exist under EZlator.
 * imagefs 안에 남아 있는 원본 패키지 경로 참조를 고침.
 * imagefs 압축본은 Winlator Cmod와 공용이라 설정/스크립트 파일이
 * /data/data/com.winlator.cmod/...를 가리킬 수 있는데, EZlator에는 그 경로가 없음
 *
 * Text files are rewritten in place. Binaries are NOT patched (a different-length
 * string would corrupt them); they are only counted and logged with tag
 * "PackagePathFixer" so the cause is visible if something fails to start.
 * 텍스트 파일은 그 자리에서 고침. 바이너리는 고치지 않음(길이가 다른 문자열을
 * 넣으면 파일이 깨짐). 개수와 경로만 "PackagePathFixer" 태그로 로그에 남겨서
 * 실행 실패 시 원인을 바로 볼 수 있게 함
 */
public final class PackagePathFixer {
    private static final String TAG = "PackagePathFixer";
    private static final String OLD_PACKAGE = "com.winlator.cmod";
    // Files bigger than this are skipped: large files are libraries/archives,
    // and reading every one would stall the first-run install
    // 이보다 큰 파일은 건너뜀: 큰 파일은 라이브러리/압축본이고, 전부 읽으면
    // 최초 설치가 오래 멈춤
    private static final long MAX_SCAN_SIZE = 4L * 1024 * 1024;

    private PackagePathFixer() {}

    public static void fix(Context context, File rootDir) {
        String pkg = context.getPackageName();
        if (OLD_PACKAGE.equals(pkg) || rootDir == null || !rootDir.isDirectory()) return;

        String[][] pairs = {
                {"/data/data/" + OLD_PACKAGE + "/", "/data/data/" + pkg + "/"},
                {"/data/user/0/" + OLD_PACKAGE + "/", "/data/user/0/" + pkg + "/"},
        };
        int[] counts = new int[2]; // [0]=text fixed, [1]=binary found / [0]=텍스트 수정, [1]=바이너리 발견
        long start = System.currentTimeMillis();
        walk(rootDir, pairs, counts);
        Log.i(TAG, "done in " + (System.currentTimeMillis() - start) + "ms: text fixed="
                + counts[0] + ", binaries still referencing " + OLD_PACKAGE + "=" + counts[1]);
    }

    private static void walk(File dir, String[][] pairs, int[] counts) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            // Symlinks are skipped so nothing outside imagefs is ever touched
            // 심볼릭 링크는 건너뜀. imagefs 밖을 건드리지 않기 위함
            if (Files.isSymbolicLink(f.toPath())) continue;
            if (f.isDirectory()) walk(f, pairs, counts);
            else if (f.isFile() && f.length() > 0 && f.length() <= MAX_SCAN_SIZE) scanFile(f, pairs, counts);
        }
    }

    private static void scanFile(File f, String[][] pairs, int[] counts) {
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            if (indexOf(data, OLD_PACKAGE.getBytes(StandardCharsets.US_ASCII)) < 0) return;

            if (isBinary(data)) {
                counts[1]++;
                Log.w(TAG, "binary contains old package path (not patched): " + f.getPath());
                return;
            }
            String text = new String(data, StandardCharsets.UTF_8);
            String fixed = text;
            for (String[] p : pairs) fixed = fixed.replace(p[0], p[1]);
            if (!fixed.equals(text)) {
                // Rewriting the same File keeps its permission bits
                // 같은 File에 다시 쓰므로 권한 비트는 유지됨
                Files.write(f.toPath(), fixed.getBytes(StandardCharsets.UTF_8));
                counts[0]++;
            }
        } catch (Exception e) {
            Log.w(TAG, "scan failed: " + f.getPath(), e);
        }
    }

    // A NUL byte in the first 8KB is the usual text/binary heuristic
    // 앞 8KB에 NUL 바이트가 있으면 바이너리로 보는 일반적인 판별법임
    private static boolean isBinary(byte[] data) {
        int n = Math.min(data.length, 8192);
        for (int i = 0; i < n; i++) if (data[i] == 0) return true;
        return false;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }
}
