package com.winlator.cmod.steam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.TrafficStats;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.core.FileUtils;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background Steam download. Runs as a foreground service in its own
 * process (":download", see AndroidManifest), so it keeps going while the app
 * is in the background or a game runs - a game exit ends the main app process,
 * but not this one. Progress goes to a notification and to SteamDownloadState.
 * Setting "pause downloads while a game runs" (on by default): the download
 * pauses when a game starts and resumes by itself when the game ends. The
 * depot downloader offers no byte-rate limit, so pausing is the limit that
 * actually frees bandwidth and CPU for the game.
 * 백그라운드 스팀 다운로드임. 자체 프로세스(":download", AndroidManifest 참고)의
 * 포그라운드 서비스로 돌아서, 앱이 뒤로 가거나 게임이 실행 중이어도 계속됨 - 게임
 * 종료는 메인 앱 프로세스를 끝내지만 이 프로세스는 끝내지 않음. 진행 상황은 알림과
 * SteamDownloadState로 내보냄.
 * 설정 "게임 실행 중 다운로드 일시정지"(기본 켜짐): 게임이 시작되면 다운로드가
 * 멈추고, 게임이 끝나면 저절로 이어 받음. depot 다운로더에는 속도 제한 기능이 없어서,
 * 게임에 대역폭과 CPU를 실제로 비워 주는 제한 방법은 일시정지임
 */
public class SteamDownloadService extends Service {
    private static final String TAG = "SteamDownloadService";
    public static final String ACTION_START = "com.ezlator.steam.DL_START";
    public static final String ACTION_PAUSE = "com.ezlator.steam.DL_PAUSE";
    public static final String ACTION_RESUME = "com.ezlator.steam.DL_RESUME";
    public static final String ACTION_STOP = "com.ezlator.steam.DL_STOP";
    public static final String EXTRA_APP_ID = "appId";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_IS_UPDATE = "isUpdate";
    public static final String PREF_PAUSE_DURING_GAME = "steam_dl_pause_during_game";
    private static final String CHANNEL_ID = "steam_download";
    private static final int NOTIFICATION_ID = 4901;

    // Same Dispatchers.IO cap as MainActivity (this process never loads it)
    // MainActivity와 같은 Dispatchers.IO 상한임(이 프로세스는 MainActivity를 안 불러옴)
    static {
        if (System.getProperty("kotlinx.coroutines.io.parallelism") == null) {
            System.setProperty("kotlinx.coroutines.io.parallelism", "16");
        }
    }

    private final SteamDownloadState state = SteamDownloadState.create();
    private volatile AtomicBoolean cancel;
    private volatile boolean stopRequested;
    private volatile Thread worker;
    private SteamSession session;
    private volatile boolean alive = true;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(R.string.steam_dl_channel), NotificationManager.IMPORTANCE_LOW));
        }
        // A download left by a previous service process (swiped away, killed)
        // comes back as paused, so Resume / Stop still work on it
        // 이전 서비스 프로세스가 남긴 다운로드(밀어서 종료, 강제 종료)는 일시정지
        // 상태로 되살려서 재개 / 정지가 그대로 동작하게 함
        SteamDownloadState saved = SteamDownloadState.read(this);
        if (saved.isActive()) {
            state.appId = saved.appId;
            state.name = saved.name;
            state.percent = saved.percent;
            state.installDir = saved.installDir;
            state.isUpdate = saved.isUpdate;
            state.paused = !saved.waitingForGame;
            state.waitingForGame = saved.waitingForGame;
            state.running = false;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        // Ticker: speed, state file, notification, game watcher (every 500ms)
        // 주기 작업: 속도, 상태 파일, 알림, 게임 감시(500ms마다)
        new Thread(this::tickLoop, "SteamDlTicker").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            int appId = intent.getIntExtra(EXTRA_APP_ID, 0);
            if (appId != 0 && !state.running) {
                state.appId = appId;
                state.name = intent.getStringExtra(EXTRA_NAME);
                state.isUpdate = intent.getBooleanExtra(EXTRA_IS_UPDATE, false);
                state.percent = 0;
                state.result = "";
                state.paused = false;
                state.waitingForGame = false;
                startWorker();
            }
        } else if (ACTION_PAUSE.equals(action)) {
            state.paused = true;
            if (state.running) haltProcess();
        } else if (ACTION_RESUME.equals(action)) {
            state.paused = false;
            state.waitingForGame = false;
            if (!state.running && state.appId != 0) startWorker();
        } else if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            if (state.running) {
                // Stop: forget the download, leave the folder to the UI process
                // 정지: 다운로드를 지우고, 폴더 삭제는 UI 프로세스에 맡김
                state.deletePending = state.isUpdate ? "" : state.installDir;
                state.appId = 0;
                state.result = getString(R.string.steam_dl_stopped);
                haltProcess();
            } else {
                deleteInstallDirAndFinish();
            }
        }
        return START_NOT_STICKY;
    }

    private void startWorker() {
        stopRequested = false;
        cancel = new AtomicBoolean(false);
        final AtomicBoolean myCancel = cancel;
        state.running = true;
        state.status = getString(R.string.steam_preparing, state.name);
        state.write(this);
        worker = new Thread(() -> {
            String result = null;
            try {
                if (session == null) {
                    SteamSession s = new SteamSession();
                    s.open(new SteamAccountStore(this));
                    session = s;
                }
                String shortcut = SteamGameInstaller.install(getApplicationContext(), session, state.appId,
                        new SteamGameInstaller.Progress() {
                            @Override public void onStatus(String text) { state.status = text; }
                            @Override public void onPercent(int p) { state.percent = p; }
                            @Override public void onInstallDir(File dir) { state.installDir = dir.getAbsolutePath(); }
                        }, myCancel);
                // Visual C++ 2015-2022: read the Microsoft installers that came with
                // the game (or Steamworks Shared) and keep the newest DLLs in imagefs
                // Visual C++ 2015~2022: 게임(또는 Steamworks Shared)과 함께 받은
                // Microsoft 설치 파일을 읽어 가장 새 DLL을 imagefs에 보관함
                state.status = getString(R.string.vcredist_reading, "vc_redist");
                state.write(this);
                com.winlator.cmod.core.VCRedistInstaller.harvestAfterSteamDownload(getApplicationContext());
                result = getString(R.string.steam_download_done, shortcut);
                state.appId = 0;
            } catch (SteamGameInstaller.CancelledException ce) {
                if (stopRequested) {
                    deleteInstallDir();
                    state.appId = 0;
                    result = getString(R.string.steam_dl_stopped);
                }
                // otherwise paused (by the user or because a game started)
                // 아니면 일시정지임(사용자가 했거나 게임이 시작돼서)
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                // Token refused: tell the user to sign in again (SteamSession already
                // left the mark, so the Steam page switches to the sign-in form)
                // 토큰 거부: 다시 로그인하라고 알림(SteamSession이 이미 표시를 남겨서
                // 스팀 화면은 로그인 양식으로 바뀜)
                String why = e instanceof SteamSession.TokenRejectedException
                        ? getString(R.string.steam_token_expired) : String.valueOf(e.getMessage());
                result = getString(R.string.steam_download_failed, why);
                session = null;
                state.appId = 0;
            } finally {
                state.running = false;
                if (result != null) state.result = result;
                state.write(this);
            }
            if (state.appId == 0) finishService();
        }, "SteamDlWorker");
        worker.start();
    }

    // An update keeps the installed game; only a new download is removed
    // 업데이트는 설치된 게임을 남김. 새 다운로드만 지움
    // Pause / game / Stop end this whole process. Closing the depot
    // downloader did not stop it: its workers kept downloading after cancel,
    // which is why the download went on while a game ran. Files already
    // written stay; the next start re-validates them and fetches the rest.
    // The notification is detached first so it survives with the paused text
    // 일시정지 / 게임 / 정지는 이 프로세스 전체를 끝냄. depot 다운로더는
    // close해도 멈추지 않았음: 취소 뒤에도 작업 스레드가 계속 받아서, 게임 중에도
    // 다운로드가 이어졌던 이유임. 이미 쓴 파일은 남고, 다음 시작 때 다시 검증한 뒤
    // 나머지를 받음. 알림은 먼저 분리해서 일시정지 문구로 남게 함
    private void haltProcess() {
        alive = false;
        state.running = false;
        state.bytesPerSecond = 0;
        state.write(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        stopForeground(Service.STOP_FOREGROUND_DETACH);
        if (state.appId != 0) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        } else {
            nm.cancel(NOTIFICATION_ID);
            if (state.result != null && !state.result.isEmpty()) {
                nm.notify(NOTIFICATION_ID + 1, baseBuilder().setContentText(state.result).setAutoCancel(true).build());
            }
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void deleteInstallDir() {
        if (state.isUpdate) return;
        if (state.installDir != null && !state.installDir.isEmpty()) FileUtils.delete(new File(state.installDir));
    }

    private void deleteInstallDirAndFinish() {
        new Thread(() -> {
            deleteInstallDir();
            state.appId = 0;
            state.result = getString(R.string.steam_dl_stopped);
            state.write(this);
            finishService();
        }).start();
    }

    // Leaves a final notification with the result, then ends the service
    // 결과를 담은 마지막 알림을 남기고 서비스를 끝냄
    private void finishService() {
        alive = false;
        NotificationManager nm = getSystemService(NotificationManager.class);
        stopForeground(true);
        if (state.result != null && !state.result.isEmpty()) {
            nm.notify(NOTIFICATION_ID + 1, baseBuilder().setContentText(state.result)
                    .setOngoing(false).setAutoCancel(true).build());
        }
        stopSelf();
        // The ":download" process is ended too. Reported: the
        // tablet stayed hot after every download until a reboot. stopSelf()
        // only stops the service; the process (with the depot downloader's
        // worker threads and the Steam connection) could stay alive in the
        // background. Nothing else runs in this process, so ending it is safe
        // ":download" 프로세스도 끝냄. 보고: 다운로드할 때마다 재부팅 전까지
        // 태블릿이 계속 뜨거웠음. stopSelf()는 서비스만 멈추고, 프로세스(depot 다운로더
        // 작업 스레드와 스팀 연결 포함)는 백그라운드에 남을 수 있었음. 이 프로세스에서는
        // 다른 것이 돌지 않으므로 끝내도 안전함
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void tickLoop() {
        long lastRx = -1, lastTime = 0;
        while (alive) {
            long now = SystemClock.elapsedRealtime();
            long rx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
            if (state.running && lastRx >= 0 && now > lastTime && rx >= lastRx) {
                // Smoothed (exponential average): chunks arrive in bursts, so the
                // raw half-second value kept dropping to 0 B/s
                // 지수 평균으로 다듬음: 청크가 몰려서 들어와서, 0.5초 단위 값이 계속
                // 0 B/s로 떨어졌음
                long instant = (rx - lastRx) * 1000L / (now - lastTime);
                state.bytesPerSecond = state.bytesPerSecond == 0 ? instant
                        : (long) (state.bytesPerSecond * 0.8 + instant * 0.2);
            } else if (!state.running) {
                state.bytesPerSecond = 0;
            }
            lastRx = rx;
            lastTime = now;

            // Pause while a game runs, resume after it ends (setting, default on)
            // 게임 중에는 일시정지, 끝나면 이어 받기(설정, 기본 켜짐)
            boolean pauseDuringGame = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(PREF_PAUSE_DURING_GAME, true);
            boolean gameRunning = pauseDuringGame && SteamDownloadState.isGameRunning(this);
            if (gameRunning && state.running && !state.waitingForGame) {
                state.waitingForGame = true;
                haltProcess();
            } else if (!gameRunning && state.waitingForGame && !state.running && !state.paused && state.appId != 0) {
                state.waitingForGame = false;
                startWorker();
            }

            state.write(this);
            if (alive) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
            SystemClock.sleep(500);
        }
    }

    private Notification.Builder baseBuilder() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.icon_steam_library)
                .setContentTitle(state.name != null && !state.name.isEmpty() ? state.name : getString(R.string.steam_account))
                .setContentIntent(pi)
                .setOnlyAlertOnce(true);
    }

    private Notification buildNotification() {
        // Ongoing only while the download really runs (a paused one can be
        // swiped away)
        // 실제로 받는 동안만 고정 알림임(일시정지된 알림은 밀어서 지울 수 있음)
        Notification.Builder b = baseBuilder().setOngoing(state.running);
        if (state.waitingForGame) {
            b.setContentText(getString(R.string.steam_dl_waiting_game, state.percent));
        } else if (!state.running) {
            b.setContentText(getString(R.string.steam_dl_paused, state.percent));
        } else {
            String speed = android.text.format.Formatter.formatShortFileSize(this, state.bytesPerSecond);
            b.setContentText(getString(R.string.steam_dl_info, state.percent, speed));
            b.setProgress(100, state.percent, state.percent == 0);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        alive = false;
        if (cancel != null) cancel.set(true);
        super.onDestroy();
        // Same reason as finishService(): no leftover worker threads
        // finishService()와 같은 이유임: 작업 스레드가 남지 않게 함
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
