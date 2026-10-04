package com.winlator.cmod.steam;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.winlator.cmod.R;

// Binds steam_download_panel.xml to SteamDownloadState. Polls the
// state file twice a second while started; buttons send commands to
// SteamDownloadService. appIdFilter = 0 shows any download (Steam page);
// a specific id shows only that game's download (shortcut detail sheet).
// steam_download_panel.xml을 SteamDownloadState에 연결함. 시작된 동안 상태
// 파일을 1초에 두 번 읽고, 버튼은 SteamDownloadService로 명령을 보냄.
// appIdFilter = 0이면 어떤 다운로드든 보여 주고(스팀 페이지), 특정 id면 그 게임의
// 다운로드만 보여 줌(숏컷 상세창)
public final class SteamDownloadPanel {
    public interface Listener {
        // A download / update just ended with this message
        // 다운로드 / 업데이트가 이 문구로 막 끝났음
        void onFinished(String result);
    }

    private final Context context;
    private final View root;
    private final TextView tvName, tvInfo;
    private final ProgressBar progress;
    private final Button btPause;
    private final int appIdFilter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private Runnable onRefreshed;

    // Called after every refresh (the host toggles its own buttons with it)
    // 새로 그릴 때마다 호출됨(호스트가 자기 버튼을 켜고 끄는 데 씀)
    public void setOnRefreshed(Runnable r) {
        this.onRefreshed = r;
    }
    private boolean wasActive = false;
    private SteamDownloadState last;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    public SteamDownloadPanel(View root, int appIdFilter) {
        this.root = root;
        this.context = root.getContext();
        this.appIdFilter = appIdFilter;
        tvName = root.findViewById(R.id.TVSteamDlName);
        tvInfo = root.findViewById(R.id.TVSteamDlInfo);
        progress = root.findViewById(R.id.PBSteamDl);
        btPause = root.findViewById(R.id.BTSteamDlPause);
        btPause.setOnClickListener(v -> {
            if (last == null) return;
            SteamDownloadState.send(context, last.running
                    ? SteamDownloadService.ACTION_PAUSE : SteamDownloadService.ACTION_RESUME);
        });
        root.findViewById(R.id.BTSteamDlStop).setOnClickListener(v -> confirmStop());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        SteamDownloadState.housekeeping(context);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    public void stop() {
        handler.removeCallbacks(ticker);
    }

    // True when this panel currently shows a download
    // 이 창이 지금 다운로드를 보여 주고 있으면 true
    public boolean isShowing() {
        return root.getVisibility() == View.VISIBLE;
    }

    public void refresh() {
        refreshInternal();
        if (onRefreshed != null) onRefreshed.run();
    }

    private void refreshInternal() {
        SteamDownloadState s = SteamDownloadState.read(context);
        SteamDownloadState.deletePendingIfAny(context, s);
        last = s;
        boolean mine = s.isActive() && (appIdFilter == 0 || s.appId == appIdFilter);
        if (!mine) {
            root.setVisibility(View.GONE);
            if (wasActive) {
                wasActive = false;
                if (listener != null) listener.onFinished(s.result);
            }
            return;
        }
        wasActive = true;
        root.setVisibility(View.VISIBLE);
        tvName.setText(s.name);
        progress.setProgress(s.percent);
        if (s.waitingForGame) {
            tvInfo.setText(context.getString(R.string.steam_dl_waiting_game, s.percent));
            btPause.setText(R.string.steam_dl_resume);
        } else if (s.running) {
            String speed = android.text.format.Formatter.formatShortFileSize(context, s.bytesPerSecond);
            tvInfo.setText(s.percent > 0 ? context.getString(R.string.steam_dl_info, s.percent, speed) : s.status);
            btPause.setText(R.string.steam_dl_pause);
        } else {
            tvInfo.setText(context.getString(R.string.steam_dl_paused, s.percent));
            btPause.setText(R.string.steam_dl_resume);
        }
    }

    // Stop asks first because it deletes the downloaded files
    // 정지는 받은 파일을 지우므로 먼저 물어봄
    private void confirmStop() {
        if (last == null || !last.isActive()) return;
        new AlertDialog.Builder(context)
                .setTitle(R.string.steam_dl_stop)
                .setMessage(last.isUpdate
                        ? context.getString(R.string.steam_update_stop_confirm, last.name)
                        : context.getString(R.string.steam_dl_stop_confirm, last.name))
                .setPositiveButton(R.string.steam_dl_stop, (d, w) ->
                        SteamDownloadState.send(context, SteamDownloadService.ACTION_STOP))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
