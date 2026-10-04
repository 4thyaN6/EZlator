package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

import android.widget.ProgressBar;
import com.winlator.cmod.R;
import com.winlator.cmod.math.Mathf;

public class DownloadProgressDialog {
    private final Activity activity;
    private Dialog dialog;

    public DownloadProgressDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.download_progress_dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public void show() {
        show(null);
    }

    public void show(int textResId) {
        show(textResId, null);
    }

    public void show(Runnable onCancelCallback) {
        show(0, onCancelCallback);
    }

    public void show(int textResId, final Runnable onCancelCallback) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();

        if (textResId > 0) ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);

        setProgress(0);
        // Size line and overall section start hidden; the caller turns them on
        // only when it actually has that information.
        // 용량 줄과 전체 진행 영역은 숨긴 채로 시작함. 호출하는 쪽이 실제로 그
        // 정보가 있을 때만 켬
        setFileStatus(null);
        dialog.findViewById(R.id.LLOverall).setVisibility(View.GONE);
        if (onCancelCallback != null) {
            dialog.findViewById(R.id.BTCancel).setOnClickListener((v) -> onCancelCallback.run());
            dialog.findViewById(R.id.LLBottomBar).setVisibility(View.VISIBLE);
        }
        dialog.show();
    }

    public void setProgress(int progress) {
        if (dialog == null) return;
        progress = Mathf.clamp(progress, 0, 100);
        // Linear bar now (download_progress_dialog.xml). Plain ProgressBar is
        // enough: setProgress(int) is all this needs, and it avoids depending
        // on a Material indicator class for a simple determinate bar.
        // 이제 선형 막대임(download_progress_dialog.xml). 기본 ProgressBar로
        // 충분함: 필요한 건 setProgress(int)뿐이고, 단순한 퍼센트 막대 때문에
        // Material 표시기 클래스에 의존할 필요가 없음
        ((ProgressBar) dialog.findViewById(R.id.PBDownload)).setProgress(progress);
        ((TextView)dialog.findViewById(R.id.TVProgress)).setText(progress+"%");
    }

    /**
     * Current file's byte progress. Updates the per-file bar, the percentage
     * and the "45.2 / 180.5 MiB" size line. With total <= 0 (no Content-Length)
     * only the downloaded amount is shown and the bar stays put - the ring
     * spinner next to it still shows that work is going on.
     * 현재 파일의 바이트 진행률임. 파일별 막대, 퍼센트, "45.2 / 180.5 MiB"
     * 용량 줄을 갱신함. total이 0 이하(Content-Length 없음)면 받은 양만
     * 보여주고 막대는 멈춰 있음 - 옆의 원형 스피너가 작업 중임을 계속 알려 줌
     */
    public void setFileProgress(long downloaded, long total) {
        if (dialog == null) return;
        TextView bytes = dialog.findViewById(R.id.TVFileBytes);
        bytes.setVisibility(View.VISIBLE);
        if (total > 0) {
            int percent = (int) Math.min(100, downloaded * 100 / total);
            ((ProgressBar) dialog.findViewById(R.id.PBDownload)).setProgress(percent);
            ((TextView) dialog.findViewById(R.id.TVProgress)).setText(percent + "%");
            bytes.setText(formatBytes(downloaded) + " / " + formatBytes(total));
        } else {
            ((TextView) dialog.findViewById(R.id.TVProgress)).setText("");
            bytes.setText(formatBytes(downloaded));
        }
    }

    /**
     * Free-text status in the size line, e.g. "설치 중…" after the bytes are
     * in but before extraction finishes. null hides the line.
     * 용량 줄에 쓰는 상태 문구임. 예: 다 받은 뒤 압축 해제가 끝나기 전의
     * "설치 중…". null이면 그 줄을 숨김
     */
    public void setFileStatus(String text) {
        if (dialog == null) return;
        TextView bytes = dialog.findViewById(R.id.TVFileBytes);
        if (text == null) {
            bytes.setVisibility(View.GONE);
        } else {
            bytes.setVisibility(View.VISIBLE);
            bytes.setText(text);
        }
    }

    /**
     * Overall position in a multi-file run. completed = files already
     * finished, so the thin bar steps up exactly once per finished file.
     * current is the 1-based number of the file being downloaded now.
     * 여러 파일을 받을 때의 전체 위치임. completed는 이미 끝난 파일 수라서
     * 얇은 막대가 파일 하나가 끝날 때마다 정확히 한 칸씩 오름. current는
     * 지금 받는 파일의 1부터 시작하는 번호임
     */
    public void setOverall(int current, int completed, int total) {
        if (dialog == null || total <= 0) return;
        dialog.findViewById(R.id.LLOverall).setVisibility(View.VISIBLE);
        ((TextView) dialog.findViewById(R.id.TVOverall)).setText("전체 " + current + " / " + total);
        ((ProgressBar) dialog.findViewById(R.id.PBOverall)).setProgress(completed * 100 / total);
    }

    /**
     * 1024-based units with IEC labels (MiB / GiB), matching how other apps
     * the user compared against report sizes.
     * 1024 기준 단위에 IEC 표기(MiB / GiB)를 씀. 사용자가 비교한 다른 앱들이
     * 용량을 적는 방식과 맞춤
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.0f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public void setLabel(String text) {
        if (dialog == null) return;
        ((TextView) dialog.findViewById(R.id.TextView)).setText(text);
    }

    public void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        }
        catch (Exception e) {}
    }

    public boolean closeOnUiThread() {
        activity.runOnUiThread(this::close);
        return true;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
