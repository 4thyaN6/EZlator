package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.view.InputDevice;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import androidx.appcompat.widget.SwitchCompat;
// CheckBox -> SwitchCompat, same reasoning as the other screens.
// CheckBox -> SwitchCompat. 다른 화면들과 같은 이유임
import android.widget.TextView;


import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.winhandler.WinHandler;

public class ControllerAssignmentDialog {
    private final ContentDialog dialog;
    private final ControllerManager controllerManager;
    private final WinHandler winHandler;     // may be null
    private final Activity hostActivity;

    private final SwitchCompat[] checkBoxes = new SwitchCompat[4];
    private final TextView[] deviceNameTextViews = new TextView[4];
    private final Button[] assignButtons = new Button[4];
    private final Button[] btnMacros = new Button[4];   // <-- Button array
    private final SwitchCompat[] vibrateBoxes = new SwitchCompat[4];
    private final Button[] resetButtons = new Button[4];

    private final TextView restartRequiredView;
    private final int initialPlayerCount;

    // ---------- Public entry points -----------------------------------------

    /** Legacy call (e.g., from MainActivity). Will try to grab WinHandler if context is XServerDisplayActivity. */
    public static void show(Context context) {
        show(context, extractWinHandler(context));
    }

    /** Preferred call when you already have the handler. */
    public static void show(Context context, WinHandler winHandler) {
        int initialPlayerCount = ControllerManager.getInstance().getEnabledPlayerCount();
        Activity act = (Activity) context; // all current callers pass an Activity
        new ControllerAssignmentDialog(act, initialPlayerCount, winHandler).showContentDialog();
    }

    private static WinHandler extractWinHandler(Context ctx) {
        if (ctx instanceof XServerDisplayActivity) {
            return ((XServerDisplayActivity) ctx).getWinHandler();
        }
        return null;
    }

    // ---------- Impl ---------------------------------------------------------

    // BUG FIX: this used to build its own ContextThemeWrapper by hand - wrong
    // in both directions (dark ? R.style.ContentDialog : R.style.AppTheme
    // picked our style for LIGHT-in-name-only when dark, and the plain OLD
    // AppTheme - no neon attrs at all - when NOT dark), and then passed that
    // wrapper into ContentDialog(context, layoutResId). ContentDialog's own
    // constructor now already resolves the correct light/dark style itself
    // (see its theme-chain fix), the same way every other ContentDialog
    // subclass calls it - so this dialog just needs to do the same.
    // 버그 수정: 직접 만든 ContextThemeWrapper를 썼는데 방향이 둘 다 잘못돼
    // 있었음(dark ? R.style.ContentDialog : R.style.AppTheme는 다크일 때
    // 이름만 그럴듯한 라이트 값을 쓰게 했고, 다크가 아닐 때는 네온 속성이
    // 전혀 없는 예전 AppTheme를 그대로 썼음). ContentDialog 자신의 생성자가
    // 이제 알맞은 라이트/다크 스타일을 스스로 고름(테마 체계 수정 참고) -
    // 다른 모든 ContentDialog 서브클래스가 부르는 것과 같은 방식이라, 이
    // 다이얼로그도 그냥 똑같이 하면 됨
    private ControllerAssignmentDialog(Activity activity, int initialPlayerCount, WinHandler winHandler) {
        this.dialog = new ContentDialog(activity, R.layout.controller_assignment_dialog);
        this.dialog.setTitle(R.string.controller_manager);

        this.controllerManager = ControllerManager.getInstance();
        this.initialPlayerCount = initialPlayerCount;
        this.winHandler = winHandler;     // can be null
        this.hostActivity = activity;

        initializeViews();

        restartRequiredView = dialog.getContentView().findViewById(R.id.TVRestartRequired);

        // BUG FIX (build error): this block referenced a local `dark`
        // variable that no longer exists - it belonged to the manual
        // ContextThemeWrapper removed above. It also predates that removal
        // for a real reason: setTextColorForDialog() force-painted every
        // TextView in the dialog tree solid white for dark mode, and
        // SwitchCompat IS a TextView (CompoundButton -> Button -> TextView),
        // so it would have overwritten NeonSwitch's own label colour and
        // NeonButtonGhost's accent-coloured text too. With the theme-chain
        // fix, content_dialog.xml and controller_assignment_dialog.xml
        // already resolve the right colour per theme on their own - this
        // manual pass has nothing left to do.
        // 버그 수정(빌드 에러): 이 블록이 더 이상 없는 지역변수 `dark`를
        // 참조하고 있었음 - 위에서 지운 수동 ContextThemeWrapper에 딸려 있던
        // 것임. 지우는 게 맞는 이유가 하나 더 있었음: setTextColorForDialog()가
        // 다크 모드일 때 다이얼로그 안의 모든 TextView를 흰색으로 강제
        // 칠했는데, SwitchCompat도 TextView임(CompoundButton -> Button ->
        // TextView). 그래서 NeonSwitch 라벨 색과 NeonButtonGhost의 강조색
        // 글자까지 덮어썼을 것임. 테마 체계를 고친 지금은 content_dialog.xml과
        // controller_assignment_dialog.xml이 이미 테마별로 알맞은 색을 스스로
        // 가져오므로, 이 수동 작업이 더 이상 할 일이 없음

        populateView();
        setupListeners();
    }

    private static int dp(Context c, int v){
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    @SuppressWarnings("deprecation")
    public void showContentDialog() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w == null) return;

        int widthPx;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowMetrics metrics = w.getWindowManager().getCurrentWindowMetrics();
            android.graphics.Rect b = metrics.getBounds();
            widthPx = b.width();
        } else {
            Point p = new Point();
            w.getWindowManager().getDefaultDisplay().getSize(p);
            widthPx = p.x;
        }

        int capPx = dp(dialog.getContext(), 540);
        int target = Math.min((int) (widthPx * 0.90f), capPx);
        w.setLayout(target, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void initializeViews() {
        View view = dialog.getContentView();

        // Player 1
        checkBoxes[0] = view.findViewById(R.id.CBPlayer1);
        deviceNameTextViews[0] = view.findViewById(R.id.TVPlayer1DeviceName);
        assignButtons[0] = view.findViewById(R.id.BTNAssignP1);
        vibrateBoxes[0] = view.findViewById(R.id.CBVibrateP1);
        resetButtons[0] = view.findViewById(R.id.BTNResetP1);
        btnMacros[0] = view.findViewById(R.id.BTNMacrosP1);
        btnMacros[0].setOnClickListener(v ->
                com.winlator.cmod.contentdialog.MacrosDialog.show(hostActivity, 0, winHandler));

        // Player 2
        checkBoxes[1] = view.findViewById(R.id.CBPlayer2);
        deviceNameTextViews[1] = view.findViewById(R.id.TVPlayer2DeviceName);
        assignButtons[1] = view.findViewById(R.id.BTNAssignP2);
        vibrateBoxes[1] = view.findViewById(R.id.CBVibrateP2);
        resetButtons[1] = view.findViewById(R.id.BTNResetP2);
        btnMacros[1] = view.findViewById(R.id.BTNMacrosP2);
        btnMacros[1].setOnClickListener(v ->
                com.winlator.cmod.contentdialog.MacrosDialog.show(hostActivity, 1, winHandler));

        // Player 3
        checkBoxes[2] = view.findViewById(R.id.CBPlayer3);
        deviceNameTextViews[2] = view.findViewById(R.id.TVPlayer3DeviceName);
        assignButtons[2] = view.findViewById(R.id.BTNAssignP3);
        vibrateBoxes[2] = view.findViewById(R.id.CBVibrateP3);
        resetButtons[2] = view.findViewById(R.id.BTNResetP3);
        btnMacros[2] = view.findViewById(R.id.BTNMacrosP3);
        btnMacros[2].setOnClickListener(v ->
                com.winlator.cmod.contentdialog.MacrosDialog.show(hostActivity, 2, winHandler));

        // Player 4
        checkBoxes[3] = view.findViewById(R.id.CBPlayer4);
        deviceNameTextViews[3] = view.findViewById(R.id.TVPlayer4DeviceName);
        assignButtons[3] = view.findViewById(R.id.BTNAssignP4);
        vibrateBoxes[3] = view.findViewById(R.id.CBVibrateP4);
        resetButtons[3] = view.findViewById(R.id.BTNResetP4);
        btnMacros[3] = view.findViewById(R.id.BTNMacrosP4);
        btnMacros[3].setOnClickListener(v ->
                com.winlator.cmod.contentdialog.MacrosDialog.show(hostActivity, 3, winHandler));
    }

    private void populateView() {
        controllerManager.scanForDevices();

        for (int i = 0; i < 4; i++) {
            checkBoxes[i].setChecked(controllerManager.isSlotEnabled(i));
            if (vibrateBoxes[i] != null) {
                vibrateBoxes[i].setChecked(controllerManager.isVibrationEnabled(i));
            }
            InputDevice device = controllerManager.getAssignedDeviceForSlot(i);
            deviceNameTextViews[i].setText(
                    device != null ? device.getName() : dialog.getContext().getString(R.string.not_assigned)
            );
            deviceNameTextViews[i].setSelected(true);
        }
    }

    private void setupListeners() {
        for (int i = 0; i < 4; i++) {
            final int slotIndex = i;

            checkBoxes[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                controllerManager.setSlotEnabled(slotIndex, isChecked);
                if (!isChecked) {
                    for (int j = slotIndex + 1; j < 4; j++) {
                        if (controllerManager.isSlotEnabled(j)) controllerManager.setSlotEnabled(j, false);
                    }
                } else {
                    for (int j = 0; j < slotIndex; j++) {
                        if (!controllerManager.isSlotEnabled(j)) controllerManager.setSlotEnabled(j, true);
                    }
                }
                populateView();

                if (controllerManager.getEnabledPlayerCount() != initialPlayerCount) {
                    restartRequiredView.setVisibility(View.VISIBLE);
                } else {
                    restartRequiredView.setVisibility(View.GONE);
                }
            });

            vibrateBoxes[i].setOnCheckedChangeListener((b, checked) ->
                    controllerManager.setVibrationEnabled(slotIndex, checked));

            resetButtons[i].setOnClickListener(v -> {
                controllerManager.unassignSlot(slotIndex);
                populateView();
            });

            assignButtons[i].setOnClickListener(v -> {
                String message = dialog.getContext().getString(R.string.press_any_button_for_player) + " " + (slotIndex + 1);
                dialog.setMessage(message);

                dialog.setOnControllerInputListener(device -> {
                    if (!ControllerManager.isGameController(device)) return;
                    controllerManager.assignDeviceToSlot(slotIndex, device);
                    dialog.setMessage(null);
                    dialog.setOnControllerInputListener(null);
                    populateView();
                });
            });
        }

        dialog.setOnConfirmCallback(() -> controllerManager.saveAssignments());
    }
}
