package com.winlator.cmod.steam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.container.SaveTransfer;
import com.winlator.cmod.core.PreloaderDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Room check before a new Steam download. The size the game needs is worked
 * out first (Steam app info, depot sizes); only when the chosen library
 * (internal storage, or the SD card when picked in Settings) is short of space
 * the user is asked, with three equal buttons from left to right:
 *   Close   - nothing happens
 *   Delete  - pick installed games of that library to delete, then download
 *   Move    - pick installed games to move to the other library (internal <->
 *             SD card, both EZlator folders), then download; or install this
 *             game on the other library instead
 * When the size is unknown (no app info) the download simply starts.
 * The same values (game size, internal / SD card free and total, target
 * storage) fill the info block of the download confirm sheet (readInfo, fillInfo).
 * 새 스팀 다운로드 전의 공간 확인. 먼저 게임에 필요한 크기를 계산하고(스팀 앱 정보의
 * depot 크기), 고른 라이브러리(내장 메모리, 또는 설정에서 고르면 SD카드)에 공간이
 * 모자랄 때만 사용자에게 물음. 버튼은 같은 폭으로 왼쪽부터:
 *   닫기     - 아무것도 안 함
 *   삭제하기 - 그 라이브러리의 설치된 게임을 골라 지운 뒤 다운로드
 *   옮기기   - 설치된 게임을 골라 다른 라이브러리(내장 <-> SD카드, 둘 다 EZlator
 *             폴더)로 옮긴 뒤 다운로드. 또는 이 게임을 다른 라이브러리에 설치
 * 크기를 모르면(앱 정보 없음) 그냥 다운로드를 시작함
 * 같은 값(게임 용량, 내장 / SD카드 남음과 전체, 설치될 저장소)으로 받기 확인창의
 * 정보 블록도 채움(readInfo, fillInfo)
 */
public final class SteamSpaceCheck {
    private static final String TAG = "SteamSpaceCheck";

    private SteamSpaceCheck() {}

    /**
     * What the download info block shows: game size, the library it goes to and
     * free / total space of internal storage and the SD card. Read off the UI
     * thread (Steam app info is a network call).
     * 다운로드 정보 블록에 보일 값: 게임 용량, 들어갈 라이브러리, 내장 메모리와
     * SD카드의 남은 / 전체 공간. UI 스레드 밖에서 읽음(스팀 앱 정보는 네트워크 호출임)
     */
    public static final class Info {
        public long needed;        // size on disk, 0 = unknown / 디스크 크기, 0 = 모름
        public long download;      // download size, 0 = unknown / 다운로드 크기, 0 = 모름
        public boolean resume;     // unfinished download goes on in place / 끝나지 않은 다운로드를 그 자리에서 이어감
        public File library;       // library the game goes to / 게임이 들어갈 라이브러리
        public long internalFree, internalTotal;
        public long sdFree = -1, sdTotal = -1;   // -1 = no SD card / -1 = SD카드 없음

        // Room the download still needs (none when it resumes in place)
        // 다운로드에 새로 필요한 공간(그 자리에서 이어 받으면 없음)
        public long need() { return resume ? 0 : needed; }
    }

    /** Reads the info block values; call off the UI thread / 정보 블록 값을 읽음. UI 스레드 밖에서 호출 */
    public static Info readInfo(Context context, int appId) {
        Context app = context.getApplicationContext();
        Info info = new Info();
        info.library = SteamGameInstaller.chooseLibrary(app);
        try {
            SteamSession session = SteamFragment.sharedSession(app);
            if (session != null) {
                SteamSession.AppDetails d = session.getAppDetails(appId);
                info.needed = d.sizeBytes;
                info.download = d.downloadBytes;
                // An unfinished download goes on where it is (no new room needed)
                // 끝나지 않은 다운로드는 있던 곳에서 이어감(새 공간이 필요 없음)
                File partial = SteamGameInstaller.partialLibrary(app, d.installDir);
                if (partial != null) { info.library = partial; info.resume = true; }
            }
        } catch (Exception e) {
            Log.w(TAG, "size check failed, download starts without it", e);
        }
        File internal = SteamGameInstaller.getLibraryDir(app);
        info.internalFree = SteamGameInstaller.usableSpace(internal);
        info.internalTotal = SteamGameInstaller.totalSpace(internal);
        File sd = SteamGameInstaller.getSdLibraryDir(app);
        if (sd != null) {
            info.sdFree = SteamGameInstaller.usableSpace(sd);
            info.sdTotal = SteamGameInstaller.totalSpace(sd);
        }
        return info;
    }

    /** Fills a steam_download_info block (UI thread) / steam_download_info 블록을 채움(UI 스레드) */
    public static void fillInfo(View block, Info info) {
        if (block == null || info == null) return;
        Context c = block.getContext();
        // Size on disk (what the space check uses), plus the download size when
        // it is smaller (compressed)
        // 디스크 크기(공간 확인에 쓰는 값). 다운로드 크기가 더 작으면(압축) 함께 보임
        String size;
        if (info.needed <= 0) size = c.getString(R.string.steam_dl_info_unknown);
        else if (info.download > 0 && info.download < info.needed) {
            size = c.getString(R.string.steam_dl_info_size_dl, SaveTransfer.sizeText(info.needed), SaveTransfer.sizeText(info.download));
        } else size = SaveTransfer.sizeText(info.needed);
        if (info.resume) size = c.getString(R.string.steam_dl_info_resume, size);
        ((TextView) block.findViewById(R.id.TVInfoSize)).setText(size);
        ((TextView) block.findViewById(R.id.TVInfoInternal)).setText(spaceText(c, info.internalFree, info.internalTotal));
        ((TextView) block.findViewById(R.id.TVInfoSd)).setText(info.sdTotal < 0
                ? c.getString(R.string.steam_dl_info_none) : spaceText(c, info.sdFree, info.sdTotal));
        ((TextView) block.findViewById(R.id.TVInfoTarget)).setText(storageName(c, info.library));
    }

    // "free / total" / "남음 / 전체"
    private static String spaceText(Context c, long free, long total) {
        return c.getString(R.string.steam_dl_info_space, SaveTransfer.sizeText(free), SaveTransfer.sizeText(total));
    }

    /** Checks room, then starts the download (or asks) / 공간을 확인한 뒤 다운로드를 시작함(또는 물음) */
    public static void startDownload(Activity activity, int appId, String name, Runnable afterStart) {
        Context app = activity.getApplicationContext();
        PreloaderDialog preloader = new PreloaderDialog(activity);
        preloader.showOnUiThread(R.string.steam_space_checking);
        Executors.newSingleThreadExecutor().execute(() -> {
            final Info info = readInfo(app, appId);
            final File lib = info.library;
            final long need = info.need();
            final long free = SteamGameInstaller.usableSpace(lib);
            final long missing = SteamLibraryPicker.shortfall(free, need);
            Log.i(TAG, "app " + appId + ": needed=" + need + " free=" + free + " missing=" + missing + " in " + lib);
            activity.runOnUiThread(() -> {
                preloader.close();
                if (activity.isFinishing()) return;
                if (missing == 0) {
                    SteamDownloadState.start(app, appId, name, false, lib);
                    if (afterStart != null) afterStart.run();
                } else {
                    showShortDialog(activity, appId, name, info, free, missing, afterStart);
                }
            });
        });
    }

    private static void showShortDialog(Activity activity, int appId, String name, Info info,
                                        long free, long missing, Runnable afterStart) {
        final File lib = info.library;
        View view = LayoutInflater.from(activity).inflate(R.layout.steam_space_dialog, null);
        // Size and both storages first, then what is short
        // 용량과 두 저장소를 먼저 보이고, 그다음 모자란 양을 알림
        fillInfo(view.findViewById(R.id.IncDownloadInfo), info);
        ((TextView) view.findViewById(R.id.TVSpaceMessage)).setText(activity.getString(R.string.steam_space_message,
                name, SaveTransfer.sizeText(info.need()), storageName(activity, lib), SaveTransfer.sizeText(free),
                SaveTransfer.sizeText(missing)));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.steam_space_title)
                .setView(view)
                .create();
        view.findViewById(R.id.BTSpaceClose).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.BTSpaceDelete).setOnClickListener(v -> {
            dialog.dismiss();
            pickGames(activity, appId, name, lib, missing, false, afterStart);
        });
        view.findViewById(R.id.BTSpaceMove).setOnClickListener(v -> {
            if (SteamGameInstaller.otherLibrary(activity, lib) == null) {
                Toast.makeText(activity, R.string.steam_library_sd_missing, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            pickGames(activity, appId, name, lib, missing, true, afterStart);
        });
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(R.drawable.neon_popup_bg);
        dialog.show();
    }

    // Installed games of lib (biggest first) to delete or move
    // lib의 설치된 게임(큰 것부터) 중 지우거나 옮길 것을 고름
    private static void pickGames(Activity activity, int appId, String name, File lib, long missing,
                                  boolean move, Runnable afterStart) {
        Context app = activity.getApplicationContext();
        PreloaderDialog preloader = new PreloaderDialog(activity);
        preloader.showOnUiThread(R.string.steam_space_reading);
        Executors.newSingleThreadExecutor().execute(() -> {
            List<SteamGameInstaller.InstalledGame> games = new ArrayList<>();
            for (SteamGameInstaller.InstalledGame g : SteamGameInstaller.installedGames(lib)) {
                if (g.appId != appId) games.add(g);
            }
            activity.runOnUiThread(() -> {
                preloader.close();
                if (activity.isFinishing()) return;
                File other = SteamGameInstaller.otherLibrary(app, lib);
                if (games.isEmpty() && !(move && other != null)) {
                    Toast.makeText(activity, R.string.steam_space_no_games, Toast.LENGTH_LONG).show();
                    return;
                }
                String[] items = new String[games.size()];
                for (int i = 0; i < items.length; i++) {
                    items[i] = games.get(i).name + "  (" + SaveTransfer.sizeText(games.get(i).bytes) + ")";
                }
                boolean[] checked = new boolean[items.length];
                AlertDialog.Builder b = new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(move ? R.string.steam_space_pick_move : R.string.steam_space_pick_delete,
                                SaveTransfer.sizeText(missing)))
                        .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                        .setNegativeButton(R.string.steam_space_close, null)
                        .setPositiveButton(move ? R.string.steam_space_move : R.string.steam_space_delete, (d, w) -> {
                            List<SteamGameInstaller.InstalledGame> picked = new ArrayList<>();
                            long sum = 0;
                            for (int i = 0; i < checked.length; i++) {
                                if (checked[i]) { picked.add(games.get(i)); sum += games.get(i).bytes; }
                            }
                            if (picked.isEmpty()) return;
                            // The pick has to free enough; otherwise the list opens again
                            // 고른 게임으로 충분히 비워야 함. 아니면 목록을 다시 엶
                            if (sum < missing) {
                                Toast.makeText(activity, activity.getString(R.string.steam_space_not_enough,
                                        SaveTransfer.sizeText(missing - sum)), Toast.LENGTH_LONG).show();
                                pickGames(activity, appId, name, lib, missing, move, afterStart);
                                return;
                            }
                            if (move) moveGames(activity, picked, other, sum, () -> startDownload(activity, appId, name, afterStart));
                            else confirmDelete(activity, picked, sum, () -> startDownload(activity, appId, name, afterStart));
                        });
                // Move only: this game can go to the other library instead
                // 옮기기만: 대신 이 게임을 다른 라이브러리에 넣을 수 있음
                if (move && other != null) {
                    b.setNeutralButton(activity.getString(R.string.steam_space_install_other, storageName(activity, other)), (d, w) ->
                            installOnOther(activity, appId, name, other, afterStart));
                }
                b.show();
            });
        });
    }

    private static void confirmDelete(Activity activity, List<SteamGameInstaller.InstalledGame> picked, long sum, Runnable then) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.steam_space_delete)
                .setMessage(activity.getString(R.string.steam_space_delete_confirm, picked.size(), SaveTransfer.sizeText(sum)))
                .setNegativeButton(R.string.steam_space_close, null)
                .setPositiveButton(R.string.steam_space_delete, (d, w) -> {
                    Context app = activity.getApplicationContext();
                    PreloaderDialog preloader = new PreloaderDialog(activity);
                    preloader.showOnUiThread(R.string.steam_space_working);
                    Executors.newSingleThreadExecutor().execute(() -> {
                        String error = null;
                        for (SteamGameInstaller.InstalledGame g : picked) {
                            try {
                                activity.runOnUiThread(() -> preloader.setMessage(app.getString(R.string.steam_space_deleting, g.name)));
                                SteamGameInstaller.uninstall(app, g.appId);
                            } catch (Exception e) {
                                Log.w(TAG, "delete failed: " + g.appId, e);
                                error = g.name + ": " + e.getMessage();
                            }
                        }
                        final String err = error;
                        activity.runOnUiThread(() -> {
                            preloader.close();
                            if (err != null) Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                            then.run();
                        });
                    });
                })
                .show();
    }

    private static void moveGames(Activity activity, List<SteamGameInstaller.InstalledGame> picked, File to, long sum, Runnable then) {
        Context app = activity.getApplicationContext();
        long room = SteamGameInstaller.usableSpace(to);
        if (SteamLibraryPicker.shortfall(room, sum) > 0) {
            Toast.makeText(activity, activity.getString(R.string.steam_space_other_full, storageName(activity, to),
                    SaveTransfer.sizeText(room)), Toast.LENGTH_LONG).show();
            return;
        }
        PreloaderDialog preloader = new PreloaderDialog(activity);
        preloader.showOnUiThread(R.string.steam_space_working);
        Executors.newSingleThreadExecutor().execute(() -> {
            String error = null;
            for (SteamGameInstaller.InstalledGame g : picked) {
                try {
                    final long[] lastShown = {-1};
                    SteamGameInstaller.moveGame(app, g.appId, to, (done, total) -> {
                        long pct = total > 0 ? done * 100 / total : 0;
                        if (pct == lastShown[0]) return;
                        lastShown[0] = pct;
                        activity.runOnUiThread(() -> preloader.setMessage(app.getString(R.string.steam_space_moving, g.name, (int) pct)));
                    });
                } catch (Exception e) {
                    Log.w(TAG, "move failed: " + g.appId, e);
                    error = g.name + ": " + e.getMessage();
                    break;
                }
            }
            final String err = error;
            activity.runOnUiThread(() -> {
                preloader.close();
                if (err != null) {
                    Toast.makeText(activity, activity.getString(R.string.steam_space_move_failed, err), Toast.LENGTH_LONG).show();
                    return;
                }
                then.run();
            });
        });
    }

    // This game on the other library, if it fits there
    // 이 게임을 다른 라이브러리에 넣음(들어갈 때만)
    private static void installOnOther(Activity activity, int appId, String name, File other, Runnable afterStart) {
        Context app = activity.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            long needed = 0;
            try {
                SteamSession session = SteamFragment.sharedSession(app);
                if (session != null) needed = session.getAppDetails(appId).sizeBytes;
            } catch (Exception ignored) {}
            final long free = SteamGameInstaller.usableSpace(other);
            final long missing = SteamLibraryPicker.shortfall(free, needed);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (missing > 0) {
                    Toast.makeText(activity, activity.getString(R.string.steam_space_other_full, storageName(activity, other),
                            SaveTransfer.sizeText(free)), Toast.LENGTH_LONG).show();
                    return;
                }
                SteamDownloadState.start(app, appId, name, false, other);
                if (afterStart != null) afterStart.run();
            });
        });
    }

    private static String storageName(Context context, File library) {
        return context.getString(SteamGameInstaller.isSdLibrary(context, library)
                ? R.string.steam_storage_sd : R.string.steam_storage_internal);
    }
}
