package com.winlator.cmod.steam;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.File;

// Download state shared between the UI process and the download service.
// The service runs in its own process (":download") so that a
// game exit - which ends the main app process - does not kill a download;
// static fields cannot cross processes, so the state is a small JSON file
// that the service rewrites twice a second and the UI reads.
// UI 프로세스와 다운로드 서비스가 함께 쓰는 다운로드 상태임. 서비스는 별도
// 프로세스(":download")에서 돌아서, 게임 종료(메인 앱 프로세스를 끝냄)가 다운로드를
// 죽이지 않음. static 필드는 프로세스를 넘지 못하므로, 상태는 서비스가 1초에 두 번
// 다시 쓰고 UI가 읽는 작은 JSON 파일임
public final class SteamDownloadState {
    public int appId = 0;
    public String name = "";
    public boolean running = false;     // download thread active / 다운로드 스레드 동작 중
    public boolean paused = false;      // paused by the user / 사용자가 일시정지함
    public boolean waitingForGame = false; // auto-paused while a game runs / 게임 중 자동 일시정지
    public int percent = 0;
    public long bytesPerSecond = 0;
    public String status = "";
    public String result = "";          // last finished/failed message / 마지막 완료·실패 문구
    public String installDir = "";
    // Update of an installed game: Stop must not delete the game folder
    // 설치된 게임의 업데이트임: 정지가 게임 폴더를 지우면 안 됨
    public boolean isUpdate = false;
    // Folder to delete after a Stop. The service ends its own process to stop
    // the downloader, so the UI process deletes it
    // 정지 뒤에 지울 폴더임. 서비스는 다운로더를 멈추려고 자기 프로세스를 끝내므로,
    // 삭제는 UI 프로세스가 함
    public String deletePending = "";
    // Wall-clock time of the last write; a "running" state older than a few
    // seconds means the service process is gone (treated as paused)
    // 마지막으로 쓴 시각임. 몇 초 넘게 지난 "실행 중" 상태는 서비스 프로세스가
    // 사라졌다는 뜻이라 일시정지로 취급함
    public long updatedAt = 0;

    public boolean isStale() {
        return System.currentTimeMillis() - updatedAt > 3000;
    }

    private SteamDownloadState() {}

    public boolean isActive() {
        return appId != 0;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "steam_download/state.json");
    }

    public static SteamDownloadState read(Context context) {
        SteamDownloadState s = new SteamDownloadState();
        File f = file(context);
        if (!f.isFile()) return s;
        try {
            JSONObject o = new JSONObject(FileUtils.readString(f));
            s.appId = o.optInt("appId");
            s.name = o.optString("name");
            s.running = o.optBoolean("running");
            s.paused = o.optBoolean("paused");
            s.waitingForGame = o.optBoolean("waitingForGame");
            s.percent = o.optInt("percent");
            s.bytesPerSecond = o.optLong("bps");
            s.status = o.optString("status");
            s.result = o.optString("result");
            s.installDir = o.optString("installDir");
            s.updatedAt = o.optLong("updatedAt");
            s.isUpdate = o.optBoolean("isUpdate");
            s.deletePending = o.optString("deletePending");
            if (s.running && s.isStale()) s.running = false;
        } catch (Exception ignored) {
            // Half-written file read during a rename: treated as "no change"
            // 이름 바꾸는 순간 읽힌 파일임: "변화 없음"으로 취급함
        }
        return s;
    }

    // Written to a temp file and renamed, so a reader never sees half a file
    // 임시 파일에 쓰고 이름을 바꿔서, 읽는 쪽이 반쯤 쓰인 파일을 보지 않게 함
    void write(Context context) {
        try {
            JSONObject o = new JSONObject()
                    .put("appId", appId).put("name", name).put("running", running)
                    .put("paused", paused).put("waitingForGame", waitingForGame)
                    .put("percent", percent).put("bps", bytesPerSecond)
                    .put("status", status).put("result", result).put("installDir", installDir)
                    .put("updatedAt", System.currentTimeMillis()).put("isUpdate", isUpdate).put("deletePending", deletePending);
            File f = file(context);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            if (FileUtils.writeString(tmp, o.toString())) tmp.renameTo(f);
        } catch (Exception ignored) {}
    }

    // UI side: deletes a folder left by Stop, and resumes a download that was
    // paused because a game ran (the game exit restarts the main process, so
    // MainActivity calls this on start). Safe to call often
    // UI 쪽: 정지가 남긴 폴더를 지우고, 게임 때문에 멈춘 다운로드를 이어 받게 함
    // (게임 종료가 메인 프로세스를 다시 시작하므로 MainActivity가 시작할 때 부름).
    // 자주 불러도 안전함
    public static void housekeeping(Context context) {
        SteamDownloadState s = read(context);
        if (s.running) return;
        deletePendingIfAny(context, s);
        if (s.isActive() && s.waitingForGame && !isGameRunning(context)) {
            send(context, SteamDownloadService.ACTION_RESUME);
        }
    }

    // Deletes the folder a Stop left behind. Called from every panel refresh:
    // before, it only ran when the main screen or the panel
    // started, so the folder stayed while the user remained on the page
    // 정지가 남긴 폴더를 지움. 창을 새로 그릴 때마다 부름: 예전엔 메인 화면이나
    // 창이 시작될 때만 돌아서, 사용자가 그 페이지에 머무는 동안 폴더가 남아 있었음
    static void deletePendingIfAny(Context context, SteamDownloadState s) {
        if (s.running || s.deletePending == null || s.deletePending.isEmpty()) return;
        final File dir = new File(s.deletePending);
        s.deletePending = "";
        s.write(context);
        new Thread(() -> FileUtils.delete(dir), "SteamDlDelete").start();
    }

    static SteamDownloadState create() {
        return new SteamDownloadState();
    }

    // ---- Commands to the service / 서비스로 보내는 명령 ----

    public static void start(Context context, int appId, String name) {
        start(context, appId, name, false);
    }

    public static void start(Context context, int appId, String name, boolean isUpdate) {
        Intent i = new Intent(context, SteamDownloadService.class)
                .setAction(SteamDownloadService.ACTION_START)
                .putExtra(SteamDownloadService.EXTRA_APP_ID, appId)
                .putExtra(SteamDownloadService.EXTRA_NAME, name)
                .putExtra(SteamDownloadService.EXTRA_IS_UPDATE, isUpdate);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void send(Context context, String action) {
        Intent i = new Intent(context, SteamDownloadService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    // ---- Game-running marker (written by XServerDisplayActivity) ----
    // ---- 게임 실행 표시(XServerDisplayActivity가 씀) ----

    private static File gameMarker(Context context) {
        return new File(context.getFilesDir(), "steam_download/game_running");
    }

    public static void markGameRunning(Context context, boolean running) {
        File f = gameMarker(context);
        if (running) {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            FileUtils.writeString(f, String.valueOf(android.os.Process.myPid()));
        } else if (f.isFile()) {
            f.delete();
        }
    }

    // The game ends by killing its process, so the marker can stay behind;
    // it only counts while the pid it names is still alive
    // 게임은 프로세스를 끝내는 방식으로 종료되므로 표시 파일이 남을 수 있음.
    // 적힌 pid가 아직 살아 있을 때만 유효함
    static boolean isGameRunning(Context context) {
        File f = gameMarker(context);
        if (!f.isFile()) return false;
        try {
            int pid = Integer.parseInt(FileUtils.readString(f).trim());
            return new File("/proc/" + pid).exists();
        } catch (Exception e) {
            return false;
        }
    }
}
