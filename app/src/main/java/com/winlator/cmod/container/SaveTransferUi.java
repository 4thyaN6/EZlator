package com.winlator.cmod.container;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppDirs;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.steam.SteamAccountStore;
import com.winlator.cmod.steam.SteamSession;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Screens for moving saves to and from other apps (SaveTransferIO does the
 * work). The target app is asked first; the expected size is shown before
 * anything is written; results go to Download/.EZlator/Saves.
 * 다른 앱과 세이브를 주고받는 화면(실제 작업은 SaveTransferIO). 먼저 대상 앱을
 * 묻고, 쓰기 전에 예상 크기를 보여 주며, 결과는 Download/.EZlator/Saves에 둠
 */
public final class SaveTransferUi {
    private static final String TAG = "SaveTransferUi";

    private SaveTransferUi() {}

    private interface Job<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value); }

    // Runs job off the UI thread with a progress window, then done on the UI thread
    // (null result + failure toast on an error)
    // 진행 창과 함께 UI 스레드 밖에서 job을 돌리고, UI 스레드에서 done을 부름
    // (오류면 결과 null + 실패 알림)
    private static <T> void run(Activity activity, Job<T> job, Done<T> done) {
        PreloaderDialog preloader = new PreloaderDialog(activity);
        preloader.showOnUiThread(R.string.save_working);
        Executors.newSingleThreadExecutor().execute(() -> {
            T value = null;
            Exception error = null;
            try {
                value = job.run();
            } catch (Exception e) {
                Log.e(TAG, "save transfer failed", e);
                error = e;
            }
            final T v = value;
            final Exception err = error;
            activity.runOnUiThread(() -> {
                preloader.close();
                if (activity.isFinishing()) return;
                if (err != null) {
                    Toast.makeText(activity, activity.getString(R.string.save_transfer_failed, String.valueOf(err.getMessage())), Toast.LENGTH_LONG).show();
                    return;
                }
                done.accept(v);
            });
        });
    }

    // ==================== Export everything / 전체 내보내기 ====================

    /** Settings > Export saves / 설정 > 세이브 내보내기 */
    public static void exportAll(Activity activity) {
        String[] labels = {
                activity.getString(R.string.save_target_gamenative),
                activity.getString(R.string.save_target_gamehub),
                activity.getString(R.string.save_target_winlator)
        };
        new AlertDialog.Builder(activity)
                .setTitle(R.string.save_transfer_export)
                .setItems(labels, (d, which) -> {
                    if (which == 0) exportAllGameNative(activity);
                    else exportAllDriveC(activity, which == 1 ? SaveTransfer.Format.GAMEHUB : SaveTransfer.Format.WINLATOR);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void exportAllDriveC(Activity activity, SaveTransfer.Format format) {
        Context app = activity.getApplicationContext();
        run(activity, () -> SaveTransferIO.planAll(app, format), plan -> {
            if (plan.files.isEmpty()) {
                Toast.makeText(activity, R.string.save_backup_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            File out = new File(AppDirs.downloads("Saves"), "saves-" + format.name().toLowerCase(Locale.ENGLISH) + "-" + stamp() + ".zip");
            confirmSize(activity, plan.files.size(), plan.bytes, () ->
                    run(activity, () -> SaveTransferIO.write(plan, out), count -> doneToast(activity, out, count)));
        });
    }

    // One zip per Steam game (GameNative reads one game per archive)
    // 스팀 게임마다 zip 하나(GameNative는 묶음 하나에 게임 하나를 읽음)
    private static void exportAllGameNative(Activity activity) {
        Context app = activity.getApplicationContext();
        if (!new SteamAccountStore(app).isLoggedIn()) {
            Toast.makeText(activity, R.string.save_gamenative_sign_in, Toast.LENGTH_LONG).show();
            return;
        }
        run(activity, () -> {
            Map<Integer, Shortcut> games = new LinkedHashMap<>();
            for (Shortcut s : new ContainerManager(app).loadShortcuts()) {
                int id = parseInt(s.getExtra("steamAppId"));
                if (id > 0 && !games.containsKey(id)) games.put(id, s);
            }
            List<SaveTransferIO.Plan> plans = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            if (games.isEmpty()) return new Object[]{plans, ids};
            SteamSession session = steamSession(app);
            for (Map.Entry<Integer, Shortcut> g : games.entrySet()) {
                try {
                    SaveTransferIO.Plan p = SaveTransferIO.planGameNative(app, session, g.getValue(), g.getKey());
                    if (p != null) { plans.add(p); ids.add(g.getKey()); }
                } catch (Exception e) {
                    Log.w(TAG, "GameNative plan failed for " + g.getKey(), e);
                }
            }
            return new Object[]{plans, ids};
        }, result -> {
            @SuppressWarnings("unchecked") List<SaveTransferIO.Plan> plans = (List<SaveTransferIO.Plan>) result[0];
            @SuppressWarnings("unchecked") List<Integer> ids = (List<Integer>) result[1];
            if (plans.isEmpty()) {
                Toast.makeText(activity, R.string.save_gamenative_none, Toast.LENGTH_LONG).show();
                return;
            }
            int files = 0;
            long bytes = 0;
            for (SaveTransferIO.Plan p : plans) { files += p.files.size(); bytes += p.bytes; }
            File dir = new File(AppDirs.downloads("Saves"), "GameNative");
            String when = stamp();
            confirmSize(activity, files, bytes, () -> run(activity, () -> {
                int n = 0;
                for (int i = 0; i < plans.size(); i++) {
                    SaveTransferIO.Plan p = plans.get(i);
                    n += SaveTransferIO.write(p, new File(dir, safeName(p.name) + "-" + ids.get(i) + "-" + when + ".zip"));
                }
                return n;
            }, count -> doneToast(activity, dir, count)));
        });
    }

    // ==================== Export one game / 게임 하나 추출 ====================

    /** Long-press menu "Export saves" of a shortcut / 바로가기 길게 누르기 메뉴 "세이브 추출" */
    public static void exportGame(Activity activity, Shortcut shortcut) {
        final int appId = parseInt(shortcut.getExtra("steamAppId"));
        List<String> labels = new ArrayList<>();
        List<SaveTransfer.Format> formats = new ArrayList<>();
        // GameNative needs a Steam AppID / GameNative는 Steam AppID가 있어야 함
        if (appId > 0) { labels.add(activity.getString(R.string.save_target_gamenative)); formats.add(SaveTransfer.Format.GAMENATIVE); }
        labels.add(activity.getString(R.string.save_target_gamehub)); formats.add(SaveTransfer.Format.GAMEHUB);
        labels.add(activity.getString(R.string.save_target_winlator)); formats.add(SaveTransfer.Format.WINLATOR);
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.save_export_game_title, shortcut.name))
                .setItems(labels.toArray(new String[0]), (d, which) -> exportGameAs(activity, shortcut, appId, formats.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void exportGameAs(Activity activity, Shortcut shortcut, int appId, SaveTransfer.Format format) {
        Context app = activity.getApplicationContext();
        if (format == SaveTransfer.Format.GAMENATIVE && !new SteamAccountStore(app).isLoggedIn()) {
            Toast.makeText(activity, R.string.save_gamenative_sign_in, Toast.LENGTH_LONG).show();
            return;
        }
        run(activity, () -> {
            if (format != SaveTransfer.Format.GAMENATIVE) return SaveTransferIO.planGame(app, shortcut, format);
            return SaveTransferIO.planGameNative(app, steamSession(app), shortcut, appId);
        }, plan -> {
            if (plan == null || plan.files.isEmpty()) {
                Toast.makeText(activity, R.string.save_export_none, Toast.LENGTH_LONG).show();
                return;
            }
            String suffix = format == SaveTransfer.Format.GAMENATIVE ? "gamenative-" + appId : format.name().toLowerCase(Locale.ENGLISH);
            File out = new File(AppDirs.downloads("Saves"), safeName(shortcut.name) + "-" + suffix + "-" + stamp() + ".zip");
            confirmSize(activity, plan.files.size(), plan.bytes, () ->
                    run(activity, () -> SaveTransferIO.write(plan, out), count -> doneToast(activity, out, count)));
        });
    }

    // ==================== Import / 가져오기 ====================

    /** After the file was picked (Settings > Import saves) / 파일을 고른 뒤(설정 > 세이브 가져오기) */
    public static void importPicked(Activity activity, Uri uri) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.save_transfer_import)
                .setMessage(R.string.save_import_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Context app = activity.getApplicationContext();
                    run(activity, () -> SaveTransferIO.importArchive(app, uri), r -> {
                        String msg = activity.getString(R.string.save_import_done, r.kind, r.written, r.skipped);
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ==================== Helpers / 보조 ====================

    private static void confirmSize(Activity activity, int files, long bytes, Runnable go) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.save_transfer_export)
                .setMessage(activity.getString(R.string.save_export_size, files, SaveTransfer.sizeText(bytes)))
                .setPositiveButton(R.string.save_export_go, (d, w) -> go.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void doneToast(Activity activity, File where, int count) {
        Toast.makeText(activity, activity.getString(R.string.save_export_written, count, where.getAbsolutePath()), Toast.LENGTH_LONG).show();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH).format(new Date());
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "game" : n;
    }

    private static int parseInt(String s) {
        try {
            return s != null && !s.isEmpty() ? Integer.parseInt(s.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // The app's shared Steam session (SteamFragment.sharedSession). A second
    // session of the same account made Steam log the shared one off, and the
    // next Steam request of the app (size check before a download) failed
    // 앱이 함께 쓰는 스팀 세션(SteamFragment.sharedSession). 같은 계정으로 세션을 하나
    // 더 열면 스팀이 공용 세션을 로그오프해서, 그다음 앱의 스팀 요청(다운로드 전 크기
    // 확인)이 실패했음
    private static SteamSession steamSession(Context app) {
        SteamSession session = com.winlator.cmod.steam.SteamFragment.sharedSession(app);
        if (session == null) throw new IllegalStateException("Steam sign-in failed");
        return session;
    }
}
