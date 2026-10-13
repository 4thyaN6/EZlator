package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Make a report log": one zip with what is needed to look into a problem,
 * private values hidden (LogScrubber), opened in Android's share window so the
 * user decides where it goes (a friend, a GitHub issue ...). Nothing is sent by
 * the app itself.
 * Contents:
 *  - device.txt: device model, Android version, GPU, app version
 *  - containers.txt: each container's layer (Wine/Proton), components, driver,
 *    DXVK / VKD3D, Box64, FEXCore
 *  - app_log.txt: this app's own Android log (logcat, newest 5000 lines)
 *  - wine_log.txt: the newest saved Wine log (Download/.EZlator/logs), when there is one
 * Not included: game files, saves, Visual C++ DLLs, Steam files.
 * "제보용 로그 만들기": 문제를 살펴보는 데 필요한 것을 zip 하나로 묶고 개인 값은
 * 가린 뒤(LogScrubber) 안드로이드 공유 창을 열어 사용자가 보낼 곳을 고르게 함(친구,
 * GitHub 이슈 등). 앱이 스스로 보내는 것은 없음.
 * 들어가는 것:
 *  - device.txt: 기기 모델, 안드로이드 버전, GPU, 앱 버전
 *  - containers.txt: 컨테이너마다 레이어(Wine/Proton), 구성요소, 드라이버,
 *    DXVK / VKD3D, Box64, FEXCore
 *  - app_log.txt: 이 앱 자체의 안드로이드 로그(logcat, 최근 5000줄)
 *  - wine_log.txt: 저장된 가장 최근 Wine 로그(Download/.EZlator/logs), 있을 때만
 * 넣지 않는 것: 게임 파일, 세이브, Visual C++ DLL, 스팀 파일
 */
public final class ReportLogBuilder {
    private static final String TAG = "ReportLogBuilder";
    private static final int LOGCAT_LINES = 8000;
    private static final long WINE_LOG_MAX = 4L * 1024 * 1024;

    private ReportLogBuilder() {}

    public static void makeAndShare(Activity activity) {
        Toast.makeText(activity, R.string.report_log_making, Toast.LENGTH_SHORT).show();
        final Context app = activity.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            File zip = null;
            try {
                zip = build(app);
            } catch (Exception e) {
                Log.w(TAG, "report log failed", e);
            }
            final File result = zip;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (result == null) {
                    Toast.makeText(activity, R.string.report_log_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".tileprovider", result);
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.report_log_share)));
                } catch (Exception e) {
                    Log.w(TAG, "share failed", e);
                    // The zip stays in the folder even when sharing fails
                    // 공유가 안 돼도 zip은 폴더에 남음
                    Toast.makeText(activity, activity.getString(R.string.report_log_saved, result.getAbsolutePath()), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // Writes Download/.EZlator/Reports/report_<time>.zip; older reports beyond 5 are removed
    // Download/.EZlator/Reports/report_<시각>.zip을 씀. 5개를 넘는 옛 제보 파일은 지움
    static File build(Context context) throws Exception {
        List<String> secrets = com.winlator.cmod.steam.SteamLiteManager.secrets(context);
        File dir = AppDirs.downloads("Reports");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no folder " + dir);
        trimOld(dir, 5);
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ENGLISH).format(new Date());
        File zip = new File(dir, "report_" + stamp + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            put(out, "device.txt", deviceInfo(context));
            put(out, "containers.txt", LogScrubber.scrub(containersInfo(context), secrets));
            put(out, "app_log.txt", LogScrubber.scrub(appLog(), secrets));
            File wine = newestWineLog();
            if (wine != null) {
                String text = wine.length() > WINE_LOG_MAX ? tail(wine, WINE_LOG_MAX) : FileUtils.readString(wine);
                put(out, "wine_log.txt", "# " + wine.getName() + "\n" + LogScrubber.scrub(text, secrets));
            }
        }
        return zip;
    }

    private static void put(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write((text != null ? text : "").getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static String deviceInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            android.content.pm.PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            sb.append("App: ").append(p.versionName).append(" (")
                    .append(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(p)).append(")\n");
        } catch (Exception ignored) {}
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("SoC: ").append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE).append('\n');
        try {
            sb.append("GPU: ").append(GPUInformation.getRenderer()).append(" / ").append(GPUInformation.getVersion()).append('\n');
        } catch (Throwable ignored) {}
        sb.append("Locale: ").append(Locale.getDefault()).append('\n');
        return sb.toString();
    }

    private static String containersInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        for (Container c : new ContainerManager(context).getContainers()) {
            sb.append("[").append(c.id).append("] ").append(c.getName()).append('\n')
                    .append("  wine: ").append(c.getWineVersion()).append('\n')
                    .append("  wincomponents: ").append(c.getWinComponents()).append('\n')
                    .append("  graphics: ").append(c.getGraphicsDriver()).append(" | ").append(c.getGraphicsDriverConfig()).append('\n')
                    .append("  dxwrapper: ").append(c.getDXWrapper()).append(" | ").append(c.getDXWrapperConfig()).append('\n')
                    .append("  box64: ").append(c.getBox64Version()).append(", fexcore: ").append(c.getFEXCoreVersion()).append('\n');
        }
        return sb.length() > 0 ? sb.toString() : "(no containers)\n";
    }

    // This app's own log lines (an app can always read its own logcat)
    // 이 앱 자체의 로그 줄(앱은 자기 logcat을 항상 읽을 수 있음)
    private static String appLog() {
        StringBuilder sb = new StringBuilder();
        try {
            // The whole buffer is read and the newest lines are kept here. With
            // "-t <n>" the device picked the newest lines of all apps first and only
            // then left out other apps, so busy system logs pushed this app's own
            // lines out (seen: a report with almost no app lines)
            // 버퍼 전체를 읽고 가장 최근 줄을 여기서 남김. "-t <n>"을 쓰면 기기가 모든
            // 앱의 최근 줄을 먼저 고른 뒤에 다른 앱 줄을 빼서, 시스템 로그가 많으면 이
            // 앱 자체의 줄이 밀려났음(확인: 앱 줄이 거의 없는 제보 로그)
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime"});
            java.util.ArrayDeque<String> last = new java.util.ArrayDeque<>(LOGCAT_LINES);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (last.size() == LOGCAT_LINES) last.pollFirst();
                    last.addLast(line);
                }
            }
            p.waitFor();
            for (String line : last) sb.append(line).append('\n');
        } catch (Exception e) {
            sb.append("(logcat not readable: ").append(e.getMessage()).append(")\n");
        }
        return sb.toString();
    }

    private static File newestWineLog() {
        File[] files = AppDirs.downloads("logs").listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".txt")) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    // Last maxBytes of a large file / 큰 파일의 마지막 maxBytes
    private static String tail(File f, long maxBytes) throws Exception {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long start = Math.max(0, raf.length() - maxBytes);
            byte[] b = new byte[(int) (raf.length() - start)];
            raf.seek(start);
            raf.readFully(b);
            return "(last " + b.length + " bytes)\n" + new String(b, StandardCharsets.UTF_8);
        }
    }

    private static void trimOld(File dir, int keep) {
        File[] files = dir.listFiles((d, n) -> n.startsWith("report_") && n.endsWith(".zip"));
        if (files == null || files.length < keep) return;
        List<File> list = new ArrayList<>(java.util.Arrays.asList(files));
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep - 1; i < list.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            list.get(i).delete();
        }
    }
}
