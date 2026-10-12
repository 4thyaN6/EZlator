package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import androidx.appcompat.widget.SwitchCompat;
// CheckBox -> SwitchCompat app-wide per the toggle-switch decision. Same
// CompoundButton API, so only the declared type changes.
// 토글 스위치 결정에 따라 앱 전체에서 CheckBox -> SwitchCompat로 바꿈.
// CompoundButton API가 같아서 선언 타입만 바뀜
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.box86_64.Box86_64PresetManager;
import com.winlator.cmod.box86_64.rc.RCManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.AddEnvVarDialog;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.contentdialog.DXWrapperConfigDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.ColorPickerView;
import com.winlator.cmod.widget.EnvVarsView;
import com.winlator.cmod.widget.ImagePickerView;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ContainerDetailFragment extends Fragment {

    private static final String TAG = "FileUtils";

    private ContainerManager manager;
    private ContentsManager contentsManager;
    // Last name auto-filled from the Wine/Proton layer (new containers only)
    // Wine/Proton 레이어에서 자동으로 채운 마지막 이름(새 컨테이너 전용)
    private String autoContainerName = null;
    private final int containerId;
    private static Container container;
    private PreloaderDialog preloaderDialog;
    private JSONArray gpuCards;
    private Callback<String> openDirectoryCallback;

    private static boolean isDarkMode;

    private ImageFs imageFs;

    public ContainerDetailFragment() {
        this(0);
    }

    public ContainerDetailFragment(int containerId) {
        this.containerId = containerId;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        preloaderDialog = new PreloaderDialog(getActivity());

        try {
            gpuCards = new JSONArray(FileUtils.readString(getContext(), "gpu_cards.json"));
        }
        catch (JSONException e) {}
    }

    private static void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
        // Disabled: NeonSectionLabel takes its colours from the theme. Leaving
        // this active would repaint every section label with the old hardcoded
        // #cccccc / #bdbdbd right after inflation. The body is kept below the
        // return only so the 7 call sites in onViewCreated still compile.
        // 비활성화함: NeonSectionLabel이 테마에서 색을 가져옴. 이걸 살려 두면
        // 인플레이트 직후 모든 구역 라벨을 옛 하드코딩 색 #cccccc / #bdbdbd로
        // 다시 칠해 버림. 본문은 onViewCreated의 호출 7곳이 계속 컴파일되도록
        // return 아래에 남겨 둔 것뿐임
        if (true) return;

        if (isDarkMode) {
            // Apply dark mode-specific attributes
            textView.setTextColor(Color.parseColor("#cccccc")); // Set text color to #cccccc
            textView.setBackgroundResource(R.color.window_background_color_dark); // Set dark background color
        } else {
            // Apply light mode-specific attributes (original FieldSetLabel)
            textView.setTextColor(Color.parseColor("#bdbdbd")); // Set text color to #bdbdbd
            textView.setBackgroundResource(R.color.window_background_color); // Set light background color
        }
    }


    private void applyDynamicStylesRecursively(View view, boolean isDarkMode) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                applyDynamicStylesRecursively(child, isDarkMode);
            }
        } else if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if ("desktop".equals(textView.getText().toString())) { // Check for specific text if needed
                textView.setTextAppearance(getContext(), isDarkMode ? R.style.FieldSetLabel_Dark : R.style.FieldSetLabel);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_DIRECTORY_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                Log.d(TAG, "URI obtained in onActivityResult: " + uri.toString());
                String path = FileUtils.getFilePathFromUri(getContext(), uri);
                Log.d(TAG, "File path in onActivityResult: " + path);
                if (path != null) {
                    if (openDirectoryCallback != null) {
                        openDirectoryCallback.call(path);
                    }
                } else {
                    Toast.makeText(getContext(), "Invalid directory selected", Toast.LENGTH_SHORT).show();
                }
            }
            openDirectoryCallback = null;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(isEditMode() ? R.string.edit_container : R.string.new_container);

        // Find TextViews by ID and apply dynamic styles
        TextView desktopLabel = view.findViewById(R.id.TVDesktop);
        applyFieldSetLabelStyle(desktopLabel, isDarkMode);  // Apply the dark or light mode styles

        TextView registryKeysLabel = view.findViewById(R.id.TVRegistryKeys);
        applyFieldSetLabelStyle(registryKeysLabel, isDarkMode);  // Apply the dark or light mode styles

        // Win Components TextViews
        TextView directXLabel = view.findViewById(R.id.TVDirectX);
        applyFieldSetLabelStyle(directXLabel, isDarkMode);  // Apply the dark or light mode styles

        TextView generalLabel = view.findViewById(R.id.TVGeneral);
        applyFieldSetLabelStyle(generalLabel, isDarkMode);  // Apply the dark or light mode styles

        // Advanced Tab TextViews
        TextView box86box64Label = view.findViewById(R.id.TVBox86Box64);
        applyFieldSetLabelStyle(box86box64Label, isDarkMode);  // Apply the dark or light mode styles
        
        TextView fexCoreLabel = view.findViewById(R.id.TVFEXCore);
        applyFieldSetLabelStyle(fexCoreLabel, isDarkMode);

        TextView systemLabel = view.findViewById(R.id.TVSystem);
        applyFieldSetLabelStyle(systemLabel, isDarkMode);  // Apply the dark or light mode styles

    }

    public boolean isEditMode() {
        return container != null;
    }

    @SuppressLint("SetTextI18n")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup root, @Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final View view = inflater.inflate(R.layout.container_detail_fragment, root, false);

        // Determine if dark mode is enabled
        isDarkMode = preferences.getBoolean("dark_mode", true); // Adjust this based on how you store theme info

        // Apply dynamic styles
        manager = new ContainerManager(context);
        container = containerId > 0 ? manager.getContainerById(containerId) : null;
        contentsManager = new ContentsManager(context);
        contentsManager.syncContents();



        boolean isLegacyModeEnabled = preferences.getBoolean("legacy_mode_enabled", false);


        final EditText etName = view.findViewById(R.id.ETName);

        final Spinner sWineVersion = view.findViewById(R.id.SWineVersion);



        // Ensure the Wine version layout is visible
        final LinearLayout llWineVersion = view.findViewById(R.id.LLWineVersion);
        llWineVersion.setVisibility(View.VISIBLE);

        // Set container name and graphics driver version based on mode
        if (isEditMode()) {
            etName.setText(container.getName());
        } else {
            // Filled from the selected Wine/Proton layer by the spinner listener in
            // loadWineVersionSpinner(); nothing to set until a version is selected
            // loadWineVersionSpinner()의 스피너 리스너가 선택된 Wine/Proton 레이어
            // 이름으로 채움. 버전이 선택되기 전에는 넣을 값이 없음
            etName.setText("");
        }

        final Spinner sBox64Version = view.findViewById(R.id.SBox64Version);

        loadWineVersionSpinner(view, sWineVersion, sBox64Version);

        loadScreenSizeSpinner(view, isEditMode() ? container.getScreenSize() : Container.DEFAULT_SCREEN_SIZE);

        final Spinner sGraphicsDriver = view.findViewById(R.id.SGraphicsDriver);
        
        final Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);
        final Spinner sDDrawrapper = view.findViewById(R.id.SDDrawrapper);

        final View vDXWrapperConfig = view.findViewById(R.id.BTDXWrapperConfig);
        vDXWrapperConfig.setTag(isEditMode() ? container.getDXWrapperConfig() : Container.DEFAULT_DXWRAPPERCONFIG);

        final View vGraphicsDriverConfig = view.findViewById(R.id.BTGraphicsDriverConfig);
        // New container starts on the newest downloaded driver (DriverAutoSelect)
        // 새 컨테이너는 가장 최근에 받은 드라이버로 시작함(DriverAutoSelect)
        vGraphicsDriverConfig.setTag(isEditMode() ? container.getGraphicsDriverConfig()
                : com.winlator.cmod.core.DriverAutoSelect.defaultGraphicsDriverConfig(getContext()));

        setupDXWrapperSpinner(sDXWrapper, vDXWrapperConfig, sWineVersion);
        setupDDrawSpinner(sDDrawrapper, isEditMode() ? container.getDDrawWrapper() : Container.DEFAULT_DDRAWRAPPER);
        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig,
                isEditMode() ? container.getGraphicsDriver() : Container.DEFAULT_GRAPHICS_DRIVER,
                isEditMode() ? container.getDXWrapper() : Container.DEFAULT_DXWRAPPER);

        view.findViewById(R.id.BTHelpDXWrapper).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));
        view.findViewById(R.id.BTHelpBox64Preset).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.box64_preset_help_content));
        view.findViewById(R.id.BTHelpTSOMode).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.fexcore_tso_help_content));
        view.findViewById(R.id.BTHelpX87Mode).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.fexcore_x87_help_content));
        view.findViewById(R.id.BTHelpMultiBlock).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.fexcore_multiblock_help_content));
        // Wire up the RC File help icon to show its explanation popup
        // RC 파일 도움말 아이콘을 눌렀을 때 설명 팝업이 뜨도록 연결
        view.findViewById(R.id.BTHelpRCFile).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.rc_file_help_content));

        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, isEditMode() ? container.getAudioDriver() : Container.DEFAULT_AUDIO_DRIVER);

        Spinner sEmulator = view.findViewById(R.id.SEmulator);
        AppUtils.setSpinnerSelectionFromIdentifier(sEmulator, isEditMode() ? container.getEmulator() : Container.DEFAULT_EMULATOR);

        Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, isEditMode() ? container.getMIDISoundFont() : "");

        final SwitchCompat cbShowFPS = view.findViewById(R.id.CBShowFPS);
        // New containers start with FPS on; existing ones keep theirs
        // 새 컨테이너는 FPS 켜짐으로 시작함. 기존 컨테이너는 저장된 값을 유지함
        cbShowFPS.setChecked(!isEditMode() || container.isShowFPS());

        final SwitchCompat cbFullscreenStretched = view.findViewById(R.id.CBFullscreenStretched);
        cbFullscreenStretched.setChecked(isEditMode() && container.isFullscreenStretched());

        final Runnable showGStreamerWorkaroundWarning = () -> ContentDialog.alert(context, R.string.enable_gstreamer_workaround_alert, null);

        final SwitchCompat cbGStreamerWorkaroundToggle = view.findViewById(R.id.CBGStreamerWorkaroundToggle);
        // In ContainerDetailFragment.java
        cbGStreamerWorkaroundToggle.setChecked(isEditMode() && container.isGstreamerWorkaround());

        cbGStreamerWorkaroundToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked && cbGStreamerWorkaroundToggle.isChecked())
                    showGStreamerWorkaroundWarning.run();
            });

        final EditText etLC_ALL = view.findViewById(R.id.ETlcall);
        Locale systemLocal = Locale.getDefault();
        etLC_ALL.setText(isEditMode() ? container.getLC_ALL() : systemLocal.getLanguage() + '_' + systemLocal.getCountry() + ".UTF-8");

        final View btShowLCALL = view.findViewById(R.id.BTShowLCALL);
        btShowLCALL.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            String[] lcs = getResources().getStringArray(R.array.some_lc_all);
            for (int i = 0; i < lcs.length; i++)
                popupMenu.getMenu().add(Menu.NONE, i, Menu.NONE, lcs[i]);
            popupMenu.setOnMenuItemClickListener(item -> {
                etLC_ALL.setText(item.toString() + ".UTF-8");
                return true;
            });
            popupMenu.show();
        });

        final SwitchCompat cbWoW64Mode = view.findViewById(R.id.CBWoW64Mode);
        cbWoW64Mode.setChecked(!isEditMode() || container.isWoW64Mode());

        final Spinner sStartupSelection = view.findViewById(R.id.SStartupSelection);
        byte previousStartupSelection = isEditMode() ? container.getStartupSelection() : -1;
        sStartupSelection.setSelection(previousStartupSelection != -1 ? previousStartupSelection : Container.STARTUP_SELECTION_ESSENTIAL);

        final Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        Box86_64PresetManager.loadSpinner("box64", sBox64Preset, isEditMode() ? container.getBox64Preset() : preferences.getString("box64_preset", Box86_64Preset.PERFORMANCE));

        final Spinner sFEXCoreVersion = view.findViewById(R.id.SFEXCoreVersion);
        FEXCoreManager.loadFEXCoreVersion(context, contentsManager, sFEXCoreVersion, container);

        final Spinner sFEXCoreTSOPreset = view.findViewById(R.id.SFEXCoreTSOPreset);
        final Spinner sFEXCoreMultiBlock = view.findViewById(R.id.SFEXCoreMultiblock);
        final Spinner sFEXCoreX87ReducedPrecision = view.findViewById(R.id.SFEXCoreX87ReducedPrecision);
        
        FEXCoreManager.loadFEXCoreSettings(context, container, sFEXCoreTSOPreset, sFEXCoreMultiBlock, sFEXCoreX87ReducedPrecision);

        String selectedDriver = sGraphicsDriver.getSelectedItem().toString();
        List<String> sGraphicsItemsList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.graphics_driver_entries)));
        sGraphicsDriver.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, sGraphicsItemsList));
        AppUtils.setSpinnerSelectionFromValue(sGraphicsDriver, selectedDriver);

        final Spinner sRCFile = view.findViewById(R.id.SRCFile);
        final int[] rcfileIds = {0};
        RCManager rcManager = new RCManager(context);
        RCManager.loadRCFileSpinner(rcManager, container == null ? 0 : container.getRCFileId(), sRCFile, id -> rcfileIds[0] = id);

        final CPUListView cpuListView = view.findViewById(R.id.CPUListView);
        final CPUListView cpuListViewWoW64 = view.findViewById(R.id.CPUListViewWoW64);

        cpuListView.setCheckedCPUList(isEditMode() ? container.getCPUList(true) : Container.getFallbackCPUList());
        cpuListViewWoW64.setCheckedCPUList(isEditMode() ? container.getCPUListWoW64(true) : Container.getFallbackCPUListWoW64());

        // XR tab (VR controller mapping) removed with XrActivity
        // XR 탭(VR 컨트롤러 매핑)을 XrActivity와 함께 제거함

        createWineConfigurationTab(view);
        final EnvVarsView envVarsView = createEnvVarsTab(view);
        createWinComponentsTab(view, isEditMode() ? container.getWinComponents() : Container.DEFAULT_WINCOMPONENTS);
        createDrivesTab(view);

        // One id per TabItem, in the same order they appear in
        // container_detail_fragment.xml. setupTabLayout() calls getTabAt(i) for every
        // id passed, so a wrong name breaks the build and a wrong count throws at runtime.
        // container_detail_fragment.xml의 TabItem 순서와 1:1로 맞춰야 함.
        // setupTabLayout()이 전달된 id 개수만큼 getTabAt(i)를 호출하므로,
        // 이름이 틀리면 빌드가 깨지고 개수가 틀리면 실행 중 예외가 남
        AppUtils.setupTabLayout(view, R.id.TabLayout,
                R.id.LLTabWine,             // 1. Wine
                R.id.LLTabGraphicsBinaries, // 2. Graphics / Binaries
                R.id.LLTabAdvanced,         // 3. Advanced
                R.id.LLTabWinComponents,    // 4. Win Components
                R.id.LLTabDrives,           // 5. Drives
                R.id.LLTabEnvVars);         // 6. Environment Variables (7. XR removed / 7. XR 제거함)

        TabLayout tabLayout = view.findViewById(R.id.TabLayout);

        // The per-mode background swap is gone: the layout sets neon_tab_bg,
        // which follows the theme on its own. Swapping here would replace it
        // with the old grey tab strip.
        // 모드별 배경 교체는 없앰: 레이아웃이 neon_tab_bg를 지정하고 그게 알아서
        // 테마를 따라감. 여기서 갈아 끼우면 옛 회색 탭 줄로 되돌아감

        // Set up confirm button
        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            try {
                // Capture and set container properties based on UI inputs
                String name = etName.getText().toString().trim();
                // Empty name (no layer installed yet, or user cleared it) falls back
                // to a unique "Container" instead of saving a blank name
                // 이름이 비었으면(레이어 미설치 또는 사용자가 지움) 빈 이름 대신
                // 겹치지 않는 "Container"로 대체함
                if (name.isEmpty()) name = manager.makeUniqueName("Container");
                String screenSize = getScreenSize(view);
                String envVars = envVarsView.getEnvVars();
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString();
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                String ddrawrapper = StringUtils.parseIdentifier(sDDrawrapper.getSelectedItem());
                String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                String emulator = StringUtils.parseIdentifier(sEmulator.getSelectedItem());
                String wincomponents = getWinComponents(view);
                String drives = getDrives(view);
                boolean showFPS = cbShowFPS.isChecked();
                boolean fullscreenStretched = cbFullscreenStretched.isChecked();

                String cpuList = cpuListView.getCheckedCPUListAsString();
                String cpuListWoW64 = cpuListViewWoW64.getCheckedCPUListAsString();
                boolean wow64Mode = cbWoW64Mode.isChecked();
                byte startupSelection = (byte) sStartupSelection.getSelectedItemPosition();
                String box64Version = sBox64Version.getSelectedItem().toString();
                String box64Preset = Box86_64PresetManager.getSpinnerSelectedId(sBox64Preset);
                String desktopTheme = getDesktopTheme(view);
                int rcfileId = rcfileIds[0];
                // fexcore_version_entries is empty on purpose, so this spinner holds
                // nothing until a FEXCore package has been downloaded, and
                // getSelectedItem() returns null in that state.
                // fexcore_version_entries를 의도적으로 비웠기 때문에 FEXCore
                // 패키지를 받기 전에는 이 스피너가 비어 있고,
                // 그 상태에서 getSelectedItem()은 null을 반환함
                Object fexcoreItem = sFEXCoreVersion.getSelectedItem();
                String fexcoreVersion = fexcoreItem != null ? fexcoreItem.toString() : DefaultVersion.FEXCORE;
                // Capture missing properties
                String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
                String lc_all = etLC_ALL.getText().toString();

                // Handle GStreamer Workaround environment variables based on the toggle state
                boolean gstreamerWorkaround = cbGStreamerWorkaroundToggle.isChecked();



                if (isEditMode()) {
                    // Update existing container properties
                    container.setName(name);
                    container.setScreenSize(screenSize);
                    container.setEnvVars(envVars);
                    container.setCPUList(cpuList);
                    container.setCPUListWoW64(cpuListWoW64);
                    container.setGraphicsDriver(graphicsDriver);
                    container.setGraphicsDriverConfig(graphicsDriverConfig);
                    container.setDXWrapper(dxwrapper);
                    container.setDDrawWrapper(ddrawrapper);
                    container.setDXWrapperConfig(dxwrapperConfig);
                    container.setAudioDriver(audioDriver);
                    container.setEmulator(emulator);
                    container.setWinComponents(wincomponents);
                    container.setDrives(drives);
                    container.setShowFPS(showFPS);
                    container.setFullscreenStretched(fullscreenStretched);
                    container.setWoW64Mode(wow64Mode);
                    container.setStartupSelection(startupSelection);
                    container.setBox64Version(box64Version);
                    container.setBox64Preset(box64Preset);
                    container.setFEXCoreVersion(fexcoreVersion);
                    container.setDesktopTheme(desktopTheme);
                    container.setRcfileId(rcfileId);
                    container.setMidiSoundFont(midiSoundFont);
                    container.setLC_ALL(lc_all);
                    container.setGstreamerWorkaround(gstreamerWorkaround);
                    container.saveData();
                    saveWineRegistryKeys(view);
                    FEXCoreManager.saveFEXCoreSpinners(container, sFEXCoreTSOPreset, sFEXCoreMultiBlock, sFEXCoreX87ReducedPrecision);
                    getActivity().onBackPressed();
                } else {
                    // Create new container with specified properties
                    JSONObject data = new JSONObject();
                    data.put("name", name);
                    data.put("screenSize", screenSize);
                    data.put("envVars", envVars);
                    data.put("cpuList", cpuList);
                    data.put("cpuListWoW64", cpuListWoW64);
                    data.put("graphicsDriver", graphicsDriver);
                    data.put("graphicsDriverConfig", graphicsDriverConfig);
                    data.put("dxwrapper", dxwrapper);
                    data.put("ddrawrapper", ddrawrapper);
                    data.put("dxwrapperConfig", dxwrapperConfig);
                    data.put("audioDriver", audioDriver);
                    data.put("emulator", emulator);
                    data.put("wincomponents", wincomponents);
                    data.put("drives", drives);
                    data.put("showFPS", showFPS);
                    data.put("fullscreenStretched", fullscreenStretched);
                    data.put("wow64Mode", wow64Mode);
                    data.put("startupSelection", startupSelection);
                    data.put("box64Version", box64Version);
                    data.put("box64Preset", box64Preset);
                    data.put("fexcoreVersion", fexcoreVersion);
                    data.put("desktopTheme", desktopTheme);
                    data.put("rcfileId", rcfileId);
                    data.put("wineVersion", sWineVersion.getSelectedItem().toString());
                    data.put("midiSoundFont", midiSoundFont);
                    data.put("lc_all", lc_all);
                    data.put("gstreamerWorkaround", gstreamerWorkaround);

                    preloaderDialog.show(R.string.creating_container);

                    // Initialize ImageFs
                    File imageFsRoot = new File(context.getFilesDir(), "imagefs");
                    imageFs = ImageFs.find(imageFsRoot);


                    manager.createContainerAsync(data, contentsManager, (container) -> {
                        if (container != null) {
                            this.container = container;
                            saveWineRegistryKeys(view);
                            FEXCoreManager.saveFEXCoreSpinners(container, sFEXCoreTSOPreset, sFEXCoreMultiBlock, sFEXCoreX87ReducedPrecision);
                        }
                        preloaderDialog.close();
                        getActivity().onBackPressed();
                    });
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        return view;
    }

    private void saveWineRegistryKeys(View view) {
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {

            Spinner sRenderer = view.findViewById(R.id.SRenderer);
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "renderer", sRenderer.getSelectedItem().toString());

            Spinner sCSMT = view.findViewById(R.id.SCSMT);
            registryEditor.setDwordValue("Software\\Wine\\Direct3D", "csmt", sCSMT.getSelectedItemPosition() != 0 ? 3 : 0);

            Spinner sGPUName = view.findViewById(R.id.SGPUName);
            if (sGPUName.getSelectedItemPosition() == 0) {
                // "Auto" selected — don't spoof a specific card, remove any saved override
                // "자동" 선택 시 — 특정 카드로 위장하지 않고, 기존에 저장된 값이 있으면 지움
                registryEditor.removeValue("Software\\Wine\\Direct3D", "VideoPciDeviceID");
            } else {
                // gpuCards is parsed JSON, so this lookup can throw. The catch that
                // used to sit after the closing brace had no matching try.
                // gpuCards는 JSON 파싱 결과라 조회 시 예외가 날 수 있음. 기존에는
                // 닫는 중괄호 뒤에 catch만 떠 있어서 짝이 되는 try가 없었음
                try {
                    JSONObject gpuName = gpuCards.getJSONObject(sGPUName.getSelectedItemPosition() - 1);
                    registryEditor.setDwordValue("Software\\Wine\\Direct3D", "VideoPciDeviceID", gpuName.getInt("deviceID"));
                } catch (JSONException e) {
                    // Card list out of sync with the spinner - leave the saved value untouched
                    // 카드 목록과 스피너가 어긋난 경우 - 저장된 값을 건드리지 않음
                    e.printStackTrace();
                }
            }

            Spinner sOffscreenRenderingMode = view.findViewById(R.id.SOffscreenRenderingMode);
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "OffScreenRenderingMode", sOffscreenRenderingMode.getSelectedItem().toString().toLowerCase(Locale.ENGLISH));

            Spinner sStrictShaderMath = view.findViewById(R.id.SStrictShaderMath);
            registryEditor.setDwordValue("Software\\Wine\\Direct3D", "strict_shader_math", sStrictShaderMath.getSelectedItemPosition());

            Spinner sVideoMemorySize = view.findViewById(R.id.SVideoMemorySize);
            if (sVideoMemorySize.getSelectedItemPosition() == 0) {
                // "Auto" selected — let Wine detect it instead of forcing a number
                // "자동" 선택 시 — 숫자를 강제하지 않고 Wine이 알아서 판단하게 둠
                registryEditor.removeValue("Software\\Wine\\Direct3D", "VideoMemorySize");
            } else {
                String videoMemorySize = StringUtils.parseNumber(sVideoMemorySize.getSelectedItem());
                registryEditor.setStringValue("Software\\Wine\\Direct3D", "VideoMemorySize", videoMemorySize);
            }

            Spinner sMouseWarpOverride = view.findViewById(R.id.SMouseWarpOverride);
            registryEditor.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", sMouseWarpOverride.getSelectedItem().toString().toLowerCase(Locale.ENGLISH));

            registryEditor.setStringValue("Software\\Wine\\Direct3D", "shader_backend", "glsl");
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "UseGLSL", "enabled");
        }
    }

    private void createWineConfigurationTab(View view) {
        Context context = getContext();

        WineThemeManager.ThemeInfo desktopTheme = new WineThemeManager.ThemeInfo(isEditMode() ? container.getDesktopTheme() : WineThemeManager.DEFAULT_DESKTOP_THEME);
        Spinner sDesktopTheme = view.findViewById(R.id.SDesktopTheme);
        sDesktopTheme.setSelection(desktopTheme.theme.ordinal());
        final ImagePickerView ipvDesktopBackgroundImage = view.findViewById(R.id.IPVDesktopBackgroundImage);
        final ColorPickerView cpvDesktopBackgroundColor = view.findViewById(R.id.CPVDesktopBackgroundColor);
        cpvDesktopBackgroundColor.setColor(desktopTheme.backgroundColor);

        Spinner sDesktopBackgroundType = view.findViewById(R.id.SDesktopBackgroundType);
        sDesktopBackgroundType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                WineThemeManager.BackgroundType type = WineThemeManager.BackgroundType.values()[position];
                ipvDesktopBackgroundImage.setVisibility(View.GONE);
                cpvDesktopBackgroundColor.setVisibility(View.GONE);

                if (type == WineThemeManager.BackgroundType.IMAGE) {
                    ipvDesktopBackgroundImage.setVisibility(View.VISIBLE);
                }
                else if (type == WineThemeManager.BackgroundType.COLOR) {
                    cpvDesktopBackgroundColor.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        sDesktopBackgroundType.setSelection(desktopTheme.backgroundType.ordinal());

        File containerDir = isEditMode() ? container.getRootDir() : null;
        File userRegFile = new File(containerDir, ".wine/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {

            List<String> rendererList = Arrays.asList(context.getString(R.string.gl), context.getString(R.string.vulkan), context.getString(R.string.gdi));
            Spinner sRenderer = view.findViewById(R.id.SRenderer);
            sRenderer.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, rendererList));
            AppUtils.setSpinnerSelectionFromValue(sRenderer, registryEditor.getStringValue("Software\\Wine\\Direct3D", "renderer", "vulkan"));

            List<String> stateList = Arrays.asList(context.getString(R.string.disable), context.getString(R.string.enable));
            Spinner sCSMT = view.findViewById(R.id.SCSMT);
            sCSMT.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, stateList));
            sCSMT.setSelection(registryEditor.getDwordValue("Software\\Wine\\Direct3D", "csmt", 3) != 0 ? 1 : 0);

            // GPU name spinner (index 0 = "Auto"). -1 means "no saved override",
            // so no card matches and "Auto" stays selected.
            // GPU 이름 스피너(0번 = "자동"). -1은 "저장된 값 없음"을 뜻해서
            // 어떤 카드와도 매칭되지 않고 "자동"이 선택된 채로 남음
            final Spinner sGPUName = view.findViewById(R.id.SGPUName);
            loadGPUNameSpinner(sGPUName, registryEditor.getDwordValue("Software\\Wine\\Direct3D", "VideoPciDeviceID", -1));

            List<String> offscreenRenderingModeList = Arrays.asList("Backbuffer", "FBO");
            Spinner sOffscreenRenderingMode = view.findViewById(R.id.SOffscreenRenderingMode);
            sOffscreenRenderingMode.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, offscreenRenderingModeList));
            AppUtils.setSpinnerSelectionFromValue(sOffscreenRenderingMode, registryEditor.getStringValue("Software\\Wine\\Direct3D", "OffScreenRenderingMode", "fbo"));

            Spinner sStrictShaderMath = view.findViewById(R.id.SStrictShaderMath);
            sStrictShaderMath.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, stateList));
            sStrictShaderMath.setSelection(Math.min(registryEditor.getDwordValue("Software\\Wine\\Direct3D", "strict_shader_math", 1), 1));

            // Forced VRAM size spinner (index 0 = "Auto"). Declared here because
            // the compatibility-mode listener below captures it.
            // 강제 VRAM 크기 스피너(0번 = "자동"). 아래 호환 모드 리스너가
            // 이 변수를 캡처하기 때문에 여기서 선언해야 함
            final Spinner sVideoMemorySize = view.findViewById(R.id.SVideoMemorySize);
            // One-tap compatibility fallback: spoof as GTX 1080 + 4096MB when checked,
            // revert to "Auto" (no spoofing) when unchecked
            // 원클릭 호환 모드: 체크하면 GTX 1080 + 4096MB로 위장, 해제하면 다시 "자동"으로
            SwitchCompat cbGPUCompatibilityMode = view.findViewById(R.id.CBGPUCompatibilityMode);
            cbGPUCompatibilityMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    AppUtils.setSpinnerSelectionFromValue(sGPUName, "NVIDIA GeForce GTX 1080");
                    AppUtils.setSpinnerSelectionFromNumber(sVideoMemorySize, "4096");
                } else {
                    sGPUName.setSelection(0);
                    sVideoMemorySize.setSelection(0);
                }
            });
            String videoMemorySize = registryEditor.getStringValue("Software\\Wine\\Direct3D", "VideoMemorySize", "2048");
            AppUtils.setSpinnerSelectionFromNumber(sVideoMemorySize, videoMemorySize);

            List<String> mouseWarpOverrideList = Arrays.asList(context.getString(R.string.disable), context.getString(R.string.enable), context.getString(R.string.force));
            Spinner sMouseWarpOverride = view.findViewById(R.id.SMouseWarpOverride);
            sMouseWarpOverride.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, mouseWarpOverrideList));
            AppUtils.setSpinnerSelectionFromValue(sMouseWarpOverride, registryEditor.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", "disable"));
        }
    }

    private void loadGPUNameSpinner(Spinner spinner, int selectedDeviceID) {
        List<String> values = new ArrayList<>();
        // Index 0 is always "Auto" (no spoofing) — real cards start at index 1
        // 0번은 항상 "자동"(위장 안 함) — 실제 카드 목록은 1번부터 시작
        values.add(getContext().getString(R.string.gpu_name_auto));
        int selectedPosition = 0;

        try {
            for (int i = 0; i < gpuCards.length(); i++) {
                JSONObject item = gpuCards.getJSONObject(i);
                if (item.getInt("deviceID") == selectedDeviceID) selectedPosition = i + 1;
                values.add(item.getString("name"));
            }
        }
        catch (JSONException e) {}

        spinner.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition);
    }

    public static String getScreenSize(View view) {
        Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        String value = sScreenSize.getSelectedItem().toString();
        if (value.equalsIgnoreCase("custom")) {
            value = Container.DEFAULT_SCREEN_SIZE;
            String strWidth = ((EditText)view.findViewById(R.id.ETScreenWidth)).getText().toString().trim();
            String strHeight = ((EditText)view.findViewById(R.id.ETScreenHeight)).getText().toString().trim();
            if (strWidth.matches("[0-9]+") && strHeight.matches("[0-9]+")) {
                int width = Integer.parseInt(strWidth);
                int height = Integer.parseInt(strHeight);
                if ((width % 2) == 0 && (height % 2) == 0) return width+"x"+height;
            }
        }
        return StringUtils.parseIdentifier(value);
    }

    private String getDesktopTheme(View view) {
        Spinner sDesktopBackgroundType = view.findViewById(R.id.SDesktopBackgroundType);
        WineThemeManager.BackgroundType type = WineThemeManager.BackgroundType.values()[sDesktopBackgroundType.getSelectedItemPosition()];
        Spinner sDesktopTheme = view.findViewById(R.id.SDesktopTheme);
        ColorPickerView cpvDesktopBackground = view.findViewById(R.id.CPVDesktopBackgroundColor);
        WineThemeManager.Theme theme = WineThemeManager.Theme.values()[sDesktopTheme.getSelectedItemPosition()];

        String desktopTheme = theme+","+type+","+cpvDesktopBackground.getColorAsString();
        if (type == WineThemeManager.BackgroundType.IMAGE) {
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(getContext());
            desktopTheme += ","+(userWallpaperFile.isFile() ? userWallpaperFile.lastModified() : "0");
        }
        return desktopTheme;
    }

    public static void loadScreenSizeSpinner(View view, String selectedValue) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);

        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);
        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String[] screenSize = selectedValue.split("x");
            ((EditText) view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText) view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    // New method: Adds support for the GraphicsDriverConfigDialog
    public void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper, final View vGraphicsDriverConfig, String selectedGraphicsDriver, String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();

        // Update the spinner with the available graphics driver options
        updateGraphicsDriverSpinner(context, sGraphicsDriver);

        Runnable update = () -> {
            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());

            // Update the DXWrapper spinner
            ArrayList<String> items = new ArrayList<>();
            for (String value : context.getResources().getStringArray(R.array.dxwrapper_entries)) {
                items.add(value);
            }
            sDXWrapper.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items.toArray()));
            AppUtils.setSpinnerSelectionFromIdentifier(sDXWrapper, selectedDXWrapper);

            vGraphicsDriverConfig.setOnClickListener((v) -> {
                new GraphicsDriverConfigDialog(vGraphicsDriverConfig, graphicsDriver, null).show();
            });
            vGraphicsDriverConfig.setVisibility(View.VISIBLE);
        };

        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Set the spinner's initial selection
        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsDriver, selectedGraphicsDriver);
        update.run();
    }

    // Sets up the graphics wrapper spinner. "DXVK + VKD3D" is now a single
    // combined option (see arrays.xml) since real Wine/Proton setups always
    // run both together - DXVK handles D3D9-11, VKD3D handles D3D12.
    // Selecting it opens DXWrapperConfigDialog, which already has spinners for
    // both the DXVK version and the VKD3D version in one screen.
    // 그래픽 래퍼 스피너 설정. "DXVK + VKD3D"는 이제 하나의 통합 옵션임
    // (arrays.xml 참고) - 실제 Wine/Proton 환경에서는 항상 둘 다 같이 동작하기
    // 때문(DXVK=D3D9~11, VKD3D=D3D12). 이걸 선택하면 DXWrapperConfigDialog가
    // 열리는데, 이 화면 안에 이미 DXVK 버전/VKD3D 버전 스피너가 둘 다 있음
    public static void setupDXWrapperSpinner(final Spinner sDXWrapper, final View vDXWrapperConfig, final Spinner sWineVersion) {
        sDXWrapper.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                if (dxwrapper.equals("dxvk-vkd3d")) {
                    vDXWrapperConfig.setOnClickListener((v) -> {
                        Context ctx = vDXWrapperConfig.getContext();
                        ContentsManager tmpManager = new ContentsManager(ctx);
                        String wineVersion = sWineVersion.getSelectedItem() != null ? sWineVersion.getSelectedItem().toString() : "";
                        boolean isArm64EC = WineInfo.fromIdentifier(ctx, tmpManager, wineVersion).isArm64EC();
                        (new DXWrapperConfigDialog(vDXWrapperConfig, isArm64EC)).show();
                    });
                    vDXWrapperConfig.setVisibility(View.VISIBLE);
                } else vDXWrapperConfig.setVisibility(View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // The layer is asked for when the config dialog opens, so a
    // container changed in the same screen (ARM64EC <-> X86_64) is respected
    // 레이어를 설정 창이 열릴 때 물어봄. 같은 화면에서 컨테이너를 바꿨을 때
    // (ARM64EC <-> X86_64) 그 값을 따르게 하기 위함
    public static void setupDXWrapperSpinner(final Spinner sDXWrapper, final View vDXWrapperConfig,
                                             final java.util.function.BooleanSupplier isArm64EC) {
        sDXWrapper.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                if (dxwrapper.equals("dxvk-vkd3d")) {
                    vDXWrapperConfig.setOnClickListener((v) ->
                            (new DXWrapperConfigDialog(vDXWrapperConfig, isArm64EC.getAsBoolean())).show());
                    vDXWrapperConfig.setVisibility(View.VISIBLE);
                } else vDXWrapperConfig.setVisibility(View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public static void setupDXWrapperSpinner(final Spinner sDXWrapper, final View vDXWrapperConfig, final boolean isArm64EC) {
        sDXWrapper.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                if (dxwrapper.equals("dxvk-vkd3d")) {
                    vDXWrapperConfig.setOnClickListener((v) -> (new DXWrapperConfigDialog(vDXWrapperConfig, isArm64EC)).show());
                    vDXWrapperConfig.setVisibility(View.VISIBLE);
                } else vDXWrapperConfig.setVisibility(View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public static void setupDDrawSpinner(final Spinner sDDrawspinner, String selectedDDrawrapper) {
        final Context context = sDDrawspinner.getContext();
        ArrayList<String> items = new ArrayList<>();
        for (String value : context.getResources().getStringArray(R.array.ddrawrapper_entries)) {
            items.add(value);
        }
        sDDrawspinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items.toArray(new String[0])));
        AppUtils.setSpinnerSelectionFromIdentifier(sDDrawspinner, selectedDDrawrapper);
    }


    public static String getWinComponents(View view) {
        ViewGroup parent = view.findViewById(R.id.LLTabWinComponents);
        ArrayList<View> views = new ArrayList<>();
        AppUtils.findViewsWithClass(parent, Spinner.class, views);
        String[] wincomponents = new String[views.size()];

        for (int i = 0; i < views.size(); i++) {
            Spinner spinner = (Spinner)views.get(i);
            wincomponents[i] = spinner.getTag()+"="+spinner.getSelectedItemPosition();
        }
        return String.join(",", wincomponents);
    }

    // Visual C++ 2015-2022 row: picking native when its files are not taken yet
    // offers the one-time download; declining or a failure puts the row back
    // Visual C++ 2015~2022 행: 파일을 아직 안 꺼낸 상태에서 native를 고르면 한 번
    // 받기를 제안함. 거절하거나 실패하면 행을 원래대로 되돌림
    private static void watchVCRedist(Spinner spinner) {
        if (!com.winlator.cmod.core.VCRedistInstaller.ID.equals(spinner.getTag())) return;
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                if (position != 1) return;
                com.winlator.cmod.core.VCRedistInstaller.ensureInstalled(spinner.getContext(), ok -> {
                    if (!ok) spinner.setSelection(0, false);
                });
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    public static void createWinComponentsTab(View view, String wincomponents) {
        Context context = view.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ViewGroup tabView = view.findViewById(R.id.LLTabWinComponents);
        ViewGroup directxSectionView = tabView.findViewById(R.id.LLWinComponentsDirectX);
        ViewGroup generalSectionView = tabView.findViewById(R.id.LLWinComponentsGeneral);

        for (String[] wincomponent : new KeyValueSet(wincomponents)) {
            ViewGroup parent = wincomponent[0].startsWith("direct") ? directxSectionView : generalSectionView;
            View itemView = inflater.inflate(R.layout.wincomponent_list_item, parent, false);
            ((TextView)itemView.findViewById(R.id.TextView)).setText(StringUtils.getString(context, wincomponent[0]));
            Spinner spinner = itemView.findViewById(R.id.Spinner);
            spinner.setSelection(Integer.parseInt(wincomponent[1]), false);
            spinner.setTag(wincomponent[0]);
            watchVCRedist(spinner);

            parent.addView(itemView);

        }
    }

    // BUG FIX: wincomponent_list_item.xml's Spinner is NeonComboBox now,
    // whose own popupBackground (neon_popup_bg) already resolves per theme -
    // the override these two methods used to do right after inflating each
    // row (content_dialog_background(.dark), the pre-neon drawable) was
    // undoing that on every dynamically-added row. This was the actual cause
    // of the WIN COMPONENTS tab's plain popups.
    // 버그 수정: wincomponent_list_item.xml의 Spinner가 이제 NeonComboBox임.
    // 그 자체의 popupBackground(neon_popup_bg)가 이미 테마별로 알맞게
    // 풀리는데, 이 두 메서드가 각 행을 inflate한 직후 하던 오버라이드(네온
    // 이전 드로어블인 content_dialog_background(.dark))가 동적으로 추가되는
    // 행마다 그걸 지우고 있었음. WIN COMPONENTS 탭 팝업이 밋밋했던 실제
    // 원인이 이것임
    public static void createWinComponentsTabFromShortcut(ShortcutSettingsDialog dialog, View view, String wincomponents, boolean isDarkMode) {
        Context context = dialog.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ViewGroup tabView = view.findViewById(R.id.LLTabWinComponents);
        ViewGroup directxSectionView = tabView.findViewById(R.id.LLWinComponentsDirectX);
        ViewGroup generalSectionView = tabView.findViewById(R.id.LLWinComponentsGeneral);

        for (String[] wincomponent : new KeyValueSet(wincomponents)) {
            ViewGroup parent = wincomponent[0].startsWith("direct") ? directxSectionView : generalSectionView;
            View itemView = inflater.inflate(R.layout.wincomponent_list_item, parent, false);
            ((TextView) itemView.findViewById(R.id.TextView)).setText(StringUtils.getString(context, wincomponent[0]));
            Spinner spinner = itemView.findViewById(R.id.Spinner);
            spinner.setSelection(Integer.parseInt(wincomponent[1]), false);
            spinner.setTag(wincomponent[0]);
            watchVCRedist(spinner);

            parent.addView(itemView);
        }

        // Notify that the views are ready
        dialog.onWinComponentsViewsAdded(isDarkMode);
    }

    private EnvVarsView createEnvVarsTab(final View view) {
        final Context context = view.getContext();
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);

        // Apply dark mode setting to the existing instance
        envVarsView.setDarkMode(isDarkMode); // New setter method

        envVarsView.setEnvVars(new EnvVars(isEditMode() ? container.getEnvVars() : Container.DEFAULT_ENV_VARS));
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());
        return envVarsView;
    }

    private String getDrives(View view) {
        LinearLayout parent = view.findViewById(R.id.LLDrives);
        String drives = "";

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            Spinner spinner = child.findViewById(R.id.Spinner);
            EditText editText = child.findViewById(R.id.EditText);
            String path = editText.getText().toString().trim();
            if (!path.isEmpty()) drives += spinner.getSelectedItem()+path;
        }
        return drives;
    }

    private void createDrivesTab(View view) {
        final Context context = getContext();

        final LinearLayout parent = view.findViewById(R.id.LLDrives);
        final View emptyTextView = view.findViewById(R.id.TVDrivesEmptyText);
        LayoutInflater inflater = LayoutInflater.from(context);
        final String drives = isEditMode() ? container.getDrives() : com.winlator.cmod.core.StorageDrives.defaultDrives(getContext());
        final String[] driveLetters = new String[Container.MAX_DRIVE_LETTERS];
        for (int i = 0; i < driveLetters.length; i++) driveLetters[i] = ((char)(i + 68))+":";

        Callback<String[]> addItem = (drive) -> {
            final View itemView = inflater.inflate(R.layout.drive_list_item, parent, false);
            Spinner spinner = itemView.findViewById(R.id.Spinner);
            spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, driveLetters));
            AppUtils.setSpinnerSelectionFromValue(spinner, drive[0]+":");

            // BUG FIX: same as WinComponents above - drive_list_item.xml's
            // Spinner is NeonComboBox now, so this override just undid its
            // own popupBackground on every drive row added.
            // 버그 수정: 위 WinComponents와 같음 - drive_list_item.xml의
            // Spinner가 이제 NeonComboBox라, 이 오버라이드는 드라이브 행을
            // 추가할 때마다 자기 popupBackground를 지우고 있었을 뿐임

            final EditText editText = itemView.findViewById(R.id.EditText);
            editText.setText(drive[1]);

            // Apply dark theme to EditText if necessary
            applyDarkThemeToEditText(editText);

            // Apply dark theme to the search button if necessary
            View btSearch = itemView.findViewById(R.id.BTSearch);
            applyDarkThemeToButton(btSearch);

            itemView.findViewById(R.id.BTSearch).setOnClickListener((v) -> {
                openDirectoryCallback = (path) -> {
                    drive[1] = path;
                    editText.setText(path);
                };
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.fromFile(Environment.getExternalStorageDirectory()));
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_DIRECTORY_REQUEST_CODE);
            });

            itemView.findViewById(R.id.BTRemove).setOnClickListener((v) -> {
                parent.removeView(itemView);
                if (parent.getChildCount() == 0) emptyTextView.setVisibility(View.VISIBLE);
            });
            parent.addView(itemView);

            // Hide empty text view if there are items
            emptyTextView.setVisibility(View.GONE);
        };
        for (String[] drive : Container.drivesIterator(drives)) addItem.call(drive);

        view.findViewById(R.id.BTAddDrive).setOnClickListener((v) -> {
            if (parent.getChildCount() >= Container.MAX_DRIVE_LETTERS) return;
            final String nextDriveLetter = String.valueOf(driveLetters[parent.getChildCount()].charAt(0));
            addItem.call(new String[]{nextDriveLetter, ""});
        });

        if (drives.isEmpty()) emptyTextView.setVisibility(View.VISIBLE);
    }

    // Helper method to apply dark theme to EditText
    private static void applyDarkThemeToEditText(EditText editText) {
        // Disabled: the drive / env-var rows now use the theme's EditText,
        // whose text and underline follow ?android:textColorPrimary and
        // colorControlActivated. Forcing edit_text(_dark) here kept those rows
        // in the old palette.
        // 비활성화함: 드라이브 / 환경변수 행이 이제 테마의 EditText를 씀. 글자와
        // 밑줄이 ?android:textColorPrimary와 colorControlActivated를 따라감.
        // 여기서 edit_text(_dark)를 강제하면 그 행들만 옛 색으로 남았음
        if (true) return;
        if (isDarkMode) {
            editText.setTextColor(Color.WHITE); // Set text color to white for dark theme
            editText.setHintTextColor(Color.GRAY); // Set hint color to gray
            editText.setBackgroundResource(R.drawable.edit_text_dark); // Custom dark background drawable
        } else {
            editText.setTextColor(Color.BLACK); // Default text color
            editText.setHintTextColor(Color.GRAY); // Default hint color
            editText.setBackgroundResource(R.drawable.edit_text); // Custom light background drawable
        }
    }

    // Helper method to apply dark theme to buttons or other clickable views
    private void applyDarkThemeToButton(View button) {

    }

    // New container screen: DXVK / VKD3D, Box64 (WOWBox64 on ARM64EC) and FEXCore
    // start on the builds an automatic container gets for the selected Wine/Proton
    // (ContainerManager.newContainerDefaults()). The DefaultVersion constants used
    // before named builds that are not installed (box64 0.3.7, DXVK
    // 3.0.2-arm64ec-gplasync, VKD3D sm69), so a container made with the defaults
    // started on them. A value missing from a list leaves that list as it is
    // 새 컨테이너 화면: DXVK / VKD3D, Box64(ARM64EC는 WOWBox64), FEXCore를 선택한
    // Wine/Proton으로 자동 컨테이너를 만들 때와 같은 빌드로 시작함
    // (ContainerManager.newContainerDefaults()). 예전에 쓰던 DefaultVersion 상수는
    // 설치되지 않은 빌드(box64 0.3.7, DXVK 3.0.2-arm64ec-gplasync, VKD3D sm69)를
    // 가리켜서, 기본값으로 만든 컨테이너가 그 값으로 시작했음. 목록에 없는 값이면
    // 그 목록은 그대로 둠
    private void applyNewContainerDefaults(View view, String wineVersion, Spinner sBox64Version) {
        org.json.JSONObject d = manager.newContainerDefaults(contentsManager, wineVersion);
        if (d == null) return;
        String cfg = d.optString("dxwrapperConfig", "");
        if (!cfg.isEmpty()) view.findViewById(R.id.BTDXWrapperConfig).setTag(cfg);
        selectIfPresent(sBox64Version, d.optString("box64Version", ""));
        selectIfPresent(view.findViewById(R.id.SFEXCoreVersion), d.optString("fexcoreVersion", ""));
    }

    // Selects the item equal to value (letter case ignored); leaves the spinner
    // alone when there is no such item (AppUtils.setSpinnerSelectionFromValue()
    // would jump to the first item)
    // value와 같은 항목을 선택함(대소문자 무시). 그런 항목이 없으면 그대로 둠
    // (AppUtils.setSpinnerSelectionFromValue()는 첫 항목으로 옮겨 버림)
    private static void selectIfPresent(Spinner spinner, String value) {
        if (spinner == null || value == null || value.isEmpty()) return;
        for (int i = 0; i < spinner.getCount(); i++) {
            if (String.valueOf(spinner.getItemAtPosition(i)).equalsIgnoreCase(value)) {
                spinner.setSelection(i, false);
                return;
            }
        }
    }

    private void loadWineVersionSpinner(final View view, Spinner sWineVersion, Spinner sBox64Version) {
        final Context context = getContext();
        sWineVersion.setEnabled(!isEditMode());
//
        sWineVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                SwitchCompat cbWoW64Mode = view.findViewById(R.id.CBWoW64Mode);
                FrameLayout fexcoreFL = view.findViewById(R.id.fexcoreFrame);
                Spinner sEmulator = view.findViewById(R.id.SEmulator);
                Spinner sEmulator64 = view.findViewById(R.id.SEmulator64);
                sEmulator64.setEnabled(false);
                String wineVersion = sWineVersion.getSelectedItem().toString();
                WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
                if (wineInfo.isArm64EC()) {
                    fexcoreFL.setVisibility(View.VISIBLE);
                    sEmulator.setEnabled(true);
                    sEmulator64.setSelection(0);
                    if (!isEditMode()) sEmulator.setSelection(0);
                }
                else {
                    fexcoreFL.setVisibility(View.GONE);
                    sEmulator.setEnabled(false);
                    sEmulator.setSelection(1);
                    sEmulator64.setSelection(1);
                }
                // Box64 frame follows the same rule as the FEXCore frame, in reverse:
                // ARM64EC runs x86 code through FEXCore (HODLL=libwow64fex.dll), so
                // the Box64 settings do nothing there. They only matter for X86_64,
                // or for an older ARM64EC container whose DLL emulator is still Box64
                // (WOWBox64) - that picker is hidden, so this only covers old data.
                // Box64 영역도 FEXCore 영역과 같은 규칙을 반대로 따름: ARM64EC는 x86 코드를
                // FEXCore(HODLL=libwow64fex.dll)로 돌리므로 Box64 설정이 아무 효과가 없음.
                // X86_64일 때, 또는 DLL 에뮬레이터가 아직 Box64(WOWBox64)로 남아 있는 예전
                // ARM64EC 컨테이너일 때만 의미가 있음 - 그 선택기는 숨겨져 있어서 예전 데이터만 해당함
                FrameLayout box64FL = view.findViewById(R.id.box86box64Frame);
                boolean usesBox64 = !wineInfo.isArm64EC()
                        || "Box64".equalsIgnoreCase(String.valueOf(sEmulator.getSelectedItem()));
                box64FL.setVisibility(usesBox64 ? View.VISIBLE : View.GONE);
                loadBox64VersionSpinner(context, container, contentsManager, sBox64Version, wineInfo.isArm64EC());
                // New container: start from the builds an automatic container gets
                // (see applyNewContainerDefaults())
                // 새 컨테이너: 자동 컨테이너와 같은 빌드로 시작함(applyNewContainerDefaults() 참고)
                if (!isEditMode()) applyNewContainerDefaults(view, wineVersion, sBox64Version);
                cbWoW64Mode.setEnabled(true); // Always allow user to toggle WoW64 mode

                // New container: follow the selected layer name, but only while the
                // name is still the one we filled in - a name the user typed is kept
                // 새 컨테이너: 선택된 레이어 이름을 따라감. 단, 이름이 아직 자동으로
                // 넣은 값일 때만 바꿈 - 사용자가 직접 입력한 이름은 유지함
                if (!isEditMode()) {
                    EditText etName = view.findViewById(R.id.ETName);
                    String current = etName.getText().toString();
                    if (current.isEmpty() || current.equals(autoContainerName)) {
                        autoContainerName = manager.makeUniqueName(
                                ContainerManager.buildLayerName(wineVersion, contentsManager));
                        etName.setText(autoContainerName);
                    }
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                FrameLayout fexcoreFL = view.findViewById(R.id.fexcoreFrame);
                Spinner sEmulator = view.findViewById(R.id.SEmulator);
                Spinner sEmulator64 = view.findViewById(R.id.SEmulator64);
                sEmulator64.setEnabled(false);
                String wineVersion = sWineVersion.getSelectedItem().toString();
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
                // Same Box64 frame rule as onItemSelected() above
                // 위 onItemSelected()와 같은 Box64 영역 규칙임
                FrameLayout box64FL = view.findViewById(R.id.box86box64Frame);
                boolean usesBox64 = !wineInfo.isArm64EC()
                        || "Box64".equalsIgnoreCase(String.valueOf(sEmulator.getSelectedItem()));
                box64FL.setVisibility(usesBox64 ? View.VISIBLE : View.GONE);
                loadBox64VersionSpinner(context, container, contentsManager, sBox64Version, wineInfo.isArm64EC());
            }
        });


        view.findViewById(R.id.LLWineVersion).setVisibility(View.VISIBLE);
        String[] versions = getResources().getStringArray(R.array.wine_entries);
        ArrayList<String> wineVersions = new ArrayList<>();
        wineVersions.addAll(Arrays.asList(versions));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE))
            wineVersions.add(ContentsManager.getEntryName(profile));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON))
            wineVersions.add(ContentsManager.getEntryName(profile));
        sWineVersion.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, wineVersions));
        if (isEditMode()) AppUtils.setSpinnerSelectionFromValue(sWineVersion, container.getWineVersion());
    }

    public static void updateGraphicsDriverSpinner(Context context, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.graphics_driver_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        
        // Set the adapter with the combined list
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }

    public static void loadBox64VersionSpinner(Context context, Container container, ContentsManager manager, Spinner spinner, boolean isArm64EC) {
        List<String> itemList;
        if (isArm64EC) {
            String[] originalItems = context.getResources().getStringArray(R.array.wowbox64_version_entries);
            itemList = new ArrayList<>(Arrays.asList(originalItems));
        }
        else {
            String[] originalItems = context.getResources().getStringArray(R.array.box64_version_entries);
            itemList = new ArrayList<>(Arrays.asList(originalItems));
        }
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
        if (container != null)
            AppUtils.setSpinnerSelectionFromValue(spinner, container.getBox64Version());
        else
            AppUtils.setSpinnerSelectionFromValue(spinner, DefaultVersion.BOX64);
    }

}


