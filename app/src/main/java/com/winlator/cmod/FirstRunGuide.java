package com.winlator.cmod;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

// First-run guide shown on a fresh install, before anything is downloaded.
// Replaces the old "essentials only / everything" dialog that appeared right
// away: the user first says how familiar they are, then gets the matching
// explanation and buttons.
//   "I know nothing"      -> explanation + storage permission + Download / Later,
//                            container created automatically afterwards
//   "I've done it before" -> essentials only (then jump to components) / everything
// The window ignores outside touches and the back key, so it never vanishes
// before it has been read.
// 새로 설치했을 때, 아무것도 받기 전에 뜨는 첫 실행 안내창임. 바로 뜨던 예전
// "필수만 받기 / 전부 받기" 창을 대체함: 사용자가 먼저 익숙한 정도를 고르고,
// 그에 맞는 설명과 버튼을 받음.
//   "아무것도 몰라요" -> 설명 + 저장소 권한 + 다운받기 / 나중에 받기,
//                         끝나면 컨테이너 자동 생성
//   "해본적 있어요"   -> 필수만 받기(끝나면 구성요소 화면으로 이동) / 전부 받기
// 창이 바깥 터치와 뒤로가기를 무시하므로, 읽기 전에 사라지지 않음
public class FirstRunGuide {
    private static final int STEP_WELCOME = 0;
    private static final int STEP_EASY = 1;
    private static final int STEP_EXPERT = 2;

    private final MainActivity activity;
    private final Runnable onClosed;
    private final Dialog dialog;

    private final TextView tvTitle;
    private final TextView tvBody;
    private final TextView tvPermission;
    private final View llWelcome;
    private final View llActions;
    private final Button btPrimary;
    private final Button btSecondary;

    private int step = STEP_WELCOME;
    // Action waiting for the storage permission answer (a download button)
    // 저장소 권한 답을 기다리는 동작(다운로드 버튼)
    private Runnable pendingAction;

    public FirstRunGuide(MainActivity activity, Runnable onClosed) {
        this.activity = activity;
        this.onClosed = onClosed;

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.first_run_guide);
        // Outside touches and the back key do nothing
        // 바깥 터치와 뒤로가기는 아무 동작도 안 함
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            if (this.onClosed != null) this.onClosed.run();
        });

        tvTitle = dialog.findViewById(R.id.TVGuideTitle);
        tvBody = dialog.findViewById(R.id.TVGuideBody);
        tvPermission = dialog.findViewById(R.id.TVGuidePermission);
        llWelcome = dialog.findViewById(R.id.LLGuideWelcome);
        llActions = dialog.findViewById(R.id.LLGuideActions);
        btPrimary = dialog.findViewById(R.id.BTGuidePrimary);
        btSecondary = dialog.findViewById(R.id.BTGuideSecondary);

        dialog.findViewById(R.id.BTGuideKnowNothing).setOnClickListener(v -> showStep(STEP_EASY));
        dialog.findViewById(R.id.BTGuideKnowSome).setOnClickListener(v -> showStep(STEP_EXPERT));
        dialog.findViewById(R.id.BTGuideBack).setOnClickListener(v -> showStep(STEP_WELCOME));

        btPrimary.setOnClickListener(v -> {
            if (step == STEP_EASY) startWithPermission(MainActivity.FIRST_RUN_EASY);
            else if (step == STEP_EXPERT) startWithPermission(MainActivity.FIRST_RUN_ESSENTIAL);
        });
        btSecondary.setOnClickListener(v -> {
            // EASY: "Later" closes the guide without downloading. It shows
            // again on the next launch, because the system files are still missing
            // EASY: "나중에 받기"는 받지 않고 창을 닫음. 시스템 파일이 아직 없으므로
            // 다음 실행 때 다시 뜸
            if (step == STEP_EASY) dialog.dismiss();
            else if (step == STEP_EXPERT) startWithPermission(MainActivity.FIRST_RUN_ALL);
        });
    }

    public void show() {
        showStep(STEP_WELCOME);
        // Side by side when two two-tile buttons fit on screen (tablets,
        // landscape); stacked otherwise (phone portrait). Reported: stacked
        // buttons left too much empty space left and right on the tablet
        // 타일 2개짜리 버튼 두 개가 화면에 들어가면 가로로 나란히(태블릿, 가로
        // 모드), 아니면 세로로 쌓음(폰 세로). 태블릿에서 세로 배치는 좌우가 너무
        // 비어 보인다는 보고가 있었음
        boolean horizontal = fitsSideBySide();
        int gap = dp(12);
        arrangeRow((LinearLayout) llWelcome, horizontal, gap);
        arrangeRow(dialog.findViewById(R.id.LLGuideActionRow), horizontal, dp(10));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            // The layout draws its own rounded card
            // 레이아웃이 자기 둥근 카드를 그림
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(computeWindowWidthPx(horizontal), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    // Puts a row's children side by side (equal widths) or stacked (full
    // width), keeping each child's height, with a gap between them
    // 줄의 자식들을 나란히(같은 폭) 또는 쌓아서(전체 폭) 놓음. 각 자식의 높이는
    // 그대로 두고 사이에 간격을 줌
    private static void arrangeRow(LinearLayout row, boolean horizontal, int gap) {
        row.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            int height = child.getLayoutParams().height;
            LinearLayout.LayoutParams lp = horizontal
                    ? new LinearLayout.LayoutParams(0, height, 1f)
                    : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
            if (i > 0) {
                if (horizontal) lp.leftMargin = gap;
                else lp.topMargin = gap;
            }
            child.setLayoutParams(lp);
        }
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }

    // Tile width of the shortcut grid on this screen, in dp
    // 이 화면에서 숏컷 격자의 타일 폭(dp)
    private float tileWidthDp() {
        int screenDp = activity.getResources().getConfiguration().screenWidthDp;
        int span = Math.max(2, Math.min(5, screenDp / 115));
        return (float) screenDp / span;
    }

    private boolean fitsSideBySide() {
        int screenDp = activity.getResources().getConfiguration().screenWidthDp;
        float buttonDp = tileWidthDp() * 2f - 12f;
        return buttonDp * 2f + 12f + 44f <= screenDp - 16f;
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    // Width so that each big choice button is two shortcut tiles wide.
    // Same column rule as ShortcutsFragment.applyLayoutManager() (115dp per
    // column, 2..5 columns); each tile loses 12dp to its margins, and the card
    // adds 22dp padding on both sides
    // 큰 선택 버튼 하나가 숏컷 타일 2개 너비가 되게 하는 창 너비임.
    // ShortcutsFragment.applyLayoutManager()와 같은 열 규칙(열당 115dp, 2~5열)을
    // 씀. 타일마다 여백 12dp가 빠지고, 카드는 좌우로 22dp씩 안쪽 여백을 더함
    private int computeWindowWidthPx(boolean horizontal) {
        int screenDp = activity.getResources().getConfiguration().screenWidthDp;
        float buttonDp = tileWidthDp() * 2f - 12f;
        float contentDp = horizontal ? buttonDp * 2f + 12f : buttonDp;
        float windowDp = Math.min(contentDp + 44f, screenDp - 16f);
        return dp(windowDp);
    }

    private void showStep(int newStep) {
        step = newStep;
        llWelcome.setVisibility(step == STEP_WELCOME ? View.VISIBLE : View.GONE);
        llActions.setVisibility(step == STEP_WELCOME ? View.GONE : View.VISIBLE);

        switch (step) {
            case STEP_WELCOME:
                tvTitle.setText(R.string.first_run_welcome_title);
                tvBody.setText(R.string.first_run_welcome_body);
                break;
            case STEP_EASY:
                tvTitle.setText(R.string.first_run_easy_title);
                tvBody.setText(R.string.first_run_easy_body);
                btPrimary.setText(R.string.first_run_download);
                btSecondary.setText(R.string.first_run_later);
                tvPermission.setVisibility(View.VISIBLE);
                updatePermissionLine();
                // The permission is asked together with the explanation
                // 권한은 설명과 함께 바로 요청함
                if (!activity.hasStoragePermission()) activity.requestStoragePermission();
                break;
            case STEP_EXPERT:
                tvTitle.setText(R.string.first_run_expert_title);
                tvBody.setText(R.string.first_run_expert_body);
                btPrimary.setText(R.string.first_run_essential_only);
                btSecondary.setText(R.string.first_run_all);
                tvPermission.setVisibility(View.GONE);
                break;
        }
    }

    private void updatePermissionLine() {
        tvPermission.setText(activity.hasStoragePermission()
                ? R.string.first_run_permission_granted
                : R.string.first_run_permission_missing);
    }

    // Downloads only start with the storage permission; without it the
    // permission is asked first and the download runs once it is granted
    // 다운로드는 저장소 권한이 있어야 시작함. 없으면 먼저 권한을 요청하고,
    // 허용되면 그때 다운로드를 진행함
    private void startWithPermission(int plan) {
        Runnable start = () -> {
            dialog.dismiss();
            activity.startFirstRunDownload(plan);
        };
        if (activity.hasStoragePermission()) {
            start.run();
        } else {
            pendingAction = start;
            activity.requestStoragePermission();
        }
    }

    // Called by MainActivity.onRequestPermissionsResult() while the guide is up
    // 안내창이 떠 있는 동안 MainActivity.onRequestPermissionsResult()가 부름
    public void onPermissionResult(boolean granted) {
        if (step == STEP_EASY) updatePermissionLine();
        Runnable action = pendingAction;
        pendingAction = null;
        if (granted) {
            if (action != null) action.run();
        } else {
            Toast.makeText(activity, R.string.first_run_permission_denied, Toast.LENGTH_LONG).show();
        }
    }
}
