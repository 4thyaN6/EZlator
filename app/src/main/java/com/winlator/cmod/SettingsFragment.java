package com.winlator.cmod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import androidx.appcompat.widget.SwitchCompat;
// CheckBox import removed - every checkbox in this screen is now a
// SwitchCompat (settings_fragment.xml). SwitchCompat is a CompoundButton
// just like CheckBox, so isChecked()/setChecked()/setOnCheckedChangeListener
// calls below did not need to change, only the declared type.
// CheckBox import를 없앰 - 이 화면의 체크박스는 전부 SwitchCompat가 됨
// (settings_fragment.xml). SwitchCompat도 CheckBox와 똑같은
// CompoundButton이라 아래의 isChecked()/setChecked()/
// setOnCheckedChangeListener 호출은 안 바뀌어도 됐고, 선언 타입만 바뀜
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.collection.ArrayMap;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.R;
import com.winlator.cmod.box86_64.Box86_64EditPresetDialog;
import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AllFilesAccess;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.ArrayUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.inputcontrols.ControlElement;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.PreferenceKeys;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.restore.RestoreActivity;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import org.json.JSONArray;
import org.json.JSONException;
import org.tukaani.xz.check.Check;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {
    public static final String DEFAULT_WINE_DEBUG_CHANNELS = "warn,err,fixme";
    private Callback<Uri> installSoundFontCallback;
    private PreloaderDialog preloaderDialog;
    public static final String DEFAULT_EXPORT_PATH = com.winlator.cmod.core.AppDirs.downloads("Frontend").getPath();
    private SharedPreferences preferences;

    // Disable or enable Xinput Processing
    private SwitchCompat cbXinputToggle;
    // Disable or enable Touchscreen Input Mode
    private SwitchCompat cbXTouchscreenToggle;

    private boolean isRestoreAction = false;

    private boolean enableLegacyInputMode = false;

    private SwitchCompat cbDarkMode;
    boolean isDarkMode;

    private SwitchCompat cbUsbStorageAccess;

    // Guards the listener while we write the checkbox state programmatically.
    // 체크 상태를 코드로 바꾸는 동안 리스너가 반응하지 않게 막는 플래그임
    private boolean suppressUsbStorageCallback = false;

    private static final int REQUEST_CODE_FRONTEND_EXPORT_PATH = 1002;
    private static final int REQUEST_CODE_INSTALL_SOUNDFONT = 1001;
    // Save archive from another app (SaveTransferUi.importPicked)
    // 다른 앱의 세이브 묶음(SaveTransferUi.importPicked)
    private static final int REQUEST_CODE_SAVE_IMPORT = 1003;

    // Game language also in LC_ALL (Container section). On by default: Korean-patched
    // games need it, and it is only turned off when a game in a folder with a Korean
    // name does not start. Read by XServerDisplayActivity
    // LC_ALL에도 게임 언어 적용(컨테이너 구역). 기본 켜짐: 한국어 패치 게임에 필요하고,
    // 한글 이름 폴더의 게임이 실행되지 않을 때만 끔. XServerDisplayActivity가 읽음
    public static final String PREF_REAL_LOCALE = "wine_real_locale";
    public static final boolean REAL_LOCALE_DEFAULT = true;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.settings);
    }



    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.settings_fragment, container, false);
        final Context context = getContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Check for Dark Mode preference
        isDarkMode = preferences.getBoolean("dark_mode", false);

        // Sections a beginner does not need start closed; Theme and System files
        // stay open (see settings_fragment.xml)
        // 초보자에게 필요 없는 구역은 닫힌 채 시작함. 테마와 시스템 파일은 펼친 채
        // 둠(settings_fragment.xml 참고)
        setupFold(view, R.id.TVFoldDownload, R.id.LLBodyDownload, R.id.TVDownload);
        setupFold(view, R.id.TVFoldFrameRate, R.id.LLBodyFrameRate, R.id.TVFrameRate);
        setupFold(view, R.id.TVFoldContainer, R.id.LLBodyContainer, R.id.TVBox86Box64);
        setupFold(view, R.id.TVFoldController, R.id.LLBodyController, R.id.TVGameControllerLabel);
        setupFold(view, R.id.TVFoldExperimental, R.id.LLBodyExperimental, R.id.TVExperimental);
        setupFold(view, R.id.TVFoldLogs, R.id.LLBodyLogs, R.id.TVLogs);

        // Initialize the Dark Mode checkbox
        cbDarkMode = view.findViewById(R.id.CBDarkMode);
        cbDarkMode.setChecked(preferences.getBoolean("dark_mode", false));

        cbDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Save dark mode preference
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("dark_mode", isChecked);
            editor.apply();

            // Update the UI or activity theme if necessary
            updateTheme(isChecked);
        });

        // Beginner mode (EZ mode), saved immediately like dark mode. Read by
        // ShortcutsFragment.isEzMode() when the detail sheet opens
        // 초보자 모드(EZ mode)임. 다크 모드처럼 즉시 저장함. 상세창이 열릴 때
        // ShortcutsFragment.isEzMode()가 읽음
        SwitchCompat cbEzMode = view.findViewById(R.id.CBEzMode);
        cbEzMode.setChecked(ShortcutsFragment.isEzMode(context));
        cbEzMode.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(ShortcutsFragment.PREF_EZ_MODE, isChecked).apply());

        // Steam download while a game runs: on = pause (resumes after the game),
        // off = no limit. Read by SteamDownloadService every half second
        // 게임 중 스팀 다운로드: 켜짐 = 일시정지(게임이 끝나면 이어 받음),
        // 꺼짐 = 제한 없음. SteamDownloadService가 0.5초마다 읽음
        // Game language in LC_ALL too (on by default, see PREF_REAL_LOCALE)
        // LC_ALL에도 게임 언어 적용(기본 켜짐, PREF_REAL_LOCALE 참고)
        SwitchCompat cbRealLocale = view.findViewById(R.id.CBWineRealLocale);
        cbRealLocale.setChecked(preferences.getBoolean(PREF_REAL_LOCALE, REAL_LOCALE_DEFAULT));
        cbRealLocale.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(PREF_REAL_LOCALE, isChecked).apply());

        // Shortcut detail sheet: start with the lower part folded
        // 숏컷 상세창: 아래 부분을 접은 채 시작
        SwitchCompat cbDetailFolded = view.findViewById(R.id.CBDetailFolded);
        // Folded is the default (a shortcut's detail sheet starts folded)
        // 접힘이 기본값임(숏컷 상세창이 접힌 채 시작함)
        cbDetailFolded.setChecked(preferences.getBoolean(ShortcutsFragment.PREF_DETAIL_FOLDED, true));
        cbDetailFolded.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(ShortcutsFragment.PREF_DETAIL_FOLDED, isChecked).apply());

        SwitchCompat cbSteamDlPause = view.findViewById(R.id.CBSteamDlPauseGame);
        cbSteamDlPause.setChecked(preferences.getBoolean(
                com.winlator.cmod.steam.SteamDownloadService.PREF_PAUSE_DURING_GAME, true));
        cbSteamDlPause.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(
                        com.winlator.cmod.steam.SteamDownloadService.PREF_PAUSE_DURING_GAME, isChecked).apply());
// USB storage access is optional (only needed for external USB drives),
// so it's now an explicit switch instead of an automatic popup on every launch.
// USB 저장장치 접근은 선택 사항(외장 USB 드라이브 쓸 때만 필요)이라,
// 앱 켤 때마다 뜨는 자동 팝업 대신 사용자가 직접 켜는 스위치로 바꿈
        cbUsbStorageAccess = view.findViewById(R.id.CBUsbStorageAccess);
        syncUsbStorageCheckbox();

        cbUsbStorageAccess.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Ignore changes we made ourselves in syncUsbStorageCheckbox().
            // syncUsbStorageCheckbox()에서 우리가 바꾼 값은 무시함
            if (suppressUsbStorageCallback) return;
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return;

            boolean granted = Environment.isExternalStorageManager();

            // MANAGE_EXTERNAL_STORAGE cannot be released by the app - there is no
            // revoke API for it. Leaving the box unchecked while the permission is
            // still granted would misrepresent the app's actual access, so both
            // directions send the user to the same system screen.
            // MANAGE_EXTERNAL_STORAGE는 앱이 스스로 반납할 수 없음. 회수 API 자체가
            // 없음. 권한이 살아 있는데 체크만 풀어두면 실제 접근 범위를 속이는 꼴이
            // 되므로, 켤 때든 끌 때든 같은 시스템 화면으로 보냄
            // CHANGED: both directions now show a notice first (Continue / Back)
            // instead of jumping straight to the system screen. Back puts the
            // switch back to the real state; after Continue, onResume() re-syncs it.
            // 변경: 켤 때와 끌 때 모두 시스템 화면으로 바로 가지 않고 안내창(계속하기 /
            // 뒤로가기)을 먼저 띄움. 뒤로가기는 스위치를 실제 상태로 되돌리고,
            // 계속하기 후에는 onResume()이 다시 맞춰줌
            if (isChecked != granted) {
                if (isChecked) {
                    AllFilesAccess.showEnableDialog(requireActivity(), this::syncUsbStorageCheckbox);
                } else {
                    AllFilesAccess.showDisableDialog(requireActivity(), this::syncUsbStorageCheckbox);
                }
            }
        });
        // --- Frame rate limit widgets -------------------------------------
        // The save logic in BTConfirm (below) and the DXVK_FRAME_RATE env var in
        // XServerDisplayActivity already existed; only these bindings were missing.
        // Declared final so the BTConfirm lambda can capture them.
        // --- 프레임 제한 위젯 ----------------------------------------------
        // 아래 BTConfirm의 저장 로직과 XServerDisplayActivity의 DXVK_FRAME_RATE
        // 적용 코드는 이미 있었고, 이 연결 부분만 빠져 있었음.
        // BTConfirm 람다가 캡처할 수 있도록 final로 선언
        final EditText etFrameRateLimit = view.findViewById(R.id.ETFrameRateLimit);
        etFrameRateLimit.setText(String.valueOf(preferences.getInt("frame_rate_limit", 120)));

        final SwitchCompat cbGamingDeviceUnlimitedFPS = view.findViewById(R.id.CBGamingDeviceUnlimitedFPS);
        cbGamingDeviceUnlimitedFPS.setChecked(preferences.getBoolean("gaming_device_unlimited_fps", false));

        // The number field is meaningless while the cap is disabled, so grey it out
        // 제한 해제 상태에서는 입력칸이 의미가 없으므로 비활성화
        etFrameRateLimit.setEnabled(!cbGamingDeviceUnlimitedFPS.isChecked());
        cbGamingDeviceUnlimitedFPS.setOnCheckedChangeListener(
                (v2, isChecked) -> etFrameRateLimit.setEnabled(!isChecked));

        // Initialize the xinput toggle checkbox
        cbXinputToggle = view.findViewById(R.id.CBXinputToggle);
        cbXinputToggle.setChecked(preferences.getBoolean("xinput_toggle", false));

        // Initialize the Touchscreen mode toggle
        cbXTouchscreenToggle = view.findViewById(R.id.CBXTouchscreenToggle);
        cbXTouchscreenToggle.setChecked(preferences.getBoolean("touchscreen_toggle", false));



        // Initialize the "Configure Analog Sticks" button
        Button btConfigureAnalogSticks = view.findViewById(R.id.BTConfigureAnalogSticks);
        btConfigureAnalogSticks.setOnClickListener(v -> showAnalogStickConfigDialog());


        // Configure Frontend Export Path button

        Button btnChooseFrontendExportPath = view.findViewById(R.id.BTChooseFrontendExportPath);
        TextView tvFrontendExportPath = view.findViewById(R.id.TVFrontendExportPath);

        // Get the saved export path from SharedPreferences or use the default
        String savedUriString = preferences.getString("frontend_export_uri", null);
        if (savedUriString == null) {
            // No saved path, set default path
            tvFrontendExportPath.setText(DEFAULT_EXPORT_PATH);
        } else {
            // Parse and display the saved URI path
            Uri savedUri = Uri.parse(savedUriString);
            String displayPath = FileUtils.getFilePathFromUri(getContext(), savedUri);
            tvFrontendExportPath.setText(displayPath != null ? displayPath : savedUriString);
        }

        // Set the click listener for the "Choose Frontend Export Path" button
        btnChooseFrontendExportPath.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); // Launch File Picker for directory selection
            startActivityForResult(intent, REQUEST_CODE_FRONTEND_EXPORT_PATH);
        });

        final Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        loadBox86_64PresetSpinners(view, sBox64Preset);

        final Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);

        sMIDISoundFont.setPopupBackgroundResource(R.drawable.neon_popup_bg);

        final View btInstallSF = view.findViewById(R.id.BTInstallSF);
        final View btRemoveSF = view.findViewById(R.id.BTRemoveSF);

        MidiManager.loadSFSpinnerWithoutDisabled(sMIDISoundFont);
        btInstallSF.setOnClickListener(v -> {
            installSoundFontCallback = uri -> {
                PreloaderDialog dialog = new PreloaderDialog(requireActivity());
                dialog.showOnUiThread(R.string.installing_content);
                MidiManager.installSF2File(context, uri, new MidiManager.OnSoundFontInstalledCallback() {
                    @Override
                    public void onSuccess() {
                        dialog.closeOnUiThread();
                        requireActivity().runOnUiThread(() -> {
                            ContentDialog.alert(context, R.string.sound_font_installed_success, null);
                            MidiManager.loadSFSpinnerWithoutDisabled(sMIDISoundFont);
                        });
                    }

                    @Override
                    public void onFailed(int reason) {
                        dialog.closeOnUiThread();
                        int resId = switch (reason) {
                            case MidiManager.ERROR_BADFORMAT -> R.string.sound_font_bad_format;
                            case MidiManager.ERROR_EXIST -> R.string.sound_font_already_exist;
                            default -> R.string.sound_font_installed_failed;
                        };
                        requireActivity().runOnUiThread(() -> ContentDialog.alert(context, resId, null));
                    }
                });
            };

            // Open the file picker with the request code for SoundFont installation
            openFile(REQUEST_CODE_INSTALL_SOUNDFONT);
        });

        btRemoveSF.setOnClickListener(v -> {
            if (sMIDISoundFont.getSelectedItemPosition() != 0) {
                ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_sound_font, () -> {
                    if (MidiManager.removeSF2File(context, sMIDISoundFont.getSelectedItem().toString())) {
                        AppUtils.showToast(context, R.string.sound_font_removed_success);
                        MidiManager.loadSFSpinnerWithoutDisabled(sMIDISoundFont);
                    } else
                        AppUtils.showToast(context, R.string.sound_font_removed_failed);
                });
            } else
                AppUtils.showToast(context, R.string.cannot_remove_default_sound_font);
        });

        final SwitchCompat cbUseDRI3 = view.findViewById(R.id.CBUseDRI3);
        cbUseDRI3.setChecked(preferences.getBoolean("use_dri3", true));

        final SwitchCompat cbEnableWineDebug = view.findViewById(R.id.CBEnableWineDebug);
        cbEnableWineDebug.setChecked(preferences.getBoolean("enable_wine_debug", false));

        final ArrayList<String> wineDebugChannels = new ArrayList<>(Arrays.asList(preferences.getString("wine_debug_channels", DEFAULT_WINE_DEBUG_CHANNELS).split(",")));
        loadWineDebugChannels(view, wineDebugChannels);

        final SwitchCompat cbEnableBox86_64Logs = view.findViewById(R.id.CBEnableBox86_64Logs);
        cbEnableBox86_64Logs.setChecked(preferences.getBoolean("enable_box86_64_logs", false));

        final TextView tvCursorSpeed = view.findViewById(R.id.TVCursorSpeed);
        final SeekBar sbCursorSpeed = view.findViewById(R.id.SBCursorSpeed);
        sbCursorSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvCursorSpeed.setText(progress+"%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sbCursorSpeed.setProgress((int)(preferences.getFloat("cursor_speed", 1.0f) * 100));

        final SwitchCompat cbEnableFileProvider = view.findViewById(R.id.CBEnableFileProvider);
        final View btHelpFileProvider = view.findViewById(R.id.BTHelpFileProvider);

        cbEnableFileProvider.setChecked(preferences.getBoolean("enable_file_provider", true));
        cbEnableFileProvider.setOnClickListener(v -> AppUtils.showToast(context, R.string.take_effect_next_startup));
        btHelpFileProvider.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_file_provider));

        final SwitchCompat cbOpenInBrowser = view.findViewById(R.id.CBOpenWithAndroidBrowser);
        cbOpenInBrowser.setChecked(preferences.getBoolean("open_with_android_browser", false));

        final SwitchCompat cbShareClipboard = view.findViewById(R.id.CBShareAndroidClipboard);
        cbShareClipboard.setChecked(preferences.getBoolean("share_android_clipboard", false));

        final SwitchCompat CBEnablePebLogs = view.findViewById(R.id.CBEnablePebLogs);
        CBEnablePebLogs.setChecked(preferences.getBoolean("enable_peb_logs", false));

        view.findViewById(R.id.BTReInstallImagefs).setOnClickListener(v -> {
            ContentDialog.confirm(context, R.string.do_you_want_to_reinstall_imagefs, () -> ImageFsInstaller.installFromAssets((MainActivity) getActivity()));
        });

        // Add backup button
        Button btnBackupData = view.findViewById(R.id.BTBackupData);
        btnBackupData.setOnClickListener(v -> {
            showBackupConfirmationDialog();
        });

        // Add restore button
        Button btnRestoreData = view.findViewById(R.id.BTRestoreData);
        btnRestoreData.setOnClickListener(v -> {
            selectBackupFileForRestore();
        });

        // Shared save folder: back up now / restore one of the backups
        // 공용 세이브 폴더: 지금 백업 / 백업 중 하나로 복원
        view.findViewById(R.id.BTSaveBackup).setOnClickListener(v -> {
            Context app = context.getApplicationContext();
            runSaveTask(() -> {
                File out = com.winlator.cmod.container.SharedSaves.backup(app, null);
                return out != null ? app.getString(R.string.save_backup_done, out.getName())
                        : app.getString(R.string.save_backup_empty);
            });
        });
        view.findViewById(R.id.BTSaveRestore).setOnClickListener(v -> pickSaveBackup());

        // Saves to / from other apps: the target app is asked first, the size is
        // shown before writing; an import backs the current saves up first
        // 다른 앱과 세이브 주고받기: 먼저 대상 앱을 묻고, 쓰기 전에 크기를 보여 줌.
        // 가져오기는 먼저 현재 세이브를 백업함
        view.findViewById(R.id.BTSaveExport).setOnClickListener(v -> {
            if (getActivity() != null) com.winlator.cmod.container.SaveTransferUi.exportAll(getActivity());
        });
        view.findViewById(R.id.BTSaveImport).setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, REQUEST_CODE_SAVE_IMPORT);
        });

        // Steam library on the SD card (E:): saved at once, used by the next download
        // SD카드(E:)의 스팀 라이브러리: 바로 저장하고 다음 다운로드부터 씀
        final SwitchCompat cbSteamLibrarySd = view.findViewById(R.id.CBSteamLibrarySd);
        cbSteamLibrarySd.setChecked(preferences.getBoolean(com.winlator.cmod.steam.SteamGameInstaller.PREF_LIBRARY_SD, false));
        cbSteamLibrarySd.setOnCheckedChangeListener((b, checked) -> {
            if (checked && com.winlator.cmod.steam.SteamGameInstaller.getSdLibraryDir(context) == null) {
                // No card (or a debug build): nothing to switch to
                // 카드가 없음(또는 디버그 빌드): 바꿀 곳이 없음
                b.setChecked(false);
                AppUtils.showToast(context, R.string.steam_library_sd_missing);
                return;
            }
            preferences.edit().putBoolean(com.winlator.cmod.steam.SteamGameInstaller.PREF_LIBRARY_SD, checked).apply();
        });

        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            SharedPreferences.Editor editor = preferences.edit();

            editor.putBoolean("dark_mode", cbDarkMode.isChecked());
            editor.putString("box64_preset", Box86_64PresetManager.getSpinnerSelectedId(sBox64Preset));
            editor.putBoolean("use_dri3", cbUseDRI3.isChecked());
            editor.putInt("frame_rate_limit", etFrameRateLimit.getText().toString().isEmpty() ? 120 : Integer.parseInt(etFrameRateLimit.getText().toString()));
            editor.putBoolean("gaming_device_unlimited_fps", cbGamingDeviceUnlimitedFPS.isChecked());
            editor.putFloat("cursor_speed", sbCursorSpeed.getProgress() / 100.0f);
            editor.putBoolean("enable_wine_debug", cbEnableWineDebug.isChecked());
            editor.putBoolean("enable_box86_64_logs", cbEnableBox86_64Logs.isChecked());
            editor.putBoolean("xinput_toggle", cbXinputToggle.isChecked()); // Save xinput toggle state
            editor.putBoolean("touchscreen_toggle", cbXTouchscreenToggle.isChecked()); // Save touchscreen toggle state
            editor.putBoolean("enable_file_provider", cbEnableFileProvider.isChecked());
            editor.putBoolean("open_with_android_browser", cbOpenInBrowser.isChecked());
            editor.putBoolean("share_android_clipboard", cbShareClipboard.isChecked());
            editor.putBoolean("enable_peb_logs", CBEnablePebLogs.isChecked());





            if (!wineDebugChannels.isEmpty()) {
                editor.putString("wine_debug_channels", String.join(",", wineDebugChannels));
            } else if (preferences.contains("wine_debug_channels")) {
                editor.remove("wine_debug_channels");
            }
            else if (preferences.contains("wine_debug_channels")) editor.remove("wine_debug_channels");

            editor.putBoolean("legacy_mode_enabled", enableLegacyInputMode); // Save the 7.1.2 legacy mode state

            if (editor.commit()) {
                NavigationView navigationView = getActivity().findViewById(R.id.NavigationView);
                navigationView.setCheckedItem(R.id.main_menu_containers);
                FragmentManager fragmentManager = getParentFragmentManager();
                fragmentManager.beginTransaction()
                        .replace(R.id.FLFragmentContainer, new ContainersFragment())
                        .commit();
            }
        });



        return view;
    }

    // The checkbox must always mirror the real permission, never the other way round.
    // Without this, turning the permission off in system settings leaves the box
    // checked - the same misrepresentation in reverse.
    // 체크박스는 항상 실제 권한 상태를 따라가야 하며 그 반대가 되면 안 됨.
    // 이게 없으면 시스템 설정에서 권한을 꺼도 체크가 켜진 채로 남아, 방향만 반대인
    // 같은 거짓말이 됨
    private void syncUsbStorageCheckbox() {
        if (cbUsbStorageAccess == null) return;

        boolean granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || Environment.isExternalStorageManager();

        suppressUsbStorageCallback = true;
        cbUsbStorageAccess.setChecked(granted);
        suppressUsbStorageCallback = false;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-read after the user comes back from the system permission screen.
        // 사용자가 시스템 권한 화면에서 돌아온 뒤 다시 읽음
        syncUsbStorageCheckbox();
    }

    // Opens / closes one folding section: its fold row and its label both toggle
    // the body; the row text follows ("▼ unfold" / "▲ fold")
    // 접히는 구역 하나를 열고 닫음: 접기 줄과 구역 이름 모두 본문을 여닫고, 줄 글자가
    // 따라 바뀜("▼ 펼치기" / "▲ 접기")
    private static void setupFold(View root, int rowId, int bodyId, int labelId) {
        final TextView row = root.findViewById(rowId);
        final View body = root.findViewById(bodyId);
        View label = root.findViewById(labelId);
        View.OnClickListener toggle = v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            row.setText(open ? R.string.detail_fold : R.string.detail_unfold);
        };
        row.setOnClickListener(toggle);
        label.setOnClickListener(toggle);
    }

    private void updateTheme(boolean isDarkMode) {
        if (isDarkMode) {
            getActivity().setTheme(R.style.Theme_App_Neon_Dark);
        } else {
            getActivity().setTheme(R.style.Theme_App_Neon_Light);
        }

        // Recreate the activity to apply the new theme
        getActivity().recreate();
    }


    private void loadBox86_64PresetSpinners(View view, final Spinner sBox64Preset) {
        final ArrayMap<String, Spinner> spinners = new ArrayMap<String, Spinner>() {{
            put("box64", sBox64Preset);
        }};
        final Context context = getContext();

        Callback<String> updateSpinner = (prefix) -> {
            Box86_64PresetManager.loadSpinner(prefix, spinners.get(prefix), preferences.getString(prefix+"_preset", Box86_64Preset.COMPATIBILITY));
        };

        Callback<String> onAddPreset = (prefix) -> {
            Box86_64EditPresetDialog dialog = new Box86_64EditPresetDialog(context, prefix, null);
            dialog.setOnConfirmCallback(() -> updateSpinner.call(prefix));
            dialog.show();
        };

        Callback<String> onEditPreset = (prefix) -> {
            Box86_64EditPresetDialog dialog = new Box86_64EditPresetDialog(context, prefix, Box86_64PresetManager.getSpinnerSelectedId(spinners.get(prefix)));
            dialog.setOnConfirmCallback(() -> updateSpinner.call(prefix));
            dialog.show();
        };

        Callback<String> onDuplicatePreset = (prefix) -> ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_preset, () -> {
            Spinner spinner = spinners.get(prefix);
            Box86_64PresetManager.duplicatePreset(prefix, context, Box86_64PresetManager.getSpinnerSelectedId(spinner));
            updateSpinner.call(prefix);
            spinner.setSelection(spinner.getCount()-1);
        });

        Callback<String> onRemovePreset = (prefix) -> {
            final String presetId = Box86_64PresetManager.getSpinnerSelectedId(spinners.get(prefix));
            if (!presetId.startsWith(Box86_64Preset.CUSTOM)) {
                AppUtils.showToast(context, R.string.you_cannot_remove_this_preset);
                return;
            }
            ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_preset, () -> {
                Box86_64PresetManager.removePreset(prefix, context, presetId);
                updateSpinner.call(prefix);
            });
        };

        updateSpinner.call("box64");

        view.findViewById(R.id.BTAddBox64Preset).setOnClickListener((v) -> onAddPreset.call("box64"));
        view.findViewById(R.id.BTEditBox64Preset).setOnClickListener((v) -> onEditPreset.call("box64"));
        view.findViewById(R.id.BTDuplicateBox64Preset).setOnClickListener((v) -> onDuplicatePreset.call("box64"));
        view.findViewById(R.id.BTRemoveBox64Preset).setOnClickListener((v) -> onRemovePreset.call("box64"));
    }


    private void openFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        // Start activity for result based on the provided request code
        getActivity().startActivityFromFragment(this, intent, requestCode);
    }


    private void loadWineDebugChannels(final View view, final ArrayList<String> debugChannels) {
        final Context context = getContext();
        LinearLayout container = view.findViewById(R.id.LLWineDebugChannels);
        container.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(context);
        View itemView = inflater.inflate(R.layout.wine_debug_channel_list_item, container, false);
        itemView.findViewById(R.id.TextView).setVisibility(View.GONE);
        itemView.findViewById(R.id.BTRemove).setVisibility(View.GONE);

        View addButton = itemView.findViewById(R.id.BTAdd);
        addButton.setVisibility(View.VISIBLE);
        addButton.setOnClickListener((v) -> {
            JSONArray jsonArray = null;
            try {
                jsonArray = new JSONArray(FileUtils.readString(context, "wine_debug_channels.json"));
            }
            catch (JSONException e) {}

            final String[] items = ArrayUtils.toStringArray(jsonArray);
            ContentDialog.showMultipleChoiceList(context, R.string.wine_debug_channel, items, (selectedPositions) -> {
                for (int selectedPosition : selectedPositions) if (!debugChannels.contains(items[selectedPosition])) debugChannels.add(items[selectedPosition]);
                loadWineDebugChannels(view, debugChannels);
            });
        });

        View resetButton = itemView.findViewById(R.id.BTReset);
        resetButton.setVisibility(View.VISIBLE);
        resetButton.setOnClickListener((v) -> {
            debugChannels.clear();
            debugChannels.addAll(Arrays.asList(DEFAULT_WINE_DEBUG_CHANNELS.split(",")));
            loadWineDebugChannels(view, debugChannels);
        });
        container.addView(itemView);

        for (int i = 0; i < debugChannels.size(); i++) {
            itemView = inflater.inflate(R.layout.wine_debug_channel_list_item, container, false);
            TextView textView = itemView.findViewById(R.id.TextView);
            textView.setText(debugChannels.get(i));
            final int index = i;
            itemView.findViewById(R.id.BTRemove).setOnClickListener((v) -> {
                debugChannels.remove(index);
                loadWineDebugChannels(view, debugChannels);
            });
            container.addView(itemView);
        }
    }

    public static void resetEmulatorsVersion(AppCompatActivity activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove("current_box64_version");
        editor.remove("current_wowbox64_version");
        editor.remove("current_fexcore_version");
        editor.apply();
    }



    private void showBackupConfirmationDialog() {
        new AlertDialog.Builder(getContext())
                .setTitle("Backup Data")
                .setMessage("Do you want to create a backup of the app's data directory?")
                .setPositiveButton("Yes", (dialog, which) -> backupAppData())
                .setNegativeButton("No", null)
                .show();
    }

    private void backupAppData() {
        File dataDir = getContext().getFilesDir().getParentFile(); // App's data directory
        File backupFile = new File(Environment.getExternalStorageDirectory(), "app_data_backup.tar");

        preloaderDialog.showOnUiThread(R.string.backing_up_data);

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        ExecutorService compressionExecutor = Executors.newFixedThreadPool(availableProcessors);

        compressionExecutor.execute(() -> {
            try {
                TarCompressorUtils.archive(new File[]{dataDir}, backupFile, file -> {
                    // Exclude the problematic directory
                    String excludePath = "imagefs/tmp/.sysvshm";
                    return !file.getAbsolutePath().contains(excludePath);
                });
                getActivity().runOnUiThread(() -> {
                    preloaderDialog.closeOnUiThread();
                    AppUtils.showToast(getContext(), "Backup completed: " + backupFile.getPath());
                });
            } catch (Exception e) {
                getActivity().runOnUiThread(() -> {
                    preloaderDialog.closeOnUiThread();
                    AppUtils.showToast(getContext(), "Backup failed.");
                });
            }
        });
    }



    private void selectBackupFileForRestore() {
        isRestoreAction = true; // Set the flag to indicate a restore operation
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();

            if (uri != null) {
                switch (requestCode) {
                    // Case for File Picker to restore data
                    case MainActivity.OPEN_FILE_REQUEST_CODE:
                        if (isRestoreAction) {
                            restoreAppData(uri);
                            isRestoreAction = false;  // Reset the flag
                        }
                        break;

                    // Case for FilePicker to select frontend export path
                    case REQUEST_CODE_FRONTEND_EXPORT_PATH:
                        // Save the selected URI as a string in SharedPreferences
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putString("frontend_export_uri", uri.toString());
                        editor.apply();

                        // Take persistable URI permission
                        try {
                            // Take persistable URI permission with explicit flags
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                        } catch (SecurityException e) {
                            AppUtils.showToast(getContext(), "Unable to take persistable permissions: " + e.getMessage());
                        }

                        // Convert the URI to an absolute path and display it
                        String fullPath = FileUtils.getFilePathFromUri(getContext(), uri);

                        // Update the TextView with the absolute path or URI string if conversion fails
                        TextView tvFrontendExportPath = getView().findViewById(R.id.TVFrontendExportPath);
                        tvFrontendExportPath.setText(fullPath != null ? fullPath : uri.toString());
                        break;

                    // Case for installing a SoundFont
                    case REQUEST_CODE_INSTALL_SOUNDFONT:
                        if (installSoundFontCallback != null) {
                            try {
                                installSoundFontCallback.call(uri);
                            } catch (Exception e) {
                                AppUtils.showToast(getContext(), R.string.unable_to_install_soundfont);
                            } finally {
                                installSoundFontCallback = null;
                            }
                        }
                        break;

                    // Save archive picked for import / 가져올 세이브 묶음을 고름
                    case REQUEST_CODE_SAVE_IMPORT:
                        if (getActivity() != null) com.winlator.cmod.container.SaveTransferUi.importPicked(getActivity(), uri);
                        break;

                    // Add future cases here for other request codes...
                    default:
                        break;
                }
            }
        }
    }

    private void restoreAppData(Uri backupUri) {
        Activity activity = getActivity();
        if (activity == null) return;
        // The data restore replaces every container and the shared save folder,
        // so the saves are backed up first (Download/.EZlator/SaveBackups)
        // 데이터 복원은 모든 컨테이너와 공용 세이브 폴더를 덮어쓰므로, 세이브를 먼저
        // 백업함(Download/.EZlator/SaveBackups)
        Context app = activity.getApplicationContext();
        PreloaderDialog preloader = new PreloaderDialog(activity);
        preloader.showOnUiThread(R.string.save_working);
        Executors.newSingleThreadExecutor().execute(() -> {
            com.winlator.cmod.container.SharedSaves.backup(app, "before-data-restore");
            activity.runOnUiThread(() -> {
                preloader.close();
                Intent intent = new Intent(activity, RestoreActivity.class);
                intent.setData(backupUri);
                startActivity(intent);
                activity.finish(); // Close the main activity
            });
        });
    }

    // Runs a save backup / restore job off the UI thread and shows its result
    // 세이브 백업 / 복원 작업을 UI 스레드 밖에서 실행하고 결과를 보여 줌
    private void runSaveTask(java.util.function.Supplier<String> task) {
        Activity activity = getActivity();
        if (activity == null) return;
        Context app = activity.getApplicationContext();
        PreloaderDialog preloader = new PreloaderDialog(activity);
        preloader.showOnUiThread(R.string.save_working);
        Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            try {
                message = task.get();
            } catch (Exception e) {
                android.util.Log.e("SettingsFragment", "save task failed", e);
                message = app.getString(R.string.save_restore_failed);
            }
            final String result = message;
            activity.runOnUiThread(() -> {
                preloader.close();
                AppUtils.showToast(app, result);
            });
        });
    }

    // Lists the save backups (newest first); the picked one is restored after a
    // confirmation
    // 세이브 백업 목록(최신순)을 보여 주고, 고른 것을 확인 후 복원함
    private void pickSaveBackup() {
        Context context = getContext();
        if (context == null) return;
        File[] backups = com.winlator.cmod.container.SharedSaves.listBackups();
        if (backups.length == 0) {
            AppUtils.showToast(context, R.string.save_restore_none);
            return;
        }
        String[] names = new String[backups.length];
        for (int i = 0; i < backups.length; i++) names[i] = backups[i].getName();
        new AlertDialog.Builder(context)
                .setTitle(R.string.save_restore)
                .setItems(names, (d, which) -> {
                    File picked = backups[which];
                    new AlertDialog.Builder(context)
                            .setMessage(context.getString(R.string.save_restore_confirm, picked.getName()))
                            .setPositiveButton(android.R.string.ok, (d2, w2) -> {
                                Context app = context.getApplicationContext();
                                runSaveTask(() -> com.winlator.cmod.container.SharedSaves.restore(app, picked)
                                        ? app.getString(R.string.save_restore_done)
                                        : app.getString(R.string.save_restore_failed));
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .show();
    }



    private void showAnalogStickConfigDialog() {
        // Inflate the dialog layout
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View dialogView = inflater.inflate(R.layout.analog_stick_config_dialog, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setView(dialogView);
        builder.setTitle("Configure Analog Sticks");
        builder.setCancelable(false);

        // Initialize UI elements
        SeekBar sbLeftDeadzone = dialogView.findViewById(R.id.SBLeftDeadzone);
        TextView tvLeftDeadzone = dialogView.findViewById(R.id.TVLeftDeadzone);

        SeekBar sbLeftSensitivity = dialogView.findViewById(R.id.SBLeftSensitivity);
        TextView tvLeftSensitivity = dialogView.findViewById(R.id.TVLeftSensitivity);

        SeekBar sbRightDeadzone = dialogView.findViewById(R.id.SBRightDeadzone);
        TextView tvRightDeadzone = dialogView.findViewById(R.id.TVRightDeadzone);

        SeekBar sbRightSensitivity = dialogView.findViewById(R.id.SBRightSensitivity);
        TextView tvRightSensitivity = dialogView.findViewById(R.id.TVRightSensitivity);

        SwitchCompat cbInvertLeftX = dialogView.findViewById(R.id.CBInvertLeftStickX);
        SwitchCompat cbInvertLeftY = dialogView.findViewById(R.id.CBInvertLeftStickY);
        SwitchCompat cbInvertRightX = dialogView.findViewById(R.id.CBInvertRightStickX);
        SwitchCompat cbInvertRightY = dialogView.findViewById(R.id.CBInvertRightStickY);

        // New checkbox for square deadzone
        SwitchCompat cbLeftStickSquareDeadzone = dialogView.findViewById(R.id.CBLeftStickSquareDeadzone);

        // Load current preferences
        float currentDeadzoneLeft = preferences.getFloat(PreferenceKeys.DEADZONE_LEFT, 0.1f) * 100; // Convert to percentage
        float currentDeadzoneRight = preferences.getFloat(PreferenceKeys.DEADZONE_RIGHT, 0.1f) * 100;
        float currentSensitivityLeft = preferences.getFloat(PreferenceKeys.SENSITIVITY_LEFT, 1.0f) * 100; // Convert to percentage
        float currentSensitivityRight = preferences.getFloat(PreferenceKeys.SENSITIVITY_RIGHT, 1.0f) * 100;
        boolean squareDeadzoneLeft = preferences.getBoolean(PreferenceKeys.SQUARE_DEADZONE_LEFT, false);

        boolean invertLeftX = preferences.getBoolean(PreferenceKeys.INVERT_LEFT_X, false);
        boolean invertLeftY = preferences.getBoolean(PreferenceKeys.INVERT_LEFT_Y, false);
        boolean invertRightX = preferences.getBoolean(PreferenceKeys.INVERT_RIGHT_X, false);
        boolean invertRightY = preferences.getBoolean(PreferenceKeys.INVERT_RIGHT_Y, false);

        // Set initial values
        sbLeftDeadzone.setProgress((int) currentDeadzoneLeft);
        tvLeftDeadzone.setText("Deadzone: " + sbLeftDeadzone.getProgress() + "%");

        sbLeftSensitivity.setProgress((int) currentSensitivityLeft);
        tvLeftSensitivity.setText("Sensitivity: " + sbLeftSensitivity.getProgress() + "%");

        sbRightDeadzone.setProgress((int) currentDeadzoneRight);
        tvRightDeadzone.setText("Deadzone: " + sbRightDeadzone.getProgress() + "%");

        sbRightSensitivity.setProgress((int) currentSensitivityRight);
        tvRightSensitivity.setText("Sensitivity: " + sbRightSensitivity.getProgress() + "%");

        cbInvertLeftX.setChecked(invertLeftX);
        cbInvertLeftY.setChecked(invertLeftY);
        cbInvertRightX.setChecked(invertRightX);
        cbInvertRightY.setChecked(invertRightY);

        cbLeftStickSquareDeadzone.setChecked(squareDeadzoneLeft);

        // Set listeners to update TextViews as SeekBars change
        sbLeftDeadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLeftDeadzone.setText("Deadzone: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbLeftSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLeftSensitivity.setText("Sensitivity: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbRightDeadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvRightDeadzone.setText("Deadzone: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbRightSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvRightSensitivity.setText("Sensitivity: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Set up the dialog buttons
        builder.setPositiveButton("Save", (dialog, which) -> {
            // Retrieve and save the updated settings
            float newDeadzoneLeft = sbLeftDeadzone.getProgress() / 100.0f;
            float newDeadzoneRight = sbRightDeadzone.getProgress() / 100.0f;
            float newSensitivityLeft = sbLeftSensitivity.getProgress() / 100.0f;
            float newSensitivityRight = sbRightSensitivity.getProgress() / 100.0f;

            boolean newInvertLeftX = cbInvertLeftX.isChecked();
            boolean newInvertLeftY = cbInvertLeftY.isChecked();
            boolean newInvertRightX = cbInvertRightX.isChecked();
            boolean newInvertRightY = cbInvertRightY.isChecked();

            // Save to SharedPreferences
            SharedPreferences.Editor editor = preferences.edit();
            editor.putFloat(PreferenceKeys.DEADZONE_LEFT, newDeadzoneLeft);
            editor.putFloat(PreferenceKeys.DEADZONE_RIGHT, newDeadzoneRight);
            editor.putFloat(PreferenceKeys.SENSITIVITY_LEFT, newSensitivityLeft);
            editor.putFloat(PreferenceKeys.SENSITIVITY_RIGHT, newSensitivityRight);
            editor.putBoolean(PreferenceKeys.INVERT_LEFT_X, newInvertLeftX);
            editor.putBoolean(PreferenceKeys.INVERT_LEFT_Y, newInvertLeftY);
            editor.putBoolean(PreferenceKeys.INVERT_RIGHT_X, newInvertRightX);
            editor.putBoolean(PreferenceKeys.INVERT_RIGHT_Y, newInvertRightY);
            editor.putBoolean(PreferenceKeys.SQUARE_DEADZONE_LEFT, cbLeftStickSquareDeadzone.isChecked());
            editor.apply();

            // We'll assume ExternalController instances listen to preference changes
        });

        builder.setNegativeButton("Cancel", null);

        // Create and show the dialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }

}
