package com.winlator.cmod.contentdialog;



import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import androidx.appcompat.widget.SwitchCompat;
// CheckBox -> SwitchCompat, same reasoning as SettingsFragment/
// ContainerDetailFragment.
// CheckBox -> SwitchCompat. SettingsFragment/ContainerDetailFragment와
// 같은 이유임
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.ContainerDetailFragment;
import com.winlator.cmod.R;
import com.winlator.cmod.ShortcutsFragment;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.box86_64.rc.RCManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.EnvVarsView;
import com.winlator.cmod.winhandler.WinHandler;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import kotlin.random.Random;

public class ShortcutSettingsDialog extends ContentDialog {
    private final ShortcutsFragment fragment;
    private final Shortcut shortcut;
    private InputControlsManager inputControlsManager;
    private TextView tvGraphicsDriverVersion;
    private String box64Version;



    public ShortcutSettingsDialog(ShortcutsFragment fragment, Shortcut shortcut) {
        super(fragment.getContext(), R.layout.shortcut_settings_dialog);
        this.fragment = fragment;
        this.shortcut = shortcut;
        setTitle(shortcut.name);
        setIcon(R.drawable.icon_settings);

        // Initialize the ContentsManager
        ContainerManager containerManager = shortcut.container.getManager();

        createContentView();
    }

    private void createContentView() {
        final Context context = fragment.getContext();
        inputControlsManager = new InputControlsManager(context);
        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);

        applyDynamicStyles(findViewById(R.id.LLContent), isDarkMode);

        // Initialize the turnip version TextView
        tvGraphicsDriverVersion = findViewById(R.id.TVGraphicsDriverVersion);

        // Get the shared preferences and check the legacy mode status
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        final EditText etName = findViewById(R.id.ETName);
        // Folder of the exe below the name (Windows form, quotes and file name removed)
        // 이름 아래에 exe가 있는 폴더를 보여 줌(Windows 형식, 따옴표와 파일 이름은 뺌)
        String exePath = shortcut.path != null ? shortcut.path.trim() : "";
        if (exePath.length() >= 2 && exePath.startsWith("\"") && exePath.endsWith("\"")) exePath = exePath.substring(1, exePath.length() - 1);
        int lastSep = exePath.lastIndexOf('\\');
        String exeFolder = lastSep > 0 ? exePath.substring(0, lastSep) : exePath;
        ((TextView) findViewById(R.id.TVShortcutFolder)).setText(exeFolder);
        int folderVisibility = exeFolder.isEmpty() ? View.GONE : View.VISIBLE;
        findViewById(R.id.TVShortcutFolder).setVisibility(folderVisibility);
        findViewById(R.id.TVShortcutFolderLabel).setVisibility(folderVisibility);
        etName.setText(shortcut.name);

        // Container picker: current container preselected. The move itself
        // happens in moveToContainer() after the other settings are saved.
        // 컨테이너 선택: 현재 컨테이너가 미리 선택됨. 실제 이동은 다른 설정을
        // 저장한 뒤 moveToContainer()에서 함
        final Spinner sShortcutContainer = findViewById(R.id.SShortcutContainer);
        final ArrayList<Container> allContainers = new ContainerManager(context).getContainers();
        // Steam shortcuts: containers whose Wine/Proton cannot run the Steam agent
        // (Proton 10.0-4, see SteamWineSupport) are hidden. The container the
        // shortcut is in now stays listed so the current choice is kept as it is
        // 스팀 바로가기: 스팀 에이전트가 동작하지 않는 Wine/Proton(Proton 10.0-4,
        // SteamWineSupport 참고)의 컨테이너는 숨김. 바로가기가 지금 있는 컨테이너는
        // 현재 선택이 그대로 유지되도록 목록에 남김
        if (!shortcut.getExtra("steamAppId").isEmpty()) {
            allContainers.removeIf(c -> c.id != shortcut.container.id
                    && com.winlator.cmod.steam.SteamWineSupport.isBlocked(c.getWineVersion()));
        }
        ArrayList<String> containerNames = new ArrayList<>();
        int currentIndex = 0;
        for (int i = 0; i < allContainers.size(); i++) {
            containerNames.add(allContainers.get(i).getName());
            if (allContainers.get(i).id == shortcut.container.id) currentIndex = i;
        }
        sShortcutContainer.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, containerNames));
        sShortcutContainer.setSelection(currentIndex);

        final EditText etExecArgs = findViewById(R.id.ETExecArgs);
        etExecArgs.setText(shortcut.getExtra("execArgs"));
        final EditText etSteamAppId = findViewById(R.id.ETSteamAppId);
        etSteamAppId.setText(shortcut.getExtra("steamAppId"));
        findViewById(R.id.BTSteamAppIdFind).setOnClickListener(v -> findSteamAppId(etSteamAppId));

        ContainerDetailFragment containerDetailFragment = new ContainerDetailFragment(shortcut.container.id);

        loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()), isDarkMode);


        final Spinner sGraphicsDriver = findViewById(R.id.SGraphicsDriver);
        
        final Spinner sDXWrapper = findViewById(R.id.SDXWrapper);

        final Spinner sDDrawrapper = findViewById(R.id.SDDrawrapper);

        final Spinner sBox64Version = findViewById(R.id.SBox64Version);
        
        ContentsManager contentsManager = new ContentsManager(context);
        
        contentsManager.syncContents();

        final View vGraphicsDriverConfig = findViewById(R.id.BTGraphicsDriverConfig);
        vGraphicsDriverConfig.setTag(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
        
        final View vDXWrapperConfig = findViewById(R.id.BTDXWrapperConfig);
        vDXWrapperConfig.setTag(shortcut.getExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig()));

        // Layer of the container currently picked in this dialog (not the one the
        // shortcut is in now): DXVK/VKD3D lists then match after an ARM64EC <->
        // X86_64 change. Reported: the list did not refresh
        // 이 창에서 지금 고른 컨테이너의 레이어임(숏컷이 지금 있는 컨테이너가 아님):
        // ARM64EC <-> X86_64를 바꾼 뒤에도 DXVK/VKD3D 목록이 맞게 됨. 보고: 목록이
        // 새로고침되지 않았음
        ContainerDetailFragment.setupDXWrapperSpinner(sDXWrapper, vDXWrapperConfig, () -> {
            int picked = sShortcutContainer.getSelectedItemPosition();
            Container c = picked >= 0 && picked < allContainers.size() ? allContainers.get(picked) : shortcut.container;
            return WineInfo.fromIdentifier(context, contentsManager, c.getWineVersion()).isArm64EC();
        });
        // A different layer cannot use the stored DXVK/VKD3D builds of the old
        // one, so the wrapper config falls back to the picked container's own
        // 다른 레이어는 예전 레이어의 DXVK/VKD3D 빌드를 쓸 수 없으므로, 래퍼 설정을
        // 고른 컨테이너 자체 설정으로 되돌림
        final boolean[] lastArm = {WineInfo.fromIdentifier(context, contentsManager, shortcut.container.getWineVersion()).isArm64EC()};
        sShortcutContainer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Container c = allContainers.get(position);
                boolean arm = WineInfo.fromIdentifier(context, contentsManager, c.getWineVersion()).isArm64EC();
                if (arm != lastArm[0]) {
                    lastArm[0] = arm;
                    vDXWrapperConfig.setTag(c.getDXWrapperConfig());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        ContainerDetailFragment.setupDDrawSpinner(sDDrawrapper, shortcut.getExtra("ddrawrapper", shortcut.container.getDDrawWrapper()));
        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig, shortcut.getExtra("graphicsDriver", shortcut.container.getGraphicsDriver()),
            shortcut.getExtra("dxwrapper", shortcut.container.getDXWrapper()));

        findViewById(R.id.BTHelpDXWrapper).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));

        final Spinner sAudioDriver = findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, shortcut.getExtra("audioDriver", shortcut.container.getAudioDriver()));
        final Spinner sEmulator = findViewById(R.id.SEmulator);
        AppUtils.setSpinnerSelectionFromIdentifier(sEmulator, shortcut.getExtra("emulator", shortcut.container.getEmulator()));
        final Spinner sEmulator64 = findViewById(R.id.SEmulator64);
        sEmulator64.setEnabled(false);
        final Spinner sMIDISoundFont = findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, shortcut.getExtra("midiSoundFont", shortcut.container.getMIDISoundFont()));

        FrameLayout fexcoreFL = findViewById(R.id.fexcoreFrame);
        String wineVersion = shortcut.container.getWineVersion();
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.isArm64EC()) {
            fexcoreFL.setVisibility(View.VISIBLE);
            sEmulator.setEnabled(true);
            sEmulator64.setSelection(0);
        }
        else {
            fexcoreFL.setVisibility(View.GONE);
            sEmulator.setEnabled(false);
            sEmulator.setSelection(1);
            sEmulator64.setSelection(1);
        }

        // Box64 frame: same rule as ContainerDetailFragment (hidden for ARM64EC
        // unless the DLL emulator is still Box64/WOWBox64 in old data)
        // Box64 영역: ContainerDetailFragment와 같은 규칙임(ARM64EC면 숨김. 단, 예전
        // 데이터로 DLL 에뮬레이터가 아직 Box64/WOWBox64인 경우는 보임)
        FrameLayout box64FL = findViewById(R.id.box86box64Frame);
        boolean usesBox64 = !wineInfo.isArm64EC()
                || "Box64".equalsIgnoreCase(String.valueOf(sEmulator.getSelectedItem()));
        box64FL.setVisibility(usesBox64 ? View.VISIBLE : View.GONE);

        loadBox64VersionSpinner(context, contentsManager, sBox64Version, wineInfo.isArm64EC());

        // Add this part to set the initial spinner selection based on the shortcut
        String currentBox64Version = shortcut.getExtra("box64Version", shortcut.container.getBox64Version());
        if (currentBox64Version != null) {
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, currentBox64Version);
        } else {
            // Default selection or use a preferred default version
            AppUtils.setSpinnerSelectionFromValue(sBox64Version, DefaultVersion.BOX64);
        }

        // Set OnItemSelectedListener for the Box64 version spinner
        sBox64Version.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedVersion = parent.getItemAtPosition(position).toString();
                box64Version = selectedVersion;  // Update the class-level variable
                // Update the shortcut extra immediately, or wait until saveData() is called
                shortcut.putExtra("box64Version", selectedVersion);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // This method must be implemented, even if it's empty.
                // Optional: You can handle the case where no item is selected, if needed.
            }
        });

        final SwitchCompat cbUseSecondaryExec = findViewById(R.id.CBUseSecondaryExec);
        final LinearLayout llSecondaryExecOptions = findViewById(R.id.LLSecondaryExecOptions);
        final EditText etSecondaryExec = findViewById(R.id.ETSecondaryExec);
        final EditText etExecDelay = findViewById(R.id.ETExecDelay);

        boolean useSecondaryExec = !shortcut.getExtra("secondaryExec", "").isEmpty();
        cbUseSecondaryExec.setChecked(useSecondaryExec);
        llSecondaryExecOptions.setVisibility(useSecondaryExec ? View.VISIBLE : View.GONE);
        etSecondaryExec.setText(shortcut.getExtra("secondaryExec"));
        etExecDelay.setText(shortcut.getExtra("execDelay", "0"));

        cbUseSecondaryExec.setOnCheckedChangeListener((buttonView, isChecked) -> {
            llSecondaryExecOptions.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        final SwitchCompat cbFullscreenStretched =  findViewById(R.id.CBFullscreenStretched);
        boolean fullscreenStretched = shortcut.getExtra("fullscreenStretched", "0").equals("1");
        cbFullscreenStretched.setChecked(fullscreenStretched);

        // Borderless window: default ON; turn off only if a game's image breaks
        // 테두리 없는 창: 기본 켜짐. 게임 화면에 문제가 생길 때만 끔
        final SwitchCompat cbBorderless = findViewById(R.id.CBBorderless);
        cbBorderless.setChecked(!"0".equals(shortcut.getExtra("borderless", "1")));



        final SwitchCompat cbForceFullscreen = findViewById(R.id.CBForceFullscreen);
        cbForceFullscreen.setChecked(shortcut.getExtra("forceFullscreen", "0").equals("1"));


        final Spinner sBox64Preset = findViewById(R.id.SBox64Preset);
        Box86_64PresetManager.loadSpinner("box64", sBox64Preset, shortcut.getExtra("box64Preset", shortcut.container.getBox64Preset()));

        final Spinner sFEXCoreVersion = findViewById(R.id.SFEXCoreVersion);
        FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion, shortcut);
        
        final Spinner sFEXCoreTSOPreset = findViewById(R.id.SFEXCoreTSOPreset);
        final Spinner sFEXCoreMultiBlock = findViewById(R.id.SFEXCoreMultiblock);
        final Spinner sFEXCoreX87ReducedPrecision = findViewById(R.id.SFEXCoreX87ReducedPrecision);
        
        FEXCoreManager.loadFEXCoreSettings(context, shortcut, sFEXCoreTSOPreset, sFEXCoreMultiBlock, sFEXCoreX87ReducedPrecision);

        final Spinner sRCFile = findViewById(R.id.SRCFile);
        final int[] rcfileIds = {0};
        RCManager manager = new RCManager(context);
        String rcfileId = shortcut.getExtra("rcfileId", String.valueOf(shortcut.container.getRCFileId()));
        RCManager.loadRCFileSpinner(manager, Integer.parseInt(rcfileId), sRCFile, id -> {
            rcfileIds[0] = id;
        });

        final Spinner sControlsProfile = findViewById(R.id.SControlsProfile);
        loadControlsProfileSpinner(sControlsProfile, shortcut.getExtra("controlsProfile", "0"));

        final SwitchCompat cbDisabledXInput = findViewById(R.id.CBDisabledXInput);
        // Set the initial value based on the shortcut extras
        boolean isXInputDisabled = shortcut.getExtra("disableXinput", "0").equals("1");
        cbDisabledXInput.setChecked(isXInputDisabled);

        final Runnable showGStreamerWorkaroundWarning = () -> ContentDialog.alert(context, R.string.enable_gstreamer_workaround_alert, null);

        final SwitchCompat cbGStreamerWorkaroundToggle = findViewById(R.id.CBGStreamerWorkaroundToggle);
        String isGStreamerWorkaroundEnabled = shortcut.getExtra("gstreamerWorkaround", "0");
        cbGStreamerWorkaroundToggle.setChecked(isGStreamerWorkaroundEnabled.equals("1") ? true : false);



        cbGStreamerWorkaroundToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && cbGStreamerWorkaroundToggle.isChecked())
                showGStreamerWorkaroundWarning.run();
        });


        final SwitchCompat cbSimTouchScreen = findViewById(R.id.CBTouchscreenMode);
        String isTouchScreenMode = shortcut.getExtra("simTouchScreen");
        cbSimTouchScreen.setChecked(isTouchScreenMode.equals("1") ? true : false);

        // Only components this version offers (a shortcut saved earlier can still carry
        // vcrun2015); saving writes the filtered list back
        // 이 버전이 제공하는 항목만 보여 줌(예전에 저장한 바로가기에는 vcrun2015가 남아 있을
        // 수 있음). 저장하면 걸러진 목록으로 다시 기록됨
        ContainerDetailFragment.createWinComponentsTabFromShortcut(this, getContentView(),
                Container.offeredWinComponents(shortcut.getExtra("wincomponents", shortcut.container.getWinComponents())), isDarkMode);

        final EnvVarsView envVarsView = createEnvVarsTab();

        // Order must match the TabItem order in shortcut_settings_dialog.xml
        // exactly - setupTabLayout() maps position i to tabResIds[i]. WINE is
        // new (the old static top section, now its own tab); LLTabEnvVars is
        // renamed LLTabRuntime (env vars + the merged System/Input Controls
        // sections moved out of Advanced).
        // shortcut_settings_dialog.xml의 TabItem 순서와 정확히 맞아야 함 -
        // setupTabLayout()이 위치 i를 tabResIds[i]에 그대로 매핑함. WINE은
        // 새로 생김(예전 상단 고정 구역이 이제 자기 탭이 됨); LLTabEnvVars는
        // LLTabRuntime으로 이름이 바뀜(환경변수 + Advanced에서 옮긴 합쳐진
        // System/Input Controls 구역)
        AppUtils.setupTabLayout(getContentView(), R.id.TabLayout,
                R.id.LLTabWine, R.id.LLTabWinComponents, R.id.LLTabRuntime, R.id.LLTabAdvanced);

        TabLayout tabLayout = findViewById(R.id.TabLayout);
        // The old light/dark tab drawables are no longer applied here;
        // the layout's neon_tab_bg follows the dialog theme (the dark one was a
        // grey strip in dark mode)
        // 예전 라이트/다크 탭 배경을 여기서 덮어쓰지 않음. 레이아웃의 neon_tab_bg가
        // 대화상자 테마를 따름(다크 쪽은 다크 모드에서 회색 띠로 보였음)

        // Per-shortcut language locale, see setupLocaleSpinner()
        // 바로가기별 언어 로캘, setupLocaleSpinner() 참고
        setupLocaleSpinner();

        findViewById(R.id.BTExtraArgsMenu).setOnClickListener((v) -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            popupMenu.inflate(R.menu.extra_args_popup_menu);
            popupMenu.setOnMenuItemClickListener((menuItem) -> {
                String value = String.valueOf(menuItem.getTitle());
                String execArgs = etExecArgs.getText().toString();
                if (!execArgs.contains(value)) etExecArgs.setText(!execArgs.isEmpty() ? execArgs + " " + value : value);
                return true;
            });
            popupMenu.show();
        });

        String selectedDriver = sGraphicsDriver.getSelectedItem().toString();
        List<String> sGraphicsItemsList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.graphics_driver_entries)));
        sGraphicsDriver.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, sGraphicsItemsList));
        AppUtils.setSpinnerSelectionFromValue(sGraphicsDriver, selectedDriver);

        final Spinner sStartupSelection = findViewById(R.id.SStartupSelection);
        sStartupSelection.setSelection(Integer.parseInt(shortcut.getExtra("startupSelection", String.valueOf(shortcut.container.getStartupSelection()))));

        final Spinner sSharpnessEffect = findViewById(R.id.SSharpnessEffect);
        final SeekBar sbSharpnessLevel = findViewById(R.id.SBSharpnessLevel);
        final SeekBar sbSharpnessDenoise = findViewById(R.id.SBSharpnessDenoise);
        final TextView tvSharpnessLevel = findViewById(R.id.TVSharpnessLevel);
        final TextView tvSharpnessDenoise = findViewById(R.id.TVSharpnessDenoise);

        AppUtils.setSpinnerSelectionFromValue(sSharpnessEffect, shortcut.getExtra("sharpnessEffect", "None"));

        sbSharpnessLevel.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessLevel", "100")));
        tvSharpnessLevel.setText(shortcut.getExtra("sharpnessLevel", "100") + "%");
        sbSharpnessLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessLevel.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        sbSharpnessDenoise.setProgress(Integer.parseInt(shortcut.getExtra("sharpnessDenoise", "100")));
        tvSharpnessDenoise.setText(shortcut.getExtra("sharpnessDenoise", "100") + "%");
        sbSharpnessDenoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSharpnessDenoise.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        final CPUListView cpuListView = findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(shortcut.getExtra("cpuList", shortcut.container.getCPUList(true)));

        setOnConfirmCallback(() -> {
            String name = etName.getText().toString().trim();
            boolean nameChanged = !shortcut.name.equals(name) && !name.isEmpty();

            // First, handle renaming if the name has changed
            if (nameChanged) {
                renameShortcut(name);
            }


            // Determine if renaming is needed
            boolean renamingSuccess = !nameChanged || new File(shortcut.file.getParent(), name + ".desktop").exists();

            if (renamingSuccess) {
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                String ddrawrapper = StringUtils.parseIdentifier(sDDrawrapper.getSelectedItem());
                String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                String emulator = StringUtils.parseIdentifier(sEmulator.getSelectedItem());
                String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
                String screenSize = containerDetailFragment.getScreenSize(getContentView());


                boolean disabledXInput = cbDisabledXInput.isChecked();
                shortcut.putExtra("disableXinput", disabledXInput ? "1" : null);

                boolean gstreamerWorkaround = cbGStreamerWorkaroundToggle.isChecked();
                shortcut.putExtra("gstreamerWorkaround", gstreamerWorkaround ? "1" : "0");

                boolean touchscreenMode = cbSimTouchScreen.isChecked();
                shortcut.putExtra("simTouchScreen", touchscreenMode ? "1" : "0");

                String execArgs = etExecArgs.getText().toString();
                shortcut.putExtra("execArgs", !execArgs.isEmpty() ? execArgs : null);
                // Digits only; empty = normal launch / 숫자만. 비우면 일반 실행
                String appId = etSteamAppId.getText().toString().replaceAll("[^0-9]", "");
                shortcut.putExtra("steamAppId", !appId.isEmpty() ? appId : null);
                shortcut.putExtra("screenSize", !screenSize.equals(shortcut.container.getScreenSize()) ? screenSize : null);
                shortcut.putExtra("graphicsDriver", !graphicsDriver.equals(shortcut.container.getGraphicsDriver()) ? graphicsDriver : null);
                shortcut.putExtra("graphicsDriverConfig", !graphicsDriverConfig.equals(shortcut.container.getGraphicsDriverConfig()) ? graphicsDriverConfig : null);
                shortcut.putExtra("dxwrapper", !dxwrapper.equals(shortcut.container.getDXWrapper()) ? dxwrapper : null);
                shortcut.putExtra("ddrawrapper", !ddrawrapper.equals(shortcut.container.getDDrawWrapper()) ? ddrawrapper : null);
                shortcut.putExtra("dxwrapperConfig", !dxwrapperConfig.equals(shortcut.container.getDXWrapperConfig()) ? dxwrapperConfig : null);
                shortcut.putExtra("audioDriver", !audioDriver.equals(shortcut.container.getAudioDriver()) ? audioDriver : null);
                shortcut.putExtra("emulator", !emulator.equals(shortcut.container.getEmulator()) ? emulator : null);
                shortcut.putExtra("midiSoundFont", !midiSoundFont.equals(shortcut.container.getMIDISoundFont()) ? midiSoundFont : null);
                shortcut.putExtra("forceFullscreen", cbForceFullscreen.isChecked() ? "1" : null);

                if (cbUseSecondaryExec.isChecked()) {
                    String secondaryExec = etSecondaryExec.getText().toString().trim();
                    String execDelay = etExecDelay.getText().toString().trim();
                    shortcut.putExtra("secondaryExec", !secondaryExec.isEmpty() ? secondaryExec : null);
                    shortcut.putExtra("execDelay", !execDelay.isEmpty() ? execDelay : null);
                } else {
                    shortcut.putExtra("secondaryExec", null);
                    shortcut.putExtra("execDelay", null);
                }

                shortcut.putExtra("fullscreenStretched", cbFullscreenStretched.isChecked() ? "1" : null);
                shortcut.putExtra("borderless", cbBorderless.isChecked() ? null : "0");

                String wincomponents = containerDetailFragment.getWinComponents(getContentView());
                shortcut.putExtra("wincomponents", !wincomponents.equals(shortcut.container.getWinComponents()) ? wincomponents : null);



                String envVars = envVarsView.getEnvVars();
                // Language locale: "" = follow the container
                // 언어 로캘: "" = 컨테이너 설정을 따름
                String locale = LOCALE_VALUES[Math.max(0, ((android.widget.Spinner) findViewById(R.id.SShortcutLocale)).getSelectedItemPosition())];
                shortcut.putExtra("lc_all", locale.isEmpty() ? null : locale);

                shortcut.putExtra("envVars", !envVars.isEmpty() ? envVars : null);

                String box64Preset = Box86_64PresetManager.getSpinnerSelectedId(sBox64Preset);
                shortcut.putExtra("box64Preset", !box64Preset.equals(shortcut.container.getBox64Preset()) ? box64Preset : null);

                shortcut.putExtra("rcfileId", rcfileIds[0] != shortcut.container.getRCFileId() ? Integer.toString(rcfileIds[0]) : null);

                // Empty until a FEXCore package is downloaded - fall back to whatever
                // the parent container already uses instead of crashing on save.
                // FEXCore 패키지를 받기 전에는 비어 있음 - 저장 시 크래시하는 대신
                // 상위 컨테이너가 이미 쓰고 있는 값으로 폴백함
                Object fexcoreItem = sFEXCoreVersion.getSelectedItem();
                String fexcoreVersion = fexcoreItem != null ? fexcoreItem.toString() : shortcut.container.getFEXCoreVersion();
                shortcut.putExtra("fexcoreVersion", !fexcoreVersion.equals(shortcut.container.getFEXCoreVersion()) ? fexcoreVersion : null);

                byte startupSelection = (byte)sStartupSelection.getSelectedItemPosition();
                shortcut.putExtra("startupSelection", (startupSelection != shortcut.container.getStartupSelection()) ? String.valueOf(startupSelection) : null);

                String sharpeningEffect = sSharpnessEffect.getSelectedItem().toString();
                String sharpeningLevel = String.valueOf(sbSharpnessLevel.getProgress());
                String sharpeningDenoise = String.valueOf(sbSharpnessDenoise.getProgress());
                shortcut.putExtra("sharpnessEffect", sharpeningEffect);
                shortcut.putExtra("sharpnessLevel", sharpeningLevel);
                shortcut.putExtra("sharpnessDenoise", sharpeningDenoise);

                ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
                int controlsProfile = sControlsProfile.getSelectedItemPosition() > 0 ? profiles.get(sControlsProfile.getSelectedItemPosition() - 1).id : 0;
                // CHANGED: "none" is saved as "0" instead of removing the
                // key. A removed key read back as "not set", and a launch then fell
                // back to the global last profile - so picking "none" here never
                // stuck. "0" is the value XServerDisplayActivity and
                // loadControlsProfileSpinner() already treat as "no profile".
                // 변경: "없음"을 키 삭제 대신 "0"으로 저장함. 키를 지우면 "설정 안 함"
                // 으로 읽혀서 실행할 때 전역 마지막 프로필로 대체됐음 - 그래서 여기서 "없음"을
                // 골라도 유지되지 않았음. "0"은 XServerDisplayActivity와
                // loadControlsProfileSpinner()가 이미 "프로필 없음"으로 취급하는 값임
                shortcut.putExtra("controlsProfile", String.valueOf(controlsProfile));

                String cpuList = cpuListView.getCheckedCPUListAsString();
                shortcut.putExtra("cpuList", !cpuList.equals(shortcut.container.getCPUList(true)) ? cpuList : null);

                // Save all changes to the shortcut
                shortcut.saveData();

                // Move to another container if one was picked (after saving, so the
                // moved file already carries every setting chosen above)
                // 다른 컨테이너를 골랐으면 옮김(저장 뒤에 해서 위에서 고른 설정이
                // 옮겨지는 파일에 이미 들어가 있게 함)
                int picked = sShortcutContainer.getSelectedItemPosition();
                if (picked >= 0 && picked < allContainers.size()
                        && allContainers.get(picked).id != shortcut.container.id) {
                    moveToContainer(allContainers.get(picked));
                }
//
                FEXCoreManager.saveFEXCoreSpinners(shortcut.container, sFEXCoreTSOPreset, sFEXCoreMultiBlock, sFEXCoreX87ReducedPrecision); 
            }
        });
    }

    // Utility method to apply styles to dynamically added TextViews based on their content
    private void applyFieldSetLabelStylesDynamically(ViewGroup rootView, boolean isDarkMode) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View child = rootView.getChildAt(i);
            if (child instanceof ViewGroup) {
                applyFieldSetLabelStylesDynamically((ViewGroup) child, isDarkMode); // Recursive call for nested ViewGroups
            } else if (child instanceof TextView) {
                TextView textView = (TextView) child;
                // Apply the style based on the content of the TextView
                if (isFieldSetLabel(textView.getText().toString())) {
                    applyFieldSetLabelStyle(textView, isDarkMode);
                }
            }
        }
    }

    // Method to check if the text content matches any fieldset label
    private boolean isFieldSetLabel(String text) {
        return text.equalsIgnoreCase("DirectX") ||
                text.equalsIgnoreCase("General") ||
                text.equalsIgnoreCase("Box86/Box64") ||
                text.equalsIgnoreCase("Input Controls") ||
                text.equalsIgnoreCase("Game Controller") ||
                text.equalsIgnoreCase("System");
    }

    public void onWinComponentsViewsAdded(boolean isDarkMode) {
        // Apply styles to all dynamically added TextViews
        ViewGroup llContent = findViewById(R.id.LLContent);
        applyFieldSetLabelStylesDynamically(llContent, isDarkMode);
    }


    public static void loadScreenSizeSpinner(View view, String selectedValue, boolean isDarkMode) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);

        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);

        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenWidth), isDarkMode);
        applyDarkThemeToEditText(view.findViewById(R.id.ETScreenHeight), isDarkMode);


        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String[] screenSize = selectedValue.split("x");
            ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    // BUG FIX: this used to manually repaint every spinner's popup and
    // ETName/ETExecArgs with hardcoded colours and the OLD
    // content_dialog_background(.dark) drawables, running right after the
    // dialog's views were inflated - so it silently undid NeonComboBox's own
    // popupBackground and NeonEditText's theme colours every single time
    // this dialog opened. Left as a no-op (rather than deleted) so its call
    // site and signature stay intact; the XML styles already handle every
    // colour this used to fight over.
    // 버그 수정: 다이얼로그 뷰가 인플레이트된 직후 모든 스피너 팝업과
    // ETName/ETExecArgs를 고정색과 옛 content_dialog_background(.dark)
    // 드로어블로 강제로 다시 칠하던 코드였음 - 그래서 이 창을 열 때마다
    // NeonComboBox 자체의 popupBackground와 NeonEditText의 테마 색을 조용히
    // 지우고 있었음. 호출부와 시그니처는 그대로 두기 위해 삭제 대신
    // 아무 일도 안 하게 비워 둠 - 여기서 덮어쓰던 색은 전부 XML 스타일이
    // 이미 처리함
    private void applyDynamicStyles(View view, boolean isDarkMode) {}

    // BUG FIX: applyFieldSetLabelStylesDynamically() (below) walked every
    // TextView under LLContent - the whole dialog, all four tabs, not just
    // WinComponents - and repainted any whose text matched a known section
    // label ("System", "Input Controls", "DirectX"...) with this hardcoded
    // pair of colours, on top of whatever NeonSectionLabel had already set.
    // Every section header in this dialog was losing its neon styling to
    // this right after the WinComponents rows finished loading. No-op for
    // the same reason as applyDynamicStyles above.
    // 버그 수정: 아래 applyFieldSetLabelStylesDynamically()가 LLContent 밑의
    // 모든 TextView를 훑으면서(WinComponents뿐 아니라 다이얼로그 전체, 탭 4개
    // 전부) 텍스트가 알려진 섹션 라벨("System", "Input Controls", "DirectX"
    // 등)과 일치하면 NeonSectionLabel이 이미 정해 둔 색 위에 이 고정 색 쌍을
    // 덮어썼음. WinComponents 행이 다 불러와진 직후마다 이 다이얼로그의 모든
    // 섹션 헤더가 네온 스타일을 잃고 있었음. 위 applyDynamicStyles와 같은
    // 이유로 아무 일도 안 하게 비워 둠
    private void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {}

    // Same reasoning as applyDynamicStyles/applyFieldSetLabelStyle above -
    // NeonEditText (ETName, ETExecArgs, ETScreenWidth, ETScreenHeight all use
    // it) already resolves the right colour from the dialog's own theme.
    // 위 applyDynamicStyles/applyFieldSetLabelStyle과 같은 이유임 -
    // NeonEditText(ETName, ETExecArgs, ETScreenWidth, ETScreenHeight 전부
    // 이걸 씀)가 다이얼로그 자신의 테마에서 이미 알맞은 색을 가져옴
    private static void applyDarkThemeToEditText(EditText editText, boolean isDarkMode) {}

    private void renameShortcut(String newName) {
        File parent = shortcut.file.getParentFile();
        File oldDesktopFile = shortcut.file; // Reference to the old file
        File newDesktopFile = new File(parent, newName + ".desktop");

        // Rename the desktop file if the new one doesn't exist
        if (!newDesktopFile.isFile() && oldDesktopFile.renameTo(newDesktopFile)) {
            // Successfully renamed, update the shortcut's file reference
            updateShortcutFileReference(newDesktopFile); // New helper method

            // As a precaution, delete any remaining old file
            deleteOldFileIfExists(oldDesktopFile);
        }

        // Rename link file if applicable
        File linkFile = new File(parent, shortcut.name + ".lnk");
        if (linkFile.isFile()) {
            File newLinkFile = new File(parent, newName + ".lnk");
            if (!newLinkFile.isFile()) linkFile.renameTo(newLinkFile);
        }

        fragment.loadShortcutsList();
        fragment.updateShortcutOnScreen(newName, newName, shortcut.container.id, newDesktopFile.getAbsolutePath(),
                Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid"));
    }

    /**
     * Moves this shortcut into another container. The .desktop file, its .lnk twin
     * and icon files are copied into the target container, the container_id line
     * is rewritten, then the originals are deleted.
     * An .exe on C: lives inside the current container's own drive_c, which the
     * target cannot see, so that case is refused instead of producing a shortcut
     * that cannot start. D:, E:, Z: and SD-card drives are shared and move fine.
     * 바로가기를 다른 컨테이너로 옮김. .desktop 파일, 짝이 되는 .lnk, 아이콘 파일을
     * 대상 컨테이너로 복사하고 container_id 줄을 고친 뒤 원본을 지움.
     * C:에 있는 .exe는 현재 컨테이너 자체의 drive_c 안에 있어서 대상 컨테이너에서
     * 안 보이므로, 실행 안 되는 바로가기를 만드는 대신 거부함. D:, E:, Z:, SD카드
     * 드라이브는 공유되므로 문제없이 옮겨짐
     */
    // ==================== Per-shortcut locale ====================
    // ==================== 바로가기별 로캘 ====================
    // Some games only run (or only show their text) under their own language
    // locale. A locale picked here is applied to LANG and LC_ALL for this
    // shortcut only; "Container default" keeps the container's value and the
    // global LC_ALL switch in Settings
    // 일부 게임은 자기 언어 로캘에서만 실행되거나 글자가 나옴. 여기서 고른 로캘은 이
    // 바로가기에만 LANG과 LC_ALL로 적용됨. "컨테이너 기본값"은 컨테이너 값과 설정의
    // 전역 LC_ALL 스위치를 그대로 따름
    private static final String[] LOCALE_VALUES = {"", "ko_KR.UTF-8", "ja_JP.UTF-8", "zh_CN.UTF-8", "zh_TW.UTF-8", "en_US.UTF-8"};

    private void setupLocaleSpinner() {
        android.widget.Spinner sLocale = findViewById(R.id.SShortcutLocale);
        String[] labels = {
                getContext().getString(R.string.shortcut_locale_default),
                "한국어 (ko_KR)", "日本語 (ja_JP)", "简体中文 (zh_CN)", "繁體中文 (zh_TW)", "English (en_US)"};
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sLocale.setAdapter(adapter);
        String current = shortcut.getExtra("lc_all");
        int pos = 0;
        for (int i = 1; i < LOCALE_VALUES.length; i++) {
            String base = LOCALE_VALUES[i].substring(0, 5);
            if (current.startsWith(base)) pos = i;
        }
        sLocale.setSelection(pos);
    }

    private void moveToContainer(Container target) {
        Context context = getContext();
        // File work moved to ShortcutMover so the detail sheet's layer button
        // uses exactly the same rules; only the UI reactions stay here
        // 파일 작업은 ShortcutMover로 옮김. 상세창의 레이어 버튼이 완전히 같은
        // 규칙을 쓰게 하기 위함이고, 여기에는 화면 반응만 남김
        com.winlator.cmod.container.ShortcutMover.Result result =
                com.winlator.cmod.container.ShortcutMover.move(shortcut, target);
        switch (result.code) {
            case com.winlator.cmod.container.ShortcutMover.C_DRIVE_BLOCKED:
                android.widget.Toast.makeText(context, R.string.shortcut_move_c_drive_blocked, android.widget.Toast.LENGTH_LONG).show();
                return;
            case com.winlator.cmod.container.ShortcutMover.NAME_EXISTS:
                android.widget.Toast.makeText(context, R.string.shortcut_move_name_exists, android.widget.Toast.LENGTH_LONG).show();
                return;
            case com.winlator.cmod.container.ShortcutMover.FAILED:
                android.widget.Toast.makeText(context, R.string.shortcut_move_failed, android.widget.Toast.LENGTH_LONG).show();
                return;
        }

        // A pinned home-screen shortcut still points at the old container/path
        // 홈 화면에 고정된 바로가기는 아직 옛 컨테이너/경로를 가리킴
        if (shortcut.icon != null) {
            fragment.updateShortcutOnScreen(shortcut.name, shortcut.name, target.id, result.newFile.getAbsolutePath(),
                    Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid"));
        }
        fragment.loadShortcutsList();
        android.widget.Toast.makeText(context,
                context.getString(R.string.shortcut_moved_to, target.getName()), android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * AppID auto-find: steam_appid.txt next to the exe first, then a Steam store
     * search by the shortcut name whose results the user picks from.
     * AppID 자동 찾기: 먼저 exe 옆의 steam_appid.txt를 보고, 없으면 바로가기 이름으로
     * Steam 상점을 검색해서 결과 중 사용자가 고르게 함
     */
    private void findSteamAppId(EditText target) {
        final Context ctx = getContext();
        final File gameDir = resolveGameDir();
        final String name = shortcut.name;
        new Thread(() -> {
            int fromFile = com.winlator.cmod.steam.SteamAppIdFinder.fromGameFolder(gameDir);
            java.util.List<com.winlator.cmod.steam.SteamAppIdFinder.Candidate> found =
                    fromFile > 0 ? null : com.winlator.cmod.steam.SteamAppIdFinder.searchStoreVariants(name, gameDir);
            target.post(() -> {
                if (fromFile > 0) {
                    target.setText(String.valueOf(fromFile));
                    return;
                }
                if (found == null || found.isEmpty()) {
                    android.widget.Toast.makeText(ctx, R.string.steam_app_id_not_found, android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = found.get(i).name + "  (" + found.get(i).appId + ")";
                new android.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.steam_app_id_pick)
                        .setItems(labels, (d, which) -> target.setText(String.valueOf(found.get(which).appId)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }).start();
    }

    // Windows exe path (e.g. F:\Game\game.exe) -> Android folder via the container's
    // dosdevices links (f: -> real folder)
    // Windows exe 경로(예: F:\Game\game.exe) -> 컨테이너 dosdevices 링크(f: -> 실제
    // 폴더)를 거쳐 안드로이드 폴더로 바꿈
    private File resolveGameDir() {
        try {
            String p = shortcut.path.trim();
            if (p.startsWith("\"")) p = p.substring(1);
            if (p.endsWith("\"")) p = p.substring(0, p.length() - 1);
            if (p.length() < 3 || p.charAt(1) != ':') return null;
            String drive = Character.toLowerCase(p.charAt(0)) + ":";
            String rest = p.substring(2).replace('\\', '/');
            File exe = new File(new File(shortcut.container.getRootDir(), ".wine/dosdevices/" + drive), rest);
            return exe.getCanonicalFile().getParentFile();
        } catch (Exception e) {
            return null;
        }
    }

    // Method to ensure no old file remains
    private void deleteOldFileIfExists(File oldFile) {
        if (oldFile.exists()) {
            if (!oldFile.delete()) {
                Log.e("ShortcutSettingsDialog", "Failed to delete old file: " + oldFile.getPath());
            }
        }
    }

    // Update the shortcut's file reference to ensure saveData() writes to the correct file
    private void updateShortcutFileReference(File newFile) {
        try {
            Field fileField = Shortcut.class.getDeclaredField("file");
            fileField.setAccessible(true);
            fileField.set(shortcut, newFile);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e("ShortcutSettingsDialog", "Error updating shortcut file reference", e);
        }
    }


    private EnvVarsView createEnvVarsTab() {
        final View view = getContentView();
        final Context context = view.getContext();

        // Retrieve the existing EnvVarsView
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);

        // Update the dark mode setting of the existing instance
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        envVarsView.setDarkMode(isDarkMode);

        // Set the environment variables in the existing EnvVarsView
        envVarsView.setEnvVars(new EnvVars(shortcut.getExtra("envVars")));

        // Set the click listener for adding new environment variables
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) ->
                new AddEnvVarDialog(context, envVarsView).show()
        );

        // Collapsed by default (see shortcut_settings_dialog.xml) - tapping
        // the header shows/hides the list+Add button and flips the chevron
        // 180 degrees. rotation, not a drawable swap, so the same
        // neon_ic_chevron_down asset serves both states.
        // 기본으로 접어 둠(shortcut_settings_dialog.xml 참고) - 헤더를 누르면
        // 목록+Add 버튼이 접히고 펴지며 화살표가 180도 뒤집힘. 드로어블을
        // 바꾸는 게 아니라 회전이라, neon_ic_chevron_down 이미지 하나로 두
        // 상태 다 처리함
        View llEnvVarsBody = view.findViewById(R.id.LLEnvVarsBody);
        View ivEnvVarsChevron = view.findViewById(R.id.IVEnvVarsChevron);
        view.findViewById(R.id.LLEnvVarsHeader).setOnClickListener((v) -> {
            boolean expanding = llEnvVarsBody.getVisibility() != View.VISIBLE;
            llEnvVarsBody.setVisibility(expanding ? View.VISIBLE : View.GONE);
            ivEnvVarsChevron.setRotation(expanding ? 180f : 0f);
        });

        return envVarsView;
    }

    private void loadControlsProfileSpinner(Spinner spinner, String selectedValue) {
        final Context context = fragment.getContext();
        final ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        ArrayList<String> values = new ArrayList<>();
        values.add(context.getString(R.string.none));

        int selectedPosition = 0;
        int selectedId = Integer.parseInt(selectedValue);
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile.id == selectedId) selectedPosition = i + 1;
            values.add(profile.getName());
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
    }

    public static void loadBox64VersionSpinner(Context context, ContentsManager manager, Spinner spinner, boolean isArm64EC) {
        List<String> itemList;
        if (isArm64EC)
            itemList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.wowbox64_version_entries)));
        else
            itemList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.box64_version_entries)));
        if (!isArm64EC) {
            for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_BOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                itemList.add(entryName.substring(firstDashIndex + 1));
            }
        } else {
            for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)) {
                String entryName = ContentsManager.getEntryName(profile);
                int firstDashIndex = entryName.indexOf('-');
                itemList.add(entryName.substring(firstDashIndex + 1));
            }
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }
    
    public void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper, final View vGraphicsDriverConfig, String selectedGraphicsDriver, String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();
        
        ContainerDetailFragment.updateGraphicsDriverSpinner(context, sGraphicsDriver);
        
        final String[] dxwrapperEntries = context.getResources().getStringArray(R.array.dxwrapper_entries);
        
        Runnable update = () -> {
            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
            String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();

            tvGraphicsDriverVersion.setText(GraphicsDriverConfigDialog.getVersion(graphicsDriverConfig));

            vGraphicsDriverConfig.setOnClickListener((v) -> {
                new GraphicsDriverConfigDialog(vGraphicsDriverConfig, graphicsDriver, tvGraphicsDriverVersion).show();
            });

            ArrayList<String> items = new ArrayList<>();
            for (String value : dxwrapperEntries) {
                    items.add(value);
            }
            sDXWrapper.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items.toArray(new String[0])));
            AppUtils.setSpinnerSelectionFromIdentifier(sDXWrapper, selectedDXWrapper);
        };

        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsDriver, selectedGraphicsDriver);
        update.run();
    }
}
