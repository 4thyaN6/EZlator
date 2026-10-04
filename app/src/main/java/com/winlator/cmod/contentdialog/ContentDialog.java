package com.winlator.cmod.contentdialog;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import androidx.appcompat.widget.SwitchCompat;
// CheckBox -> SwitchCompat for CBExtraOption, same reasoning as elsewhere.
// CBExtraOption용 CheckBox -> SwitchCompat. 다른 곳과 같은 이유임
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.inputcontrols.ControllerManager;

import java.util.ArrayList;

public class ContentDialog extends Dialog {
    public Runnable onConfirmCallback;
    private Runnable onCancelCallback;
    private final View contentView;

    private boolean isDarkMode;

    private Button gyroButton;

    public interface OnControllerInputListener {
        void onControllerInput(InputDevice device);
    }
    private OnControllerInputListener onControllerInputListener;

    public void setOnControllerInputListener(OnControllerInputListener listener) {
        this.onControllerInputListener = listener;
    }


    public ContentDialog(@NonNull Context context) {
        this(context, 0);
    }

    private static boolean isDarkModePref(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("dark_mode", false);
    }

    private View inflatedLayout;


    // BUG FIX: the dialog used to be constructed with the LIGHT style always
    // (super(context, R.style.ContentDialog)), then tried to switch to dark
    // by calling getContext().setTheme(...) afterwards. That call changes the
    // context used to inflate whatever gets added to it LATER, but the
    // Dialog's own window - already built from the light style at super() -
    // never picks it up, and more importantly it did nothing for the
    // content_dialog.xml view tree already inflated by the line right below
    // it (view inflation had already resolved every colour by then). The
    // right style now has to be picked before super() runs, the same pattern
    // used everywhere else in the app (MainActivity, SettingsFragment, ...).
    // 버그 수정: 예전엔 항상 라이트 스타일로 생성하고(super(context,
    // R.style.ContentDialog)) 나중에 getContext().setTheme(...)으로 다크로
    // 바꾸려 했음. 그 호출은 이후에 그 컨텍스트로 새로 넣는 뷰에만 적용되는데,
    // 다이얼로그 자신의 창은 이미 super()에서 라이트 스타일로 만들어진 뒤라
    // 못 따라가고, 더 중요하게는 바로 다음 줄에서 이미 inflate된
    // content_dialog.xml 뷰 트리에는 (그 시점에 이미 색이 다 정해져서) 아무
    // 효과가 없었음. 이제 super()가 돌기 전에 알맞은 스타일을 먼저 고름 -
    // 앱의 다른 곳(MainActivity, SettingsFragment 등)과 같은 방식임
    public ContentDialog(@NonNull Context context, int layoutResId) {
        this(context, layoutResId, isDarkModePref(context));
    }

    private ContentDialog(@NonNull Context context, int layoutResId, boolean isDarkMode) {
        super(context, isDarkMode ? R.style.ContentDialog_Dark : R.style.ContentDialog);
        this.isDarkMode = isDarkMode;
        contentView = LayoutInflater.from(context).inflate(R.layout.content_dialog, null);


        if (layoutResId > 0) {
            FrameLayout frameLayout = contentView.findViewById(R.id.FrameLayout);
            frameLayout.setVisibility(View.VISIBLE);
            View view = LayoutInflater.from(context).inflate(layoutResId, frameLayout, false);
            frameLayout.addView(view);
        }

        View confirmButton = contentView.findViewById(R.id.BTConfirm);
        confirmButton.setOnClickListener((v) -> {
            if (onConfirmCallback != null) onConfirmCallback.run();
            dismiss();
        });

        View cancelButton = contentView.findViewById(R.id.BTCancel);
        cancelButton.setOnClickListener((v) -> {
            if (onCancelCallback != null) onCancelCallback.run();
            dismiss();
        });

        setContentView(contentView);
    }

    public View getInflatedLayout() {
        return inflatedLayout;
    }

    public View getContentView() {
        return contentView;
    }

    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    public void setOnCancelCallback(Runnable onCancelCallback) {
        this.onCancelCallback = onCancelCallback;
    }

    @Override
    public void setTitle(int titleResId) {
        setTitle(getContext().getString(titleResId));
    }

    public void setIcon(int iconResId) {
        ImageView imageView = findViewById(R.id.IVIcon);
        imageView.setImageResource(iconResId);
        imageView.setVisibility(View.VISIBLE);
    }

    public void setTitle(String title) {
        LinearLayout titleBar = findViewById(R.id.LLTitleBar);
        TextView tvTitle = findViewById(R.id.TVTitle);

        if (title != null && !title.isEmpty()) {
            tvTitle.setText(title);
            titleBar.setVisibility(View.VISIBLE);
        }
        else {
            tvTitle.setText("");
            titleBar.setVisibility(View.GONE);
        }
    }

    public void setBottomBarText(String bottomBarText) {
        TextView tvBottomBarText = findViewById(R.id.TVBottomBarText);

        if (bottomBarText != null && !bottomBarText.isEmpty()) {
            tvBottomBarText.setText(bottomBarText);
            tvBottomBarText.setVisibility(View.VISIBLE);
        }
        else {
            tvBottomBarText.setText("");
            tvBottomBarText.setVisibility(View.GONE);
        }
    }

    public void setMessage(int msgResId) {
        setMessage(getContext().getString(msgResId));
    }

    public void setMessage(String message) {
        TextView tvMessage = findViewById(R.id.TVMessage);

        if (message != null && !message.isEmpty()) {
            tvMessage.setText(message);
            tvMessage.setVisibility(View.VISIBLE);
        }
        else {
            tvMessage.setText("");
            tvMessage.setVisibility(View.GONE);
        }
    }

    public static void alert(Context context, int msgResId, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    public static void alert(Context context, String msg, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.setOnConfirmCallback(callback);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    public static void confirm(Context context, int msgResId, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void confirm(Context context, String msg, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void prompt(Context context, int titleResId, String defaultText, Callback<String> callback) {
        ContentDialog dialog = new ContentDialog(context);

        final EditText editText = dialog.findViewById(R.id.EditText);

        // No more manual colour override here - content_dialog.xml's EditText
        // now uses NeonEditText, which already resolves the right colour from
        // the dialog's own theme (see the ContentDialog theme-chain fix).
        // 여기서 수동으로 색을 덮어쓰던 코드를 없앰 - content_dialog.xml의
        // EditText가 이제 NeonEditText를 쓰고, 다이얼로그 자신의 테마에서
        // 알맞은 색을 이미 가져옴(ContentDialog 테마 체계 수정 참고)
        editText.setHint(R.string.untitled);
        if (defaultText != null) editText.setText(defaultText);
        editText.setVisibility(View.VISIBLE);

        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(() -> {
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) callback.call(text);
        });

        dialog.show();
    }

    public static void showMultipleChoiceList(Context context, int titleResId, final String[] items, Callback<ArrayList<Integer>> callback) {
        ContentDialog dialog = new ContentDialog(context);

        final ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_multiple_choice, items));
        listView.setVisibility(View.VISIBLE);

        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(() -> {
            ArrayList<Integer> result = new ArrayList<>();
            SparseBooleanArray checkedItemPositions = listView.getCheckedItemPositions();
            for (int i = 0; i < checkedItemPositions.size(); i++) {
                if (checkedItemPositions.valueAt(i)) result.add(checkedItemPositions.keyAt(i));
            }
            callback.call(result);
        });

        dialog.show();
    }

    public static void showSingleChoiceList(Context context, int titleResId, final String[] items, Callback<Integer> callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.getContentView().findViewById(R.id.BTConfirm).setVisibility(View.GONE);

        final ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_single_choice, items));
        listView.setVisibility(View.VISIBLE);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            callback.call(position);
            dialog.dismiss();
        });

        dialog.setTitle(titleResId);
        dialog.show();
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        // If we are actively listening for controller input...
        if (onControllerInputListener != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            InputDevice device = event.getDevice();
            // And the event is from a real, physical game controller...
            if (device != null && !device.isVirtual() && ControllerManager.isGameController(device)) {
                // ...then trigger our callback and consume the event so it doesn't do anything else.
                onControllerInputListener.onControllerInput(device);
                return true;
            }
        }
        // Otherwise, process the key event normally (e.g., for keyboard input in an EditText).
        return super.dispatchKeyEvent(event);
    }

    public static class ConfirmationResult {
        public final boolean confirmed;
        public final boolean checkboxChecked;

        public ConfirmationResult(boolean confirmed, boolean checkboxChecked) {
            this.confirmed = confirmed;
            this.checkboxChecked = checkboxChecked;
        }
    }

    public static void confirmWithCheckbox(Context context, String message, String checkboxText, Callback<ConfirmationResult> callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(message);

        final SwitchCompat checkBox = dialog.findViewById(R.id.CBExtraOption);
        final View confirmButton = dialog.findViewById(R.id.BTConfirm);

        checkBox.setText(checkboxText);
        checkBox.setVisibility(View.VISIBLE);
        checkBox.setChecked(false);

        // Link the checkbox to the OK button's state
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            confirmButton.setEnabled(!isChecked);
            // Add this line to visually fade the button when disabled
            confirmButton.setAlpha(isChecked ? 0.5f : 1.0f);
        });

        dialog.setOnConfirmCallback(() -> {
            callback.call(new ConfirmationResult(true, false));
        });
        dialog.setOnCancelCallback(() -> {
            callback.call(new ConfirmationResult(false, checkBox.isChecked()));
        });

        dialog.show();
    }


}
