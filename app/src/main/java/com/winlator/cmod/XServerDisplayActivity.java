package com.winlator.cmod;

import static com.winlator.cmod.core.AppUtils.showToast;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ArrayAdapter;
import androidx.appcompat.widget.SwitchCompat;
// CheckBox -> SwitchCompat for input_controls_dialog.xml, same reasoning as elsewhere.
// input_controls_dialog.xml용 CheckBox -> SwitchCompat. 다른 곳과 같은 이유임
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.box86_64.rc.RCFile;
import com.winlator.cmod.box86_64.rc.RCManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ControllerAssignmentDialog;

import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contentdialog.ScreenEffectDialog;
import com.winlator.cmod.contentdialog.DXWrapperConfigDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.DXVKConfig;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.EnvironmentManager;
import com.winlator.cmod.core.VCRedistInstaller;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.LocaleEnv;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.KoreanFontBuilder;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StorageDrives;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineRequestHandler;
import com.winlator.cmod.core.WineStartMenuCreator;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.inputcontrols.MotionControls;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.midi.MidiHandler;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.effects.CRTEffect;
import com.winlator.cmod.renderer.effects.ColorEffect;
import com.winlator.cmod.renderer.effects.FXAAEffect;
import com.winlator.cmod.renderer.effects.NTSCCombinedEffect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.LogView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.TaskManagerDialog;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.BionicProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.xenvironment.components.XServerComponent;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    private FrameRating frameRating = null;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    // Visual C++ 2015-2022 for this launch: on or off, and the key / name of the game
    // whose own DLL copies are used and kept (see VCRedistInstaller.gameKey)
    // 이번 실행의 Visual C++ 2015~2022: 켜짐 여부, 게임별 DLL 사본을 쓰고 보관할 게임의
    // 키·이름(VCRedistInstaller.gameKey 참고)
    private boolean vcrunOn = false;
    private String vcrunGameKey = null;
    private String vcrunGameLabel = null;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private HashMap<String, String> graphicsDriverConfig;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String emulator = Container.DEFAULT_EMULATOR;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private String ddrawrapper = Container.DEFAULT_DDRAWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private String startupSelection;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    private SharedPreferences preferences;
    private OnExtractFileListener onExtractFileListener;
    private WinHandler winHandler;
    private WineRequestHandler wineRequestHandler;
    private float globalCursorSpeed = 1.0f;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private short taskAffinityMask = 0;
    private int frameRatingWindowId = -1;
    private boolean pointerCaptureRequested = false; // Flag to track if pointer capture was requested
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private boolean navigationFocused = false;
    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean isRelativeMouseMovement;

    // Inside the XServerDisplayActivity class
    private ExternalController controller;

    // Playtime stats tracking
    private long startTime;
    // Wall-clock time the game was launched: start of this session's save window
    // (SharedSaves.exportGame()) and the quick-exit check for the layer hint
    // 게임을 실행한 시각: 이번 세션 세이브 구간의 시작(SharedSaves.exportGame())이자
    // 레이어 추천용 바로 종료 판정의 기준
    private long launchWallMs;
    private String previousSaveWindows;
    // Accumulates the current session in memory; only flushed to the
    // shortcut's own playtimeSec extra (see persistPlaytimeToShortcut())
    // rather than written to disk on every 1s tick like the old
    // playtimePrefs-based version did - a full .desktop file rewrite every
    // second is a lot heavier than the SharedPreferences write it replaced
    // 현재 세션 값을 메모리에만 누적함. persistPlaytimeToShortcut()에서만
    // 숏컷 자체의 playtimeSec extra로 내려씀 - 예전 playtimePrefs 방식처럼
    // 1초마다 디스크에 쓰지 않음. .desktop 파일 전체를 매초 다시 쓰는 건
    // 이게 대체하는 SharedPreferences 쓰기보다 훨씬 무거움
    private long sessionPlaytimeSeconds;
    private int ticksSinceLastPersist;
    // Flush to disk this often even mid-session, so a hard kill (not a
    // graceful pause) loses at most this many seconds rather than the
    // whole session
    // 세션 도중이라도 이 주기로는 디스크에 반영함. 앱이 정상 종료가 아니라
    // 강제 종료되더라도 세션 전체가 아니라 이 시간만큼만 잃도록 하기 위함
    private static final int PERSIST_EVERY_N_TICKS = 30;
    private String shortcutName;
    private Handler handler;
    private Runnable savePlaytimeRunnable;
    private static final long SAVE_INTERVAL_MS = 1000;

    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;

    private boolean isDarkMode;

    private String screenEffectProfile;

    private BionicProgramLauncherComponent bionicLauncher; // Reference to BionicProgramLauncherComponent

    private EnvVars overrideEnvVars;

    boolean isMouseDisabled;

    private AudioDeviceCallback audioDeviceCallback;
    private AudioManager audioManager;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (configChangedCallback != null) {
            configChangedCallback.run();
            configChangedCallback = null;
        }
    }

    // gyroListener removed: this was a duplicate of MotionControls' own
    // SensorEventListener, registered independently on the same physical
    // gyroscope and sharing the same "gyro_enabled" preference. Whenever the
    // user opened the Motion Controls menu while this old listener was also
    // registered, both fired on every sensor event and fought over
    // winHandler.updateGyroData() - this one with raw, unrotated axis data,
    // MotionControls' with proper screen-rotation-aware axis remapping. This
    // was the more complete implementation, so it stays as the only one.
    // gyroListener 제거함: MotionControls 자체의 SensorEventListener와
    // 중복이었음. 같은 물리 자이로 센서에 독립적으로 등록되고, 같은
    // "gyro_enabled" 설정을 공유했음. 이 구 리스너가 등록된 채로 사용자가
    // Motion Controls 메뉴를 열면, 센서 이벤트마다 둘 다 반응해서
    // winHandler.updateGyroData()를 두고 서로 충돌했음 - 이쪽은 회전 보정
    // 없는 원본 축 값을, MotionControls 쪽은 화면 회전을 제대로 반영한 축
    // 값을 보냈음. 더 완전한 구현인 MotionControls만 남김


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Game-running marker for the download service, written first thing:
        // written later in setup, the download kept going for the
        // first ~20 seconds of a game (logcat 12:58:30 -> 12:58:54)
        // 다운로드 서비스용 게임 실행 표시를 가장 먼저 씀: 설정 중에 늦게 쓰면
        // 게임 시작 후 약 20초 동안 다운로드가 계속됐음(로그캣 12:58:30 -> 12:58:54)
        com.winlator.cmod.steam.SteamDownloadState.markGameRunning(this, true);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);

        ControllerManager.getInstance().init(this);

        setupAudioDeviceListener();


        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);


        // Check for Dark Mode
        isDarkMode = preferences.getBoolean("dark_mode", false);

        boolean isOpenWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean isShareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);

        // Initialize the WinHandler after context is set up
        winHandler = new WinHandler(this);
        controller = winHandler.getCurrentController();

        if (isOpenWithAndroidBrowser || isShareAndroidClipboard)
            wineRequestHandler = new WineRequestHandler(this);

        if (controller != null) {
            int triggerType = preferences.getInt("trigger_type", ExternalController.TRIGGER_IS_AXIS); // Default to TRIGGER_IS_AXIS
            controller.setTriggerType((byte) triggerType); // Cast to byte if needed
        }


        // Check if xinputDisabled extra is passed
        boolean xinputDisabledFromShortcut = false;


        // Record the start time
        startTime = System.currentTimeMillis();
        launchWallMs = startTime;

        // Initialize handler for periodic saving
        handler = new Handler(Looper.getMainLooper());
        savePlaytimeRunnable = new Runnable() {
            @Override
            public void run() {
                savePlaytimeData(false);
                handler.postDelayed(this, SAVE_INTERVAL_MS);
            }
        };
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);


        // Handler and Runnable to manage timeout for hiding controls

        // BUG FIX: this captured the pref ONCE with default true, while the
        // settings switch reads it with default false. On a fresh install the
        // switch showed OFF but controls still vanished after 5s. The value is
        // now read each time the runnable fires, with one shared default (false
        // = controls always visible), so the switch works without a restart.
        // 버그 수정: 예전엔 설정값을 기본값 true로 딱 한 번 읽어 붙잡아 두었고,
        // 설정 스위치는 기본값 false로 읽었음. 그래서 새로 설치하면 스위치는
        // 꺼져 보이는데 컨트롤이 5초 뒤 사라졌음. 이제 실행될 때마다 값을 새로
        // 읽고 기본값을 하나로 통일함(false = 항상 표시). 재시작 없이 바로 반영됨
        hideControlsRunnable = () -> {
            if (preferences.getBoolean("touchscreen_timeout_enabled", false)) {
                inputControlsView.setVisibility(View.GONE);
                Log.d("XServerDisplayActivity", "Touchscreen controls hidden after timeout.");
            }
        };


        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        drawerLayout = findViewById(R.id.DrawerLayout);

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                // The mode can also change in the input controls dialog, so
                // the title is refreshed every time the drawer opens
                // 입력 컨트롤 창에서도 모드가 바뀔 수 있으므로, 드로어를 열 때마다
                // 제목을 새로 맞춤
                updateTouchModeMenuItem();
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                // Sub-sections start folded every time the drawer is reopened
                // 드로어를 다시 열 때마다 하위 섹션은 접힌 상태로 시작함
                collapseAllSectionsSilently();
                // If the user left Relative Mouse enabled, recapture.
                if (isRelativeMouseMovement && !pointerCaptureRequested) {
                    drawerLayout.postDelayed(() -> ensurePointerCapture("drawer-closed"), 2000);
                }
            }
        });


        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);


        navigationView = findViewById(R.id.NavigationView);

        // BUG FIX: this used to force plain white text and a flat grey
        // background whenever dark mode was on, running AFTER inflation and
        // silently overwriting the neon app:itemTextColor / android:background
        // set in xserver_display_activity.xml - so the XML edit had zero
        // visible effect in dark mode, which is exactly what was reported.
        // The XML now uses ?attr/neon* colours that already resolve correctly
        // for both themes, so this branch has nothing left to do.
        // 버그 수정: 다크 모드일 때 인플레이트 직후 흰 글자 + 회색 배경을
        // 강제로 덮어쓰던 코드였음. xserver_display_activity.xml에 넣은 네온
        // app:itemTextColor / android:background를 조용히 지워 버려서, XML을
        // 고쳐도 다크 모드에서는 전혀 반영이 안 됐음(정확히 신고된 증상임).
        // 이제 XML이 두 테마 모두에서 알아서 맞는 색으로 풀리는 ?attr/neon*을
        // 쓰므로, 이 분기가 할 일이 없어짐

        enableLogs = preferences.getBoolean("enable_wine_debug", false)
                || preferences.getBoolean("enable_box86_64_logs", false);
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.main_menu_logs).setVisible(enableLogs);
        menu.findItem(R.id.main_menu_logs).setEnabled(enableLogs);
        // Was !XrActivity.isEnabled(this); XR mode is removed, so always allowed
        // 원래 !XrActivity.isEnabled(this)였음. XR 모드를 제거해서 항상 허용함
        allowMagnifier = true;


        navigationView.setNavigationItemSelectedListener(this);
        navigationView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_ARROW));
        navigationView.setOnFocusChangeListener((v, hasFocus) -> navigationFocused = hasFocus);


        // restore persisted states (default collapsed = false)
        // Always folded at start (user request); the saved values
        // are no longer restored
        // 시작할 때는 항상 접힌 상태임(요청). 저장된 값은 더 이상 복원하지 않음
        expInput = false;
        expDisplay = false;
        expSystem = false;

        applyGroup(menu, R.id.group_input, R.id.header_input, expInput);
        applyGroup(menu, R.id.group_display, R.id.header_display, expDisplay);
        applyGroup(menu, R.id.group_system, R.id.header_system, expSystem);

        // tune RV
        RecyclerView rv = navRecycler();
        if (rv != null) {
            rv.setItemAnimator(null);               // no default blink
            rv.setHasFixedSize(true);
            rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            Drawable bg = navigationView.getBackground();
            if (bg != null) rv.setBackground(bg);
        }

        imageFs = ImageFs.find(this);

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));

        // Log shortcut_path
        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        Log.d("XServerDisplayActivity", "Shortcut Path: " + shortcutPath);


        // Determine container ID
        int containerId = getIntent().getIntExtra("container_id", 0);
        Log.d("XServerDisplayActivity", "Container ID from Intent: " + containerId);
        if (containerId == 0) {
            Log.d("XServerDisplayActivity", "Container ID is 0, attempting to parse from .desktop file");
            // Proceed with .desktop file parsing
        }


        // If container_id is 0, read from the .desktop file
        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
            Log.d("XServerDisplayActivity", "Parsed Container ID from .desktop file: " + containerId);
        }


        // Initialize playtime tracking
        shortcutName = getIntent().getStringExtra("shortcut_name");

        // Ensure shortcutPath is not null before proceeding
        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            if (shortcutName == null || shortcutName.isEmpty()) {
                shortcutName = parseShortcutNameFromDesktopFile(new File(shortcutPath));
                Log.d("XServerDisplayActivity", "Parsed Shortcut Name from .desktop file: " + shortcutName);
            }
        } else {
            Log.d("XServerDisplayActivity", "No shortcut path provided, skipping shortcut parsing.");
        }

        // Log the final container_id
        Log.d("XServerDisplayActivity", "Final Container ID: " + containerId);

        // Retrieve the container and check if it's null
        container = containerManager.getContainerById(containerId);

        if (container == null) {
            Log.e("XServerDisplayActivity", "Failed to retrieve container with ID: " + containerId);
            finish();  // Gracefully exit the activity to avoid crashing
            return;
        }

        containerManager.activateContainer(container);

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }

        // Increment play count at the start of a session. Moved to after
        // `shortcut` above is actually assigned - it used to run before
        // that assignment, so incrementPlayCount() was always working with
        // whatever shortcut was left over from a previous launch (or null)
        // 세션 시작 시 재생 횟수 증가. 위에서 shortcut이 실제로 대입된
        // "다음"으로 옮김 - 예전엔 그 대입보다 먼저 실행되고 있어서,
        // incrementPlayCount()가 항상 이전 실행에서 남은 shortcut(혹은
        // null)을 갖고 동작하고 있었음
        incrementPlayCount();

        firstTimeBoot = container.getExtra("appVersion").isEmpty();

        String wineVersion = container.getWineVersion();
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        ProcessHelper.removeAllDebugCallbacks();
        if (enableLogs) {
            LogView.setFilename(getExecutable());
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        }

        // Retrieve secondary executable and delay
        String secondaryExec = shortcut != null ? shortcut.getExtra("secondaryExec") : null;
        int execDelay = shortcut != null ? Integer.parseInt(shortcut.getExtra("execDelay", "0")) : 0;

        // Debug logging for secondaryExec and execDelay
        Log.d("XServerDisplayActivity", "Secondary Exec: " + secondaryExec);
        Log.d("XServerDisplayActivity", "Execution Delay: " + execDelay);

        // If a secondary executable is specified, schedule it
        if (secondaryExec != null && !secondaryExec.isEmpty() && execDelay > 0) {
            scheduleSecondaryExecution(secondaryExec, execDelay);
            Log.d("XServerDisplayActivity", "Scheduling secondary execution: " + secondaryExec + " with delay: " + execDelay);
        } else {
            Log.d("XServerDisplayActivity", "No valid secondary executable or delay is zero, skipping scheduling.");
        }

        graphicsDriver = container.getGraphicsDriver();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriver = container.getAudioDriver();
        emulator = container.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = container.getDXWrapper();
        ddrawrapper = container.getDDrawWrapper();
        String dxwrapperConfig = container.getDXWrapperConfig();
        screenSize = container.getScreenSize();
        winHandler.setInputType((byte) container.getInputType());
        lc_all = container.getLC_ALL();

        // Log the entire intent to verify the extras
        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
            applyAudioFallback();
            emulator = shortcut.getExtra("emulator", container.getEmulator());
            dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            ddrawrapper = shortcut.getExtra("ddrawrapper", container.getDDrawWrapper());
            dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
            lc_all = shortcut.getExtra("lc_all", container.getLC_ALL());
            String inputType = shortcut.getExtra("inputType");
            if (!inputType.isEmpty()) winHandler.setInputType(Byte.parseByte(inputType));
            String xinputDisabledString = shortcut.getExtra("disableXinput", "false");
            xinputDisabledFromShortcut = parseBoolean(xinputDisabledString);
            // Pass the value to WinHandler
            winHandler.setXInputDisabled(xinputDisabledFromShortcut);
            String sharpnessEffect = shortcut.getExtra("sharpnessEffect", "None");
            if (!sharpnessEffect.equals("None")) {
                double sharpnessLevel = Double.parseDouble(shortcut.getExtra("sharpnessLevel", "100"));
                double sharpnessDenoise = Double.parseDouble(shortcut.getExtra("sharpnessDenoise", "100"));
                vkbasaltConfig = "effects=" + sharpnessEffect.toLowerCase() + ";" + "casSharpness=" + sharpnessLevel / 100 + ";" + "dlsSharpness=" + sharpnessLevel / 100 + ";" + "dlsDenoise=" + sharpnessDenoise / 100 + ";" + "enableOnLaunch=True";
            }
            Log.d("XServerDisplayActivity", "XInput Disabled from Shortcut: " + xinputDisabledFromShortcut);
        }

        this.graphicsDriverConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);

        if (dxwrapper.equals("dxvk") || dxwrapper.equals("vkd3d") || dxwrapper.equals("dxvk-vkd3d")) {
            this.dxwrapperConfig = DXVKConfig.parseConfig(dxwrapperConfig);
        }


        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/")) return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }


        preloaderDialog.show(R.string.starting_up);


        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(new ScreenInfo(screenSize));
        xServer.setWinHandler(winHandler);

        boolean[] winStarted = {false};

        // Add the OnWindowModificationListener for dynamic workarounds
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }

                // Only marks the frame; counting happens per drawn frame (see
                // setOnFrameDrawnListener in setupUI)
                // 표시만 해 둠. 세는 것은 그려진 프레임마다 함(setupUI의
                // setOnFrameDrawnListener 참고)
                if (frameRatingWindowId == window.id && frameRating != null) frameRating.markDirty();
            }

            private void setProcessAffinity(Window window, int processAffinity) {

                int processId = window.getProcessId();

                if (processId > 0) {
                    winHandler.setProcessAffinity(processId, processAffinity);
                } else if (!window.getClassName().isEmpty()) {
                    winHandler.setProcessAffinity(window.getClassName(), processAffinity);
                }
            }

            @Override
            public void onMapWindow(Window window) {

                String cpuList = container.getCPUList(true);

                // If a shortcut exists, let its setting override the container's default.
                if (shortcut != null) {
                    cpuList = shortcut.getExtra("cpuList", container.getCPUList(true));
                }

                // Calculate the final mask from the determined CPU list.
                short taskAffinityMask = (short) ProcessHelper.getAffinityMask(cpuList);

                // Apply the affinity mask.
                if (taskAffinityMask > 0) {
                    setProcessAffinity(window, taskAffinityMask);
                }
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                changeFrameRatingVisibility(window, property);
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, null);
            }
        });

        if (!midiSoundFont.equals("")) {
            InputStream in = null;
            InputStream finalIn = in;
            MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                @Override
                public void onSuccess(SF2Soundbank soundbank) {
                    midiHandler = new MidiHandler();
                    midiHandler.setSoundBank(soundbank);
                    midiHandler.start();
                }

                @Override
                public void onFailed(Exception e) {
                    try {
                        finalIn.close();
                    } catch (Exception e2) {
                    }
                }
            };
            try {
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                    MidiManager.load(in, callback);
                } else
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
            } catch (Exception e) {
            }
        }

        // Check if a profile is defined by the shortcut
        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        Runnable runnable = () -> {
            setupUI();
            if (controlsProfile.isEmpty()) {
                // CHANGED: a shortcut launch no longer borrows the global
                // "last profile". That global value is written by every in-game pick,
                // so a shortcut without its own profile silently followed whatever
                // another game chose last - the "saved for all shortcuts" symptom.
                // Shortcuts now only use their own extras (setupUI() applies them);
                // the global default is kept for launches without a shortcut.
                // 변경: 바로가기로 실행할 때 더 이상 전역 "마지막 프로필"을 빌려 쓰지
                // 않음. 그 전역 값은 게임 안에서 고를 때마다 저장되므로, 자기 프로필이 없는
                // 바로가기는 다른 게임에서 마지막으로 고른 값을 몰래 따라갔음 - 이것이
                // "모든 바로가기에 일괄 저장되는" 증상이었음. 이제 바로가기는 자기 extra만 씀
                // (setupUI()가 적용함). 전역 기본값은 바로가기 없이 실행할 때만 씀
                simulateConfirmInputControlsDialog(shortcut == null);
            }
            Executors.newSingleThreadExecutor().execute(() -> {

                setupWineSystemFiles();
                applyCjkFontLink();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();

                try {
                    setupXEnvironment();
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }

            });
        };

        if (xServer.screenInfo.height > xServer.screenInfo.width) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            configChangedCallback = runnable;
        } else
            runnable.run();
    }

    // Method to parse container_id from .desktop file
    private int parseContainerIdFromDesktopFile(File desktopFile) {
        int containerId = 0;
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("container_id=")) {
                        containerId = Integer.parseInt(line.split("=")[1].trim());
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Error parsing container_id from .desktop file", e);
            }
        }
        return containerId;
    }

    private boolean parseBoolean(String value) {
        // Return true for "true", "1", "yes" (case-insensitive)
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        // Return false for any other value, including "false", "0", "no"
        return false;
    }


    // Inside XServerDisplayActivity class
    private void handleCapturedPointer(MotionEvent event) {
        boolean handled = false;

        // Special: mouse 'Back' -> release capture and open drawer
        if (event.getAction() == MotionEvent.ACTION_BUTTON_PRESS
                && event.getActionButton() == MotionEvent.BUTTON_BACK) {
            if (pointerCaptureRequested && touchpadView != null) {
                touchpadView.releasePointerCapture();
                pointerCaptureRequested = false;
            }
            onBackPressed();
            return;
        }

        int actionButton = event.getActionButton();
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button press
                }
                handled = true;
                break;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button release
                }
                handled = true;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                if (xServer.isRelativeMouseMovement())
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int) transformedPoint[0], (int) transformedPoint[1], 0);
                else
                    xServer.injectPointerMoveDelta((int) transformedPoint[0], (int) transformedPoint[1]);
                handled = true;
                break;
            case MotionEvent.ACTION_SCROLL:
                float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY <= -1.0f) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int) scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                } else if (scrollY >= 1.0f) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int) scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                }
                handled = true;
                break;
        }
    }

    private void ensurePointerCapture(String reason) {
        if (!isRelativeMouseMovement || touchpadView == null) return;

        final int[] tries = {0};
        Runnable attempt = new Runnable() {
            @Override
            public void run() {
                if (!hasWindowFocus()) {
                    touchpadView.postDelayed(this, 50);
                    return;
                }
                if (!touchpadView.isAttachedToWindow()) {
                    touchpadView.postDelayed(this, 50);
                    return;
                }

                // Make sure the view can take focus
                touchpadView.setFocusableInTouchMode(true);
                touchpadView.requestFocus();

                touchpadView.requestPointerCapture();
                touchpadView.setOnCapturedPointerListener((v, e) -> {
                    handleCapturedPointer(e);
                    return true;
                });
                pointerCaptureRequested = true;

            }
        };
        // Try quickly a few times to dodge transient focus transitions
        touchpadView.postDelayed(attempt, 50); // First attempt
    }


    public boolean onCapturedPointerEvent(MotionEvent event) {
        handleCapturedPointer(event);
        return true;
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }


    @Override
    public void onResume() {
        super.onResume();

        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
            // Proactive audio stream check
            ALSAServerComponent alsaComponent = environment.getComponent(ALSAServerComponent.class);
            if (alsaComponent != null) {
                Log.d("XServerDisplayActivity", "onResume: Proactively checking audio stream health.");
                alsaComponent.notifyAudioDeviceChanged();
            }
        }
        startTime = System.currentTimeMillis();
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);
        ProcessHelper.resumeAllWineProcesses();
    }

    @Override
    public void onPause() {
        super.onPause();

        // Check if we are entering Picture-in-Picture mode
        if (!isInPictureInPictureMode()) {
            // Only pause environment and xServerView if not in PiP mode
            if (environment != null) {
                environment.onPause();
                xServerView.onPause();
            }
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
        ProcessHelper.pauseAllWineProcesses();
    }


    // tickFromTimer: true for the once-a-second background tick (accumulate
    // only, persist to disk every PERSIST_EVERY_N_TICKS ticks); false for a
    // real lifecycle boundary (pause/stop/exit), which always persists right
    // away so a graceful exit never loses the last few seconds
    // tickFromTimer: 매초 도는 백그라운드 틱이면 true(메모리에만 누적하고
    // PERSIST_EVERY_N_TICKS번마다 한 번씩만 디스크 반영); 실제 생명주기
    // 경계(일시정지/정지/종료)면 false로 줘서 항상 바로 반영함 - 정상
    // 종료에서는 마지막 몇 초라도 잃지 않게 하기 위함
    private void savePlaytimeData() {
        savePlaytimeData(true);
    }

    // A game that ends within 30 seconds of launch is marked for the layer hint
    // (ShortcutsFragment.maybeRecommendLayer() shows it after returning). commit()
    // because the process ends right after exit
    // 실행 후 30초 안에 끝난 게임은 레이어 추천 대상으로 표시함(돌아간 뒤
    // ShortcutsFragment.maybeRecommendLayer()가 보여 줌). 종료 직후 프로세스가
    // 끝나므로 commit()을 씀
    private void recordQuickExit() {
        if (shortcut == null || launchWallMs <= 0) return;
        if (System.currentTimeMillis() - launchWallMs >= 30_000L) return;
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(ShortcutsFragment.PREF_LAYER_HINT_PENDING, shortcut.file.getPath())
                .commit();
    }

    private void savePlaytimeData(boolean forcePersist) {
        long endTime = System.currentTimeMillis();
        long playtimeMs = endTime - startTime;
        if (playtimeMs < 0) playtimeMs = 0;

        sessionPlaytimeSeconds += playtimeMs / 1000;
        startTime = endTime;

        ticksSinceLastPersist++;
        if (forcePersist || ticksSinceLastPersist >= PERSIST_EVERY_N_TICKS) {
            persistPlaytimeToShortcut();
        }
    }

    // Single source of truth for playtime: writes straight onto the
    // shortcut's own playtimeSec extra, the same one ShortcutsFragment's
    // detail sheet reads. Old versions of this activity tracked playtime
    // separately in a "playtime_stats" SharedPreferences store, while
    // ShortcutsFragment tracked its own approximate figure by diffing
    // wall-clock time between launch and return - two numbers for the same
    // shortcut that only ever drifted further apart. This activity is the
    // one actually running while the game plays, so it's the accurate
    // source; ShortcutsFragment's pending-time fallback was removed.
    // 플레이타임의 단일 출처임: ShortcutsFragment의 상세창이 읽는 것과 같은
    // 숏컷 자체의 playtimeSec extra에 바로 씀. 예전에는 이 액티비티가
    // "playtime_stats"라는 별도 SharedPreferences에 따로 기록했고,
    // ShortcutsFragment는 실행~복귀 사이 시각 차이로 따로 근사치를 냈음 -
    // 같은 숏컷에 대해 서로 다른 두 숫자가 갈수록 벌어지기만 했음. 게임이
    // 도는 동안 실제로 떠 있는 건 이 액티비티라 여기가 정확한 출처이고,
    // ShortcutsFragment 쪽의 pending-time 방식은 제거함
    private void persistPlaytimeToShortcut() {
        if (sessionPlaytimeSeconds <= 0) return;
        if (shortcut != null) {
            long total = parseLongSafe(shortcut.getExtra("playtimeSec", "0")) + sessionPlaytimeSeconds;
            shortcut.putExtra("playtimeSec", String.valueOf(total));
            // Time windows this game ran in ("start-end;..."; the last 30 kept).
            // Files the shared save folder changed inside them are this game's saves
            // 이 게임이 실행된 시간 구간("시작-끝;..."; 최근 30개 유지). 그 구간에
            // 공용 세이브 폴더에서 바뀐 파일이 이 게임의 세이브임
            if (previousSaveWindows == null) previousSaveWindows = shortcut.getExtra("saveWindows", "");
            String window = launchWallMs + "-" + System.currentTimeMillis();
            String all = previousSaveWindows.isEmpty() ? window : previousSaveWindows + ";" + window;
            String[] parts = all.split(";");
            if (parts.length > 30) all = String.join(";", java.util.Arrays.copyOfRange(parts, parts.length - 30, parts.length));
            shortcut.putExtra("saveWindows", all);
            shortcut.saveData();
        }
        sessionPlaytimeSeconds = 0;
        ticksSinceLastPersist = 0;
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private void incrementPlayCount() {
        if (shortcut == null) return;
        long playCount = parseLongSafe(shortcut.getExtra("playCount", "0")) + 1;
        shortcut.putExtra("playCount", String.valueOf(playCount));
        shortcut.saveData();
    }

    @Override
    protected void onDestroy() {
        com.winlator.cmod.steam.SteamDownloadState.markGameRunning(this, false);
        clearAudioGuard();
        super.onDestroy();
    }

    // ==================== Box64 crash fallback ====================
    // ==================== Box64 크래시 대체 ====================
    // Containers default to the Performance preset. When a game on an X86_64
    // container ends by itself (not through the Exit menu) with an error code,
    // or within 30 seconds of starting, it is treated as a crash and that
    // shortcut steps down one preset for the next launch:
    //   PERFORMANCE -> COMPATIBILITY -> STABILITY
    // Only shortcuts without a hand-picked Box64 preset are touched.
    // 컨테이너 기본값은 성능 프리셋임. X86_64 컨테이너의 게임이 종료 메뉴를 거치지
    // 않고 스스로 끝났는데 오류 코드가 있거나 시작 후 30초 안이면 크래시로 보고,
    // 그 바로가기는 다음 실행부터 프리셋을 한 단계 내림:
    //   PERFORMANCE -> COMPATIBILITY -> STABILITY
    // Box64 프리셋을 직접 고른 바로가기는 건드리지 않음
    private volatile boolean userExitRequested = false;
    private long launchStartedAt = 0;
    public static final String EXTRA_BOX64_AUTO_PRESET = "box64AutoPreset";

    private void recordBox64CrashIfAny(int status) {
        try {
            if (userExitRequested || shortcut == null || container == null) return;
            if (com.winlator.cmod.container.ContainerManager.isArm64EC(container)) return;
            if (!shortcut.getExtra("box64Preset").isEmpty()) return;
            long ranMs = android.os.SystemClock.elapsedRealtime() - launchStartedAt;
            boolean crashed = status != 0 || ranMs < 30_000;
            if (!crashed) return;
            String current = shortcut.getExtra(EXTRA_BOX64_AUTO_PRESET, container.getBox64Preset());
            String next;
            if (com.winlator.cmod.box86_64.Box86_64Preset.PERFORMANCE.equals(current)) {
                next = com.winlator.cmod.box86_64.Box86_64Preset.COMPATIBILITY;
            } else if (com.winlator.cmod.box86_64.Box86_64Preset.COMPATIBILITY.equals(current)) {
                next = com.winlator.cmod.box86_64.Box86_64Preset.STABILITY;
            } else {
                return;
            }
            shortcut.putExtra(EXTRA_BOX64_AUTO_PRESET, next);
            shortcut.saveData();
            Log.w("XServerDisplayActivity", "Box64 crash fallback: " + current + " -> " + next
                    + " (status=" + status + ", ran=" + ranMs + "ms)");
            final String msg = getString(R.string.box64_crash_fallback, next);
            runOnUiThread(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "crash fallback check failed", e);
        }
    }

    // ==================== Auto exit when the game ends ====================
    // ==================== 게임이 끝나면 자동으로 나가기 ====================
    // Logcat 2026-10-03 00:38~00:41: Game.exe ended before showing a window,
    // only Wine's own services (services.exe, winedevice.exe ...) stayed, and the
    // screen stayed black for 2.5 minutes until the user left by hand.
    // Every 2 seconds the game processes (stage 1 in ProcessHelper: Windows
    // programs outside C:\windows, launcher helpers excluded) are counted. Once
    // one has been seen, 3 checks in a row with none (about 6 s) leave the game
    // screen the normal way (exitApp), with no message. The 6 s window lets a
    // launcher hand over to the real game exe. Sessions that never start a
    // game process (e.g. the file manager) are never closed.
    // 로그캣 2026-10-03 00:38~00:41: Game.exe가 창을 띄우기 전에 끝났고 Wine 자체
    // 서비스(services.exe, winedevice.exe 등)만 남아서, 사용자가 직접 나갈 때까지
    // 2분 30초 동안 검은 화면이었음.
    // 2초마다 게임 프로세스(ProcessHelper의 1단계: C:\windows 밖의 Windows 프로그램,
    // 런처 보조 프로그램 제외)를 셈. 한 번이라도 보인 뒤 연속 3번(약 6초) 하나도 없으면
    // 평소 방식(exitApp)으로 게임 화면을 나감. 안내 문구는 띄우지 않음. 6초의 여유는
    // 런처가 실제 게임 exe로 넘겨줄 시간임. 게임 프로세스를 한 번도 띄우지 않는
    // 세션(예: 파일 관리자)은 닫지 않음
    private static final long GAME_WATCH_INTERVAL_MS = 2000;
    private static final int GAME_GONE_CHECKS = 3;
    private volatile boolean gameWatcherRunning = false;

    private void startGameExitWatcher() {
        if (gameWatcherRunning) return;
        gameWatcherRunning = true;
        Thread t = new Thread(() -> {
            boolean seenGame = false;
            int goneCount = 0;
            while (gameWatcherRunning && !userExitRequested && !isFinishing()) {
                try {
                    Thread.sleep(GAME_WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (isPaused) continue;
                int games = ProcessHelper.countGameProcesses();
                if (games > 0) {
                    seenGame = true;
                    goneCount = 0;
                } else if (seenGame && ++goneCount >= GAME_GONE_CHECKS) {
                    Log.i("XServerDisplayActivity", "Game processes ended, leaving the game screen");
                    gameWatcherRunning = false;
                    runOnUiThread(() -> {
                        if (!userExitRequested && !isFinishing()) exitApp();
                    });
                    return;
                }
            }
        }, "GameExitWatcher");
        t.setDaemon(true);
        t.start();
    }

    // ==================== Pause overlay ====================
    // ==================== 일시정지 표시 ====================
    // While paused a large Play button sits in the middle of the screen;
    // tapping it resumes the game (same as the menu entry)
    // 일시정지 중에는 화면 가운데에 큰 재생 버튼이 있고, 누르면 게임이 다시 시작됨
    // (메뉴 항목과 같음)
    private android.widget.ImageView pauseOverlay;

    private void setGamePaused(boolean pause, MenuItem menuItem) {
        if (pause == isPaused) return;
        if (pause) ProcessHelper.pauseAllWineProcesses();
        else ProcessHelper.resumeAllWineProcesses();
        isPaused = pause;
        MenuItem item = menuItem != null ? menuItem : navigationView.getMenu().findItem(R.id.main_menu_pause);
        if (item != null) item.setIcon(pause ? R.drawable.icon_play : R.drawable.icon_pause);
        showPauseOverlay(pause);
    }

    private void showPauseOverlay(boolean show) {
        if (pauseOverlay == null) {
            if (!show) return;
            FrameLayout root = findViewById(R.id.FLXServerDisplay);
            if (root == null) return;
            pauseOverlay = new android.widget.ImageView(this);
            pauseOverlay.setImageResource(R.drawable.icon_play);
            pauseOverlay.setColorFilter(0xFFFFFFFF);
            int pad = (int) (22 * getResources().getDisplayMetrics().density);
            pauseOverlay.setPadding(pad, pad, pad, pad);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(0x99000000);
            pauseOverlay.setBackground(bg);
            int size = (int) (96 * getResources().getDisplayMetrics().density);
            root.addView(pauseOverlay, new FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER));
            pauseOverlay.setOnClickListener(v -> setGamePaused(false, null));
        }
        pauseOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) pauseOverlay.bringToFront();
    }

    public void exitApp() {
        gameWatcherRunning = false;
        // Any exit through the app counts as intentional (no crash fallback)
        // 앱을 통한 종료는 모두 의도된 종료로 봄(크래시 대체 안 함)
        userExitRequested = true;
        clearAudioGuard();

        preloaderDialog.show(R.string.closing_app);


        new Handler(Looper.getMainLooper()).postDelayed(() -> {


            Executors.newSingleThreadExecutor().execute(() -> {

                if (audioManager != null && audioDeviceCallback != null) {
                    audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
                }

                savePlaytimeData();
                handler.removeCallbacks(savePlaytimeRunnable);
                recordQuickExit();

                if (midiHandler != null) midiHandler.stop();
                if (environment != null) environment.stopEnvironmentComponents();
                if (midiHandler != null) midiHandler.stop();
                if (winHandler != null) winHandler.stop();
                if (wineRequestHandler != null) wineRequestHandler.stop();
                // Full staged shutdown on exit: game -> Wine -> wineserver ->
                // box64/FEX -> PRoot -> the rest -> check
                // 종료 시 전체 단계별 정리: 게임 -> Wine -> wineserver ->
                // box64/FEX -> PRoot -> 나머지 -> 확인
                ProcessHelper.terminateAllGuestProcesses("exit");

                // Visual C++ 2015-2022: a newer runtime the user installed during this
                // game is kept for this game, so deleting the container does not lose it
                // Visual C++ 2015~2022: 이 게임 중에 사용자가 설치한 더 새 런타임을 이 게임용으로
                // 보관함. 그래서 컨테이너를 지워도 잃지 않음
                if (vcrunOn && vcrunGameKey != null && imageFs != null) {
                    try {
                        File vcWindowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");
                        VCRedistInstaller.collectNewer(this, imageFs, vcWindowsDir, vcrunGameKey, vcrunGameLabel);
                        container.putExtra(VCRedistInstaller.LAST_GAME_EXTRA, null);
                        container.putExtra(VCRedistInstaller.LAST_GAME_NAME_EXTRA, null);
                        container.saveData();
                    } catch (Exception e) {
                        Log.w("XServerDisplayActivity", "vcrun2015 collect on exit failed", e);
                    }
                }

                // Finish the activity on the main UI thread
                runOnUiThread(() -> {
                    preloaderDialog.close();
                    AppUtils.restartApplication(getApplicationContext());
                });
            });
        }, 1000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);


    }


    private void releasePointerCaptureIfNeeded(String reason) {
        if (pointerCaptureRequested && touchpadView != null) {
            touchpadView.releasePointerCapture();
            touchpadView.setOnCapturedPointerListener(null);
            pointerCaptureRequested = false;
            Log.d("PointerCapture", "Released: " + reason);
        }
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            releasePointerCaptureIfNeeded("open-drawer/back");
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            } else {
                drawerLayout.closeDrawers();
            }
        }
    }

    private void openXServerDrawer() {
        if (environment != null) {
            releasePointerCaptureIfNeeded("open-drawer/shortcut");
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            } else {
                drawerLayout.closeDrawers();
            }
        }
    }


    // Fields
    private static final String PREF_EXP_INPUT = "drawer_exp_input";
    private static final String PREF_EXP_DISPLAY = "drawer_exp_display";
    private static final String PREF_EXP_SYSTEM = "drawer_exp_system";

    private boolean expInput = false;
    private boolean expDisplay = false;
    private boolean expSystem = false;

    private NavigationView navigationView;


    private boolean enableLogs = false;
    private boolean allowMagnifier = true;

    private static final int ANIM_DURATION = 300; // ms
    private static final float SLIDE_DP = 0f;     // small vertical shift

    private static final float COLLAPSE_TRANSLATION_DP = 6f;

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Nullable
    private RecyclerView navRecycler() {
        return findNavRecycler(navigationView);
    }

    private Set<CharSequence> titlesForIds(Menu menu, int[] itemIds) {
        HashSet<CharSequence> set = new HashSet<>();
        for (int id : itemIds) {
            MenuItem mi = menu.findItem(id);
            if (mi != null && mi.getTitle() != null) set.add(mi.getTitle());
        }
        return set;
    }

    @Nullable
    private TextView rowTitle(View row) {
        // Try Material id, then fallback to first TextView
        int textId = getResources().getIdentifier("design_menu_item_text", "id", getPackageName());
        View v = textId != 0 ? row.findViewById(textId) : null;
        if (v instanceof TextView) return (TextView) v;

        if (row instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) row;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof TextView) return (TextView) c;
            }
        }
        return null;
    }

    private List<View> findVisibleRowsForTitles(Set<CharSequence> wantedTitles) {
        ArrayList<View> rows = new ArrayList<>();
        RecyclerView rv = navRecycler();
        if (rv == null) return rows;

        for (int i = 0; i < rv.getChildCount(); i++) {
            View row = rv.getChildAt(i);
            TextView tv = rowTitle(row);
            if (tv != null && tv.getText() != null && wantedTitles.contains(tv.getText())) {
                rows.add(row);
            }
        }
        return rows;
    }

    private void animateInGroupItems(int[] itemIds) {
        RecyclerView rv = navRecycler();
        if (rv == null) return;

        // Wait one frame so the rows are laid out after setGroupVisible(true)
        rv.post(() -> {
            Menu menu = navigationView.getMenu();
            Set<CharSequence> titles = titlesForIds(menu, itemIds);
            List<View> rows = findVisibleRowsForTitles(titles);

            float startTrans = dp(SLIDE_DP);
            for (View row : rows) {
                row.setAlpha(0f);
                row.setTranslationY(startTrans);
                row.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(ANIM_DURATION)
                        .withLayer()
                        .start();
            }
        });
    }

    private static final int[] INPUT_IDS = {
            R.id.main_menu_input_controls,
            R.id.main_menu_controller_assignment,
            R.id.main_menu_motion_controls
    };

    private static final int[] DISPLAY_IDS = {
            R.id.main_menu_screen_effects,
            R.id.main_menu_toggle_fullscreen,
            R.id.main_menu_magnifier,
            R.id.main_menu_pip_mode
    };

    private static final int[] SYSTEM_IDS = {
            R.id.main_menu_task_manager,
            R.id.main_menu_terminal
    };


    @Nullable
    private RecyclerView findNavRecycler(View root) {
        if (root instanceof RecyclerView) return (RecyclerView) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                RecyclerView rv = findNavRecycler(vg.getChildAt(i));
                if (rv != null) return rv;
            }
        }
        return null;
    }

    private void applyGroup(Menu menu, int groupId, int headerId, boolean expanded) {
        menu.setGroupVisible(groupId, expanded);

        MenuItem header = menu.findItem(headerId);
        if (header != null) {
            header.setCheckable(true);
            header.setChecked(expanded);   // visual cue
        }

        RecyclerView rv = findNavRecycler(navigationView);
        if (rv != null) {
            RecyclerView.Adapter<?> ad = rv.getAdapter();
            if (ad != null) ad.notifyDataSetChanged();
            rv.requestLayout();
            rv.invalidateItemDecorations();
            rv.postInvalidateOnAnimation();
        } else {
            navigationView.invalidate();
            navigationView.postInvalidateOnAnimation();
        }
    }


    // Title = the mode currently in use; tapping switches to the other one
    // 제목 = 지금 쓰는 모드임. 누르면 다른 모드로 바뀜
    private void updateTouchModeMenuItem() {
        if (navigationView == null || touchpadView == null) return;
        MenuItem item = navigationView.getMenu().findItem(R.id.main_menu_relative_mouse);
        if (item == null) return;
        item.setCheckable(false);
        item.setTitle(touchpadView.isSimTouchScreen() ? R.string.touch_mode_touchscreen : R.string.touch_mode_touchpad);
    }

    // Folds every sub-section without the slide animation (the drawer is
    // already closed, nothing would be seen)
    // 모든 하위 섹션을 미끄러지는 애니메이션 없이 접음(드로어가 이미 닫혀
    // 있어서 보일 게 없음)
    private void collapseAllSectionsSilently() {
        if (navigationView == null) return;
        Menu menu = navigationView.getMenu();
        expInput = false;
        expDisplay = false;
        expSystem = false;
        applyGroup(menu, R.id.group_input, R.id.header_input, false);
        applyGroup(menu, R.id.group_display, R.id.header_display, false);
        applyGroup(menu, R.id.group_system, R.id.header_system, false);
    }

    private void persistSection(String key, boolean value) {
        preferences.edit().putBoolean(key, value).apply();
    }

    private void expandGroup(Menu menu, int groupId, int headerId, int[] itemIds) {
        // Show items first, then animate them in.
        applyGroup(menu, groupId, headerId, true);
        animateInGroupItems(itemIds);
    }

    private void collapseGroup(Menu menu, int groupId, int headerId, int[] itemIds) {
        RecyclerView rv = navRecycler();
        if (rv == null) {
            applyGroup(menu, groupId, headerId, false);
            return;
        }

        // Find the currently visible rows for this group
        Set<CharSequence> titles = titlesForIds(menu, itemIds);
        List<View> rows = findVisibleRowsForTitles(titles);
        if (rows.isEmpty()) {
            applyGroup(menu, groupId, headerId, false);
            return;
        }

        rv.suppressLayout(true);

        final int[] remaining = {rows.size()};
        float endTrans = dp(COLLAPSE_TRANSLATION_DP);

        for (View row : rows) {
            final View r = row;
            final int startH = r.getHeight();
            if (startH <= 0) {
                if (--remaining[0] == 0) finishCollapse(menu, groupId, headerId, rv);
                continue;
            }

            // Height animator
            ValueAnimator hAnim = ValueAnimator.ofInt(startH, 0);
            hAnim.addUpdateListener(a -> {
                int h = (int) a.getAnimatedValue();
                RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) r.getLayoutParams();
                lp.height = h;
                r.setLayoutParams(lp);
            });

            // Alpha + slight slide
            ObjectAnimator aAnim = ObjectAnimator.ofFloat(r, View.ALPHA, 1f, 0f);
            ObjectAnimator tAnim = ObjectAnimator.ofFloat(r, View.TRANSLATION_Y, 0f, endTrans);

            AnimatorSet set = new AnimatorSet();
            set.setDuration(ANIM_DURATION);
            set.setInterpolator(new AccelerateDecelerateInterpolator());
            set.playTogether(hAnim, aAnim, tAnim);
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // Restore params so RV can recycle properly next time
                    RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) r.getLayoutParams();
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    r.setLayoutParams(lp);
                    r.setAlpha(1f);
                    r.setTranslationY(0f);

                    if (--remaining[0] == 0) {
                        finishCollapse(menu, groupId, headerId, rv);
                    }
                }
            });
            set.start();
        }
    }

    private void finishCollapse(Menu menu, int groupId, int headerId, RecyclerView rv) {
        applyGroup(menu, groupId, headerId, false); // hides group + notifies adapter
        rv.suppressLayout(false);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        final int id = item.getItemId();
        final Menu menu = navigationView.getMenu();

        switch (id) {
            // ---- Section headers (toggle, do NOT close drawer) ----
            // The "Control settings" entry opens the controls dialog
            // directly (its only visible child was that dialog)
            // "컨트롤 설정" 항목은 컨트롤 창을 바로 엶(보이는 하위 항목이 그
            // 창 하나뿐이었음)
            case R.id.header_input:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                return true;
            case R.id.header_display: {
                boolean wasExpanded = expDisplay;
                expDisplay = !expDisplay;
                persistSection(PREF_EXP_DISPLAY, expDisplay);
                if (wasExpanded) {
                    collapseGroup(menu, R.id.group_display, R.id.header_display, DISPLAY_IDS);
                } else {
                    expandGroup(menu, R.id.group_display, R.id.header_display, DISPLAY_IDS);
                }
                return true;
            }

            case R.id.header_system: {
                boolean wasExpanded = expSystem;
                expSystem = !expSystem;
                persistSection(PREF_EXP_SYSTEM, expSystem);
                if (wasExpanded) {
                    collapseGroup(menu, R.id.group_system, R.id.header_system, SYSTEM_IDS);
                } else {
                    expandGroup(menu, R.id.group_system, R.id.header_system, SYSTEM_IDS);
                }
                return true;
            }


            // ---- Top-level quick actions ----
            // Was "Relative mouse movement": that switch only affects a captured
            // physical mouse, so with touch input it looked like it did nothing.
            // The entry now flips the touch input mode instead:
            //   touchpad mode    - finger moves the cursor like a laptop touchpad
            //   touchscreen mode - a tap clicks exactly where the finger is
            // Saved per shortcut in the same "simTouchScreen" extra the input
            // controls dialog and the shortcut settings use
            // 예전 "상대 마우스 이동": 그 스위치는 포인터 캡처된 실제 마우스에만
            // 작용해서, 터치 입력에서는 아무 일도 안 하는 것처럼 보였음. 이제 이 항목은
            // 터치 입력 방식을 바꿈:
            //   터치패드 모드   - 손가락이 노트북 터치패드처럼 커서를 움직임
            //   터치스크린 모드 - 손가락이 닿은 자리를 바로 클릭함
            // 입력 컨트롤 창과 바로가기 설정이 쓰는 "simTouchScreen" extra에
            // 바로가기별로 저장함
            case R.id.main_menu_relative_mouse: {
                boolean touchscreen = !touchpadView.isSimTouchScreen();
                touchpadView.setSimTouchScreen(touchscreen);
                if (shortcut != null) {
                    shortcut.putExtra("simTouchScreen", touchscreen ? "1" : "0");
                    shortcut.saveData();
                }
                updateTouchModeMenuItem();
                Toast.makeText(this, touchscreen ? R.string.touch_mode_switched_touchscreen
                        : R.string.touch_mode_switched_touchpad, Toast.LENGTH_SHORT).show();
                drawerLayout.closeDrawers();
                return true;
            }

            case R.id.main_menu_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_pause:
                setGamePaused(!isPaused, item);
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_touchpad_help:
                showTouchpadHelpDialog();
                drawerLayout.closeDrawers();
                return true;

            // ---- INPUT group ----
            case R.id.main_menu_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_controller_assignment:
                ControllerAssignmentDialog.show(this, winHandler);
                winHandler.clearIgnoredDevices();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_motion_controls:
                MotionControls.getInstance(this)
                        .attach(winHandler)
                        .showContentDialog(this, null);
                drawerLayout.closeDrawers();
                return true;

            // ---- DISPLAY group ----
            case R.id.main_menu_toggle_fullscreen:
                renderer.toggleFullscreen();
                // Keep the HUD above everything after the picture is restretched
                // 화면을 다시 늘린 뒤에도 HUD가 맨 위에 오게 함
                if (frameRating != null) frameRating.bringToFront();
                touchpadView.toggleFullscreen();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_pip_mode:
                enterPictureInPictureMode();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_magnifier:
                if (magnifierView == null) {
                    FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback(value -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_screen_effects:
                ScreenEffectDialog dlg = new ScreenEffectDialog(this);
                dlg.setOnConfirmCallback(() -> {
                    GLRenderer r = xServerView.getRenderer();
                    ColorEffect color = r.getEffectComposer().getEffect(ColorEffect.class);
                    FXAAEffect fxaa = r.getEffectComposer().getEffect(FXAAEffect.class);
                    CRTEffect crt = r.getEffectComposer().getEffect(CRTEffect.class);
                    ToonEffect toon = r.getEffectComposer().getEffect(ToonEffect.class);
                    NTSCCombinedEffect ntsc = r.getEffectComposer().getEffect(NTSCCombinedEffect.class);
                    dlg.applyEffects(color, r, fxaa, crt, toon, ntsc);
                });
                dlg.show();
                drawerLayout.closeDrawers();
                return true;

            // ---- SYSTEM group ----
            case R.id.main_menu_task_manager:
                new TaskManagerDialog(this).show();
                drawerLayout.closeDrawers();
                return true;


            case R.id.main_menu_terminal:
                openTerminal();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_exit:
                userExitRequested = true;
                exitApp();
                return true;
        }

        return true;
    }


    private void openTerminal() {
        Intent intent = new Intent(this, TerminalActivity.class);
        startActivity(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            if (pointerCaptureRequested && touchpadView != null) {
                touchpadView.releasePointerCapture();
                pointerCaptureRequested = false;
                Log.d("PointerCapture", "Released due to focus loss.");
            }
        } else {
            if (isRelativeMouseMovement && !pointerCaptureRequested) {
                ensurePointerCapture("focus-gained");
            }
        }
    }

    /**
     * Korean/Japanese/Chinese text showed as empty boxes in Wine windows (e.g.
     * Steam's "steamwebhelper is not responding" dialog): containers default to
     * the device locale (ko_KR) but the Wine prefix ships no CJK font.
     * Android's own Noto Sans CJK is linked into windows\Fonts and registered as
     * a FontLink fallback for the common UI fonts, so those glyphs resolve.
     * Runs every launch; each step is skipped when already in place.
     * Wine 창에서 한/중/일 글자가 빈 네모로 나왔음(예: Steam의 "steamwebhelper가
     * 응답하지 않습니다" 창): 컨테이너 기본 로캘은 기기 로캘(ko_KR)인데 Wine
     * 환경에는 CJK 글꼴이 없음. 안드로이드 기본 Noto Sans CJK를 windows\Fonts에
     * 링크하고, 주요 UI 글꼴의 FontLink 대체 글꼴로 등록해서 글자가 나오게 함.
     * 실행할 때마다 돌지만, 이미 돼 있는 단계는 건너뜀
     */
    // Korean Windows font names, English and Korean pairs:
    // Malgun Gothic, Gulim, GulimChe, Dotum, DotumChe, Batang, BatangChe, Gungsuh, GungsuhChe
    // 한국어 Windows 글꼴 이름(영문·한글 쌍):
    // 맑은 고딕, 굴림, 굴림체, 돋움, 돋움체, 바탕, 바탕체, 궁서, 궁서체
    private static final String[] KOREAN_FONT_NAMES = {
            "Malgun Gothic", "\uB9D1\uC740 \uACE0\uB515",
            "Gulim", "\uAD74\uB9BC",
            "GulimChe", "\uAD74\uB9BC\uCCB4",
            "Dotum", "\uB3CB\uC6C0",
            "DotumChe", "\uB3CB\uC6C0\uCCB4",
            "Batang", "\uBC14\uD0D5",
            "BatangChe", "\uBC14\uD0D5\uCCB4",
            "Gungsuh", "\uAD81\uC11C",
            "GungsuhChe", "\uAD81\uC11C\uCCB4"
    };

    // Index of the Windows font file that carries a Korean font name:
    // 0 malgun.ttf, 2 gulim.ttc, 3 batang.ttc; -1 for other names
    // 한국어 글꼴 이름이 들어 있는 Windows 글꼴 파일 번호:
    // 0 malgun.ttf, 2 gulim.ttc, 3 batang.ttc. 다른 이름이면 -1
    private static int koreanFontFile(String name) {
        for (int i = 0; i < KOREAN_FONT_NAMES.length; i++) {
            if (KOREAN_FONT_NAMES[i].equals(name)) return i < 2 ? 0 : i < 10 ? 2 : 3;
        }
        return -1;
    }

    // Faces for KOREAN_FONT_NAMES[from..to) (English/Korean pairs) with one style
    // KOREAN_FONT_NAMES[from..to)(영문·한글 쌍)의 글꼴들을 한 스타일로 만듦
    private static KoreanFontBuilder.Face[] koreanFaces(int from, int to, String subfamily) {
        KoreanFontBuilder.Face[] faces = new KoreanFontBuilder.Face[(to - from) / 2];
        for (int i = from; i < to; i += 2) {
            faces[(i - from) / 2] = new KoreanFontBuilder.Face(KOREAN_FONT_NAMES[i], KOREAN_FONT_NAMES[i + 1],
                    subfamily, KOREAN_FONT_NAMES[i].replace(" ", ""));
        }
        return faces;
    }

    // Source fonts for the generated Korean Windows fonts, all SIL OFL 1.1, in
    // the Google Fonts repository: Gothic A1 (Malgun Gothic), Gowun Dodum
    // (Gulim, Dotum), Gowun Batang (Batang, Gungsuh); Noto Sans KR / Noto Serif
    // KR (variable fonts) supply the Hanja, kana and CJK symbols the first three lack
    // 만들어 쓰는 한국어 Windows 글꼴의 원본 글꼴. 모두 SIL OFL 1.1이고 Google Fonts
    // 저장소에 있음: Gothic A1(맑은 고딕), 고운돋움(굴림, 돋움), 고운바탕(바탕, 궁서).
    // 앞의 세 글꼴에 없는 한자·가나·CJK 기호는 Noto Sans KR / Noto Serif KR(가변
    // 글꼴)에서 채움
    private static final String GOOGLE_FONTS = "https://raw.githubusercontent.com/google/fonts/main/ofl/";
    private static final String[][] KOREAN_SOURCE_FONTS = {
            {"GothicA1-Regular.ttf", "gothica1/GothicA1-Regular.ttf"},
            {"GothicA1-Bold.ttf", "gothica1/GothicA1-Bold.ttf"},
            {"GowunDodum-Regular.ttf", "gowundodum/GowunDodum-Regular.ttf"},
            {"GowunBatang-Regular.ttf", "gowunbatang/GowunBatang-Regular.ttf"},
            {"NotoSansKR-VF.ttf", "notosanskr/NotoSansKR%5Bwght%5D.ttf"},
            {"NotoSerifKR-VF.ttf", "notoserifkr/NotoSerifKR%5Bwght%5D.ttf"},
    };
    // Folder of the generated fonts; a new folder name makes every device build them again
    // 만든 글꼴을 두는 폴더. 폴더 이름을 바꾸면 모든 기기에서 다시 만듦
    private static final String KOREAN_FONT_DIR = "fonts/korean-v1";

    // A source font kept in the app's files/fonts: taken from the APK
    // (assets/fonts/<name>) when bundled, otherwise downloaded once from the
    // Google Fonts repository. null when neither works (offline)
    // 앱의 files/fonts에 보관하는 원본 글꼴. APK에 들어 있으면(assets/fonts/<이름>)
    // 그것을, 없으면 Google Fonts 저장소에서 한 번 받음. 둘 다 안 되면(오프라인) null
    private File cachedFont(int index) {
        String name = KOREAN_SOURCE_FONTS[index][0];
        try {
            File dir = new File(getFilesDir(), "fonts");
            if (!dir.isDirectory()) dir.mkdirs();
            File cached = new File(dir, name);
            if (!(cached.isFile() && cached.length() > 100_000)) {
                try (java.io.InputStream in = getAssets().open("fonts/" + name);
                     java.io.OutputStream out = new java.io.FileOutputStream(cached)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (java.io.IOException notBundled) {
                    cached.delete();
                }
            }
            if (!(cached.isFile() && cached.length() > 100_000)) {
                File part = new File(dir, name + ".part");
                Log.i("XServerDisplayActivity", "Downloading font source: " + name);
                if (!(com.winlator.cmod.contents.Downloader.downloadFile(GOOGLE_FONTS + KOREAN_SOURCE_FONTS[index][1], part)
                        && part.length() > 100_000 && part.renameTo(cached))) {
                    part.delete();
                    Log.w("XServerDisplayActivity", "Font source not available: " + name);
                    return null;
                }
            }
            return cached;
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "Font source failed: " + name, e);
            return null;
        }
    }

    // A Korean Windows font file (malgun.ttf, gulim.ttc ...) generated once per
    // device by KoreanFontBuilder: base font + Hanja from the supplement, with
    // the Windows font names written inside. Built again later when the
    // supplement was missing the first time (".nohanja" marker). null when the
    // base font is not available either
    // KoreanFontBuilder로 기기마다 한 번 만드는 한국어 Windows 글꼴 파일(malgun.ttf,
    // gulim.ttc 등): 기본 글꼴 + 보충 글꼴의 한자, 파일 안에 Windows 글꼴 이름을
    // 기록함. 처음에 보충 글꼴이 없었으면(".nohanja" 표시) 나중에 다시 만듦. 기본
    // 글꼴도 없으면 null
    private File generatedKoreanFont(String fileName, int baseIndex, int supplementIndex, int weight,
                                     KoreanFontBuilder.Face[] faces) {
        File dir = new File(getFilesDir(), KOREAN_FONT_DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        File out = new File(dir, fileName);
        File noHanja = new File(dir, fileName + ".nohanja");
        if (out.isFile() && !noHanja.exists()) return out;
        File base = cachedFont(baseIndex);
        if (base == null) return out.isFile() ? out : null;
        File supplement = cachedFont(supplementIndex);
        if (out.isFile() && supplement == null) return out;
        try {
            long t = System.currentTimeMillis();
            KoreanFontBuilder.build(base, supplement, weight, faces, out);
            try {
                if (supplement == null) noHanja.createNewFile(); else noHanja.delete();
            } catch (java.io.IOException ignored) {}
            Log.i("XServerDisplayActivity", "Korean font generated: " + fileName + " (" + out.length()
                    + " bytes, " + (System.currentTimeMillis() - t) + " ms, hanja=" + (supplement != null) + ")");
            return out;
        } catch (Exception e) {
            // Unreadable source (broken download): removed so it is downloaded again next time
            // 읽을 수 없는 원본(받다가 깨짐): 다음에 다시 받도록 지움
            Log.w("XServerDisplayActivity", "Korean font generation failed: " + fileName, e);
            base.delete();
            if (supplement != null) supplement.delete();
            return out.isFile() ? out : null;
        } catch (OutOfMemoryError e) {
            Log.w("XServerDisplayActivity", "Korean font generation out of memory: " + fileName);
            return out.isFile() ? out : null;
        }
    }

    // Fonts the user puts in Download/.EZlator/Fonts. Every .ttf / .ttc / .otf there
    // is copied into C:\windows\Fonts of the running container (Wine loads the fonts
    // of that folder; real Windows fonts copied there by hand showed Korean text).
    // A file named like a Windows Korean font (malgun.ttf, malgunbd.ttf, gulim.ttc,
    // batang.ttc) is used instead of the generated one for that name. The names
    // copied are listed in USER_FONT_LIST, so a font removed from the folder is
    // removed from the container on the next launch as well
    // 사용자가 Download/.EZlator/Fonts에 넣은 글꼴. 그 안의 .ttf / .ttc / .otf를 모두
    // 실행하는 컨테이너의 C:\windows\Fonts로 복사함(Wine은 그 폴더의 글꼴을 읽음.
    // 진짜 Windows 글꼴을 직접 넣었을 때 한글이 나왔음). Windows 한글 글꼴 이름
    // (malgun.ttf, malgunbd.ttf, gulim.ttc, batang.ttc)인 파일은 그 이름의 생성 글꼴
    // 대신 씀. 복사한 이름은 USER_FONT_LIST에 적어 두므로, 폴더에서 지운 글꼴은
    // 다음 실행 때 컨테이너에서도 지워짐
    private static final String USER_FONT_DIR = "Fonts";
    private static final String USER_FONT_LIST = ".ezlator-user-fonts";
    private static final String[] WINDOWS_KOREAN_FONT_FILES = {"malgun.ttf", "malgunbd.ttf", "gulim.ttc", "batang.ttc"};

    // Download/.EZlator/Fonts, made when missing so the user can find it
    // Download/.EZlator/Fonts. 사용자가 찾을 수 있게 없으면 만듦
    private static File userFontsDir() {
        File dir = com.winlator.cmod.core.AppDirs.downloads(USER_FONT_DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    // Font files in the user folder (.ttf / .ttc / .otf, any letter case)
    // 사용자 폴더의 글꼴 파일(.ttf / .ttc / .otf, 대소문자 무관)
    private static File[] userFontFiles(File dir) {
        File[] files = dir.listFiles(f -> {
            String n = f.getName().toLowerCase(java.util.Locale.ENGLISH);
            return f.isFile() && (n.endsWith(".ttf") || n.endsWith(".ttc") || n.endsWith(".otf"));
        });
        return files != null ? files : new File[0];
    }

    // The user font with this file name (letter case ignored), or null
    // 이 파일 이름의 사용자 글꼴(대소문자 무시). 없으면 null
    private static File findUserFont(File[] files, String name) {
        for (File f : files) if (f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    // Names placed from the user folder on an earlier launch (USER_FONT_LIST)
    // 이전 실행에서 사용자 폴더로부터 넣은 이름 목록(USER_FONT_LIST)
    private static Set<String> readUserFontList(File listFile) {
        Set<String> names = new HashSet<>();
        if (!listFile.isFile()) return names;
        for (String line : FileUtils.readLines(listFile)) {
            String n = line.trim();
            if (!n.isEmpty()) names.add(n);
        }
        return names;
    }

    // Copies the user fonts other than the four Windows Korean names (those are
    // handled with the generated fonts) and removes earlier copies whose source is
    // gone. A file is copied again when its size differs. Returns the names now
    // in place, added to the given list
    // 네 개의 Windows 한글 글꼴 이름(생성 글꼴과 함께 처리함)을 뺀 사용자 글꼴을
    // 복사하고, 원본이 사라진 예전 복사본은 지움. 크기가 다르면 다시 복사함.
    // 지금 들어가 있는 이름을 주어진 목록에 더해 돌려줌
    private static Set<String> syncUserFonts(File[] userFiles, File fontsDir, Set<String> placedBefore) {
        Set<String> placed = new HashSet<>();
        Set<String> windowsNames = new HashSet<>(java.util.Arrays.asList(WINDOWS_KOREAN_FONT_FILES));
        for (File src : userFiles) {
            String name = src.getName();
            if (windowsNames.contains(name.toLowerCase(java.util.Locale.ENGLISH))) continue;
            File dst = new File(fontsDir, name);
            if (FileUtils.isSymlink(dst)) dst.delete();
            if (!dst.isFile() || dst.length() != src.length()) {
                FileUtils.copy(src, dst);
                Log.i("XServerDisplayActivity", "User font copied: " + name + " (" + dst.length() + " bytes)");
            }
            if (dst.isFile()) placed.add(name);
        }
        for (String name : placedBefore) {
            if (windowsNames.contains(name.toLowerCase(java.util.Locale.ENGLISH)) || placed.contains(name)) continue;
            if (new File(fontsDir, name).delete()) Log.i("XServerDisplayActivity", "User font removed: " + name);
        }
        return placed;
    }

    // Target of a symbolic link as written in the link, or null
    // 심볼릭 링크에 적힌 대상 경로. 링크가 아니거나 못 읽으면 null
    private static String symlinkTarget(File f) {
        try {
            return android.system.Os.readlink(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    private void applyCjkFontLink() {
        try {
            String[][] candidates = {
                    {"/system/fonts/NotoSansCJK-Regular.ttc", null},
                    {"/system/fonts/DroidSansFallback.ttf", "Droid Sans Fallback"},
            };
            File src = null; String face = null;
            for (String[] c : candidates) {
                if (new File(c[0]).isFile()) { src = new File(c[0]); face = c[1]; break; }
            }
            // Newer Android releases ship the CJK font under other names (for example
            // a variable "NotoSansCJK-VF.otf.ttc"), so any NotoSansCJK file in
            // /system/fonts is accepted; the family names inside are the same
            // ("Noto Sans CJK KR" ...)
            // 최근 안드로이드는 CJK 글꼴을 다른 이름으로 넣기도 함(예: 가변 글꼴
            // "NotoSansCJK-VF.otf.ttc"). 그래서 /system/fonts의 NotoSansCJK 파일이면 모두
            // 받아들임. 안의 글꼴 이름은 같음("Noto Sans CJK KR" 등)
            if (src == null) {
                File[] fonts = new File("/system/fonts").listFiles((d, n) -> n.startsWith("NotoSansCJK")
                        && (n.endsWith(".ttc") || n.endsWith(".otf") || n.endsWith(".ttf")));
                if (fonts != null && fonts.length > 0) {
                    java.util.Arrays.sort(fonts, (x, y) -> Boolean.compare(!x.getName().contains("Regular"), !y.getName().contains("Regular")));
                    src = fonts[0];
                }
            }
            Log.i("XServerDisplayActivity", "CJK font source: " + (src != null ? src.getPath() : "none"));
            if (src == null) { Log.w("XServerDisplayActivity", "CJK font: no system CJK font found"); return; }

            if (face == null) {
                // Pick the Noto CJK face that matches the container language
                // 컨테이너 언어에 맞는 Noto CJK 글꼴 이름을 고름
                String lang = lc_all != null ? lc_all.toLowerCase(java.util.Locale.ENGLISH) : "";
                face = lang.startsWith("ja") ? "Noto Sans CJK JP"
                        : lang.startsWith("zh_tw") || lang.startsWith("zh_hk") ? "Noto Sans CJK TC"
                        : lang.startsWith("zh") ? "Noto Sans CJK SC" : "Noto Sans CJK KR";
            }

            File fontsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows/Fonts");
            if (!fontsDir.isDirectory()) fontsDir.mkdirs();
            // A real copy of the system font in C:\windows\Fonts, not a link into
            // /system/fonts: the link was not picked up inside the container (Korean
            // text stayed missing). Copied once; an old link from earlier builds is
            // replaced
            // 시스템 글꼴을 /system/fonts로 링크하지 않고 C:\windows\Fonts에 실제로
            // 복사함: 링크는 컨테이너 안에서 인식되지 않았음(한글이 계속 안 나왔음).
            // 한 번만 복사하고, 예전 빌드가 만든 링크는 복사본으로 바꿈
            File link = new File(fontsDir, src.getName());
            if (FileUtils.isSymlink(link)) link.delete();
            if (!link.isFile() || link.length() != src.length()) FileUtils.copy(src, link);
            Log.i("XServerDisplayActivity", "CJK font copy: " + link.getPath() + " (" + link.length() + " bytes)");

            // Windows Korean font FILES. Some games open the file itself (e.g.
            // C:\Windows\Fonts\malgun.ttf), and Unity games pick a font by the family
            // name stored inside the file, so a font under another name (Noto Sans CJK,
            // NanumGothic) was never used - Korean text stayed missing in a Korean-
            // patched Unity game, while the real Windows files copied in worked.
            // Each file is therefore a substitute font generated on the device with
            // the Windows names inside (KoreanFontBuilder): Gothic A1 for Malgun
            // Gothic, Gowun Dodum for Gulim/Dotum, Gowun Batang for Batang/Gungsuh,
            // Hanja from Noto Sans KR / Noto Serif KR. The CJK copy is used until the
            // sources can be downloaded. A real Windows font copied in by the user
            // (Microsoft copyright inside) is never replaced.
            // Windows 한글 글꼴 "파일". 일부 게임은 파일 자체를 열고(예:
            // C:\Windows\Fonts\malgun.ttf), Unity 게임은 파일 안에 적힌 글꼴 이름으로
            // 고르므로, 이름이 다른 글꼴(Noto Sans CJK, 나눔고딕)은 쓰이지 않았음 - 한글
            // 패치한 Unity 게임에서 한글이 계속 안 나왔고, 진짜 Windows 파일을 넣으면 나왔음.
            // 그래서 각 파일은 기기에서 Windows 이름을 넣어 만든 대체 글꼴로 채움
            // (KoreanFontBuilder): 맑은 고딕은 Gothic A1, 굴림/돋움은 고운돋움, 바탕/궁서는
            // 고운바탕, 한자는 Noto Sans KR / Noto Serif KR. 원본을 받기 전까지는 CJK
            // 복사본을 씀. 사용자가 넣은 진짜 Windows 글꼴(안에 Microsoft 저작권)은 절대
            // 바꾸지 않음
            // Hanja weight: 400/700 next to Gothic A1 Regular/Bold, 300 next to the
            // lighter Gowun fonts (closest stroke weight in a render comparison)
            // 한자 굵기: Gothic A1 Regular/Bold 옆에는 400/700, 더 가는 고운 글꼴 옆에는
            // 300(렌더링 비교에서 획 굵기가 가장 비슷했음)
            // User fonts (Download/.EZlator/Fonts): a Windows Korean name found there
            // replaces the generated font of that name, which is then not built
            // 사용자 글꼴(Download/.EZlator/Fonts): 거기에 Windows 한글 글꼴 이름이 있으면
            // 그 이름의 생성 글꼴 대신 쓰고, 그 생성 글꼴은 만들지 않음
            File[] userFiles = userFontFiles(userFontsDir());
            File[] userWin = new File[WINDOWS_KOREAN_FONT_FILES.length];
            for (int i = 0; i < userWin.length; i++) userWin[i] = findUserFont(userFiles, WINDOWS_KOREAN_FONT_FILES[i]);
            File userListFile = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/" + USER_FONT_LIST);
            Set<String> placedBefore = readUserFontList(userListFile);
            Set<String> placedNow = syncUserFonts(userFiles, fontsDir, placedBefore);

            File malgun = userWin[0] == null ? generatedKoreanFont("malgun.ttf", 0, 4, 400, koreanFaces(0, 2, "Regular")) : null;
            File malgunBold = userWin[1] == null ? generatedKoreanFont("malgunbd.ttf", 1, 4, 700, koreanFaces(0, 2, "Bold")) : null;
            File gulim = userWin[2] == null ? generatedKoreanFont("gulim.ttc", 2, 4, 300, koreanFaces(2, 10, "Regular")) : null;
            File batang = userWin[3] == null ? generatedKoreanFont("batang.ttc", 3, 5, 300, koreanFaces(10, 18, "Regular")) : null;
            File[] winSources = {
                    userWin[0] != null ? userWin[0] : malgun != null ? malgun : link,
                    userWin[1] != null ? userWin[1] : malgunBold != null ? malgunBold : (userWin[0] != null ? userWin[0] : malgun != null ? malgun : link),
                    userWin[2] != null ? userWin[2] : gulim != null ? gulim : link,
                    userWin[3] != null ? userWin[3] : batang != null ? batang : link
            };
            // Once every file is built with Hanja the downloaded sources are no
            // longer needed; the NanumGothic/NanumMyeongjo files placed by the
            // previous build are removed
            // 모든 파일을 한자까지 넣어 만들면 받은 원본 글꼴은 더 필요 없으므로 지움.
            // 이전 빌드가 둔 나눔고딕/나눔명조 파일도 지움
            File genDir = new File(getFilesDir(), KOREAN_FONT_DIR);
            // A name covered by a user font counts as done
            // 사용자 글꼴로 채운 이름은 끝난 것으로 봄
            File[] generated = {malgun, malgunBold, gulim, batang};
            boolean complete = true;
            for (int i = 0; i < generated.length; i++) {
                if (userWin[i] != null) continue;
                if (generated[i] == null || new File(genDir, WINDOWS_KOREAN_FONT_FILES[i] + ".nohanja").exists()) complete = false;
            }
            if (complete) {
                for (String[] sf : KOREAN_SOURCE_FONTS) new File(getFilesDir(), "fonts/" + sf[0]).delete();
            }
            for (String n : new String[]{"NanumGothic-Regular.ttf", "NanumGothic-Bold.ttf", "NanumMyeongjo-Regular.ttf"}) {
                new File(getFilesDir(), "fonts/" + n).delete();
                File old = new File(fontsDir, n);
                if (old.isFile() && !FileUtils.isSymlink(old)) old.delete();
            }
            String[][] winFonts = {
                    {"malgun.ttf", "Malgun Gothic (TrueType)"},
                    {"malgunbd.ttf", "Malgun Gothic Bold (TrueType)"},
                    {"gulim.ttc", "Gulim & GulimChe & Dotum & DotumChe (TrueType)"},
                    {"batang.ttc", "Batang & BatangChe & Gungsuh & GungsuhChe (TrueType)"}
            };
            // Hard link to the source file, a real copy when refused. On the device
            // SELinux refuses the link (avc denied { link }, untrusted_app_27), so in
            // practice every container gets a copy, made once (same size = kept)
            // 원본 파일에 대한 하드 링크, 거부되면 실제 복사. 기기에서는 SELinux가 링크를
            // 거부하므로(avc denied { link }, untrusted_app_27) 실제로는 컨테이너마다
            // 복사본이 생김. 한 번만 복사함(크기가 같으면 그대로 둠)
            boolean[] koreanCovered = new boolean[winFonts.length];
            for (int i = 0; i < winFonts.length; i++) {
                String[] wf = winFonts[i];
                File target = winSources[i];
                File f = new File(fontsDir, wf[0]);
                boolean fromUser = userWin[i] != null;
                if (fromUser) placedNow.add(wf[0]);
                // Symlink test: a generated font (not a user font, not the CJK
                // fallback) is linked by a symbolic link to files/fonts/korean-v1
                // instead of copied, saving about 33 MB per container. A link that
                // already points at the generated file is kept; any other link is
                // replaced. Builds before this one delete such links and copy again,
                // so going back to an earlier build undoes the test by itself
                // 심볼릭 링크 시험: 생성 글꼴(사용자 글꼴, CJK 대체 아님)은 복사하지 않고
                // files/fonts/korean-v1을 가리키는 심볼릭 링크로 연결함. 컨테이너당 약
                // 33MB를 아낌. 이미 생성 파일을 가리키는 링크는 그대로 두고, 다른 링크는
                // 바꿈. 이전 빌드는 이런 링크를 지우고 다시 복사하므로, 이전 빌드로
                // 돌아가면 시험이 저절로 되돌려짐
                boolean useSymlink = !fromUser && target != link;
                if (FileUtils.isSymlink(f)) {
                    if (!(useSymlink && target.getAbsolutePath().equals(symlinkTarget(f)))) f.delete();
                }
                else if (f.isFile()) {
                    // A Microsoft font put straight into the container is kept; one the
                    // app copied from the user folder follows that folder instead
                    // 컨테이너에 직접 넣은 Microsoft 글꼴은 그대로 둠. 앱이 사용자 폴더에서
                    // 복사한 것은 그 폴더를 따름
                    String copyright = !fromUser && !placedBefore.contains(wf[0]) ? KoreanFontBuilder.readCopyright(f) : null;
                    if (copyright != null && copyright.contains("Microsoft")) {
                        // Real Windows font copied in by the user
                        // 사용자가 넣은 진짜 Windows 글꼴
                        koreanCovered[i] = true;
                        Log.i("XServerDisplayActivity", "Windows font file kept (Microsoft): " + wf[0]);
                        continue;
                    }
                    // Our own earlier file (CJK copy, Nanum, older build) is replaced
                    // 예전에 직접 둔 파일(CJK 복사본, 나눔, 이전 생성본)은 바꿈
                    // A copy of the generated font is replaced by the link in the test
                    // 시험에서는 생성 글꼴의 복사본을 링크로 바꿈
                    if (useSymlink || f.length() != target.length()) f.delete();
                }
                String how = "kept";
                if (!f.exists() && !FileUtils.isSymlink(f)) {
                    how = "copy";
                    if (useSymlink) {
                        try {
                            android.system.Os.symlink(target.getAbsolutePath(), f.getAbsolutePath());
                            how = "symlink";
                        } catch (Exception e) {
                            Log.w("XServerDisplayActivity", "font symlink refused: " + wf[0], e);
                        }
                    }
                    if (how.equals("copy")) {
                        try {
                            android.system.Os.link(target.getAbsolutePath(), f.getAbsolutePath());
                            how = "hardlink";
                        } catch (Exception e) {
                            FileUtils.copy(target, f);
                        }
                    }
                } else if (FileUtils.isSymlink(f)) {
                    how = "symlink kept";
                }
                koreanCovered[i] = target != link;
                Log.i("XServerDisplayActivity", "Windows font file: " + wf[0] + " -> " + target.getName()
                        + (fromUser ? " (user folder)" : "") + " [" + how + "]");
            }
            // Remember what came from the user folder (see syncUserFonts())
            // 사용자 폴더에서 온 것을 기록함(syncUserFonts() 참고)
            FileUtils.writeString(userListFile, String.join("\n", placedNow));
            try (WineRegistryEditor reg = new WineRegistryEditor(new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/system.reg"))) {
                reg.setCreateKeyIfNotExist(true);
                for (String[] wf : winFonts) {
                    reg.setStringValue("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", wf[1], wf[0]);
                }
            }

            String entry = src.getName() + "," + face;
            File systemReg = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/system.reg");
            String key = "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink";
            String[] uiFonts = {"Tahoma", "Microsoft Sans Serif", "MS Shell Dlg", "MS Shell Dlg 2",
                    "Segoe UI", "Arial", "Verdana", "Lucida Sans Unicode"};
            try (WineRegistryEditor reg = new WineRegistryEditor(systemReg)) {
                reg.setCreateKeyIfNotExist(true);
                for (String f : uiFonts) reg.setMultiStringValue(key, f, entry);
                // Japanese / Korean font names that games and fan
                // translations ask for by name do not exist in the prefix; they
                // are substituted with the CJK face (same idea as winetricks
                // fakejapanese / fakekorean). Korean names are also registered as
                // font replacements below
                // 게임과 유저 번역이 이름으로 찾는 일본어 / 한국어 글꼴은 prefix에
                // 없음. CJK 글꼴로 대체함(winetricks fakejapanese / fakekorean과 같은
                // 방식). 한국어 글꼴은 아래에서 글꼴 대체 목록에도 등록함
                String subKey = "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes";
                String[] cjkNames = {"MS Gothic", "MS PGothic", "MS UI Gothic", "MS Mincho", "MS PMincho",
                        "Meiryo", "Meiryo UI", "Yu Gothic", "Gulim", "GulimChe", "Dotum", "DotumChe",
                        "Batang", "BatangChe", "Gungsuh", "GungsuhChe", "Malgun Gothic", "SimSun", "NSimSun",
                        "SimHei", "Microsoft YaHei", "MingLiU", "PMingLiU", "Microsoft JhengHei"};
                // A Korean name whose font file now carries that name is not
                // substituted (Wine applies a substitute even when the font exists)
                // 그 이름을 가진 글꼴 파일이 있는 한국어 이름은 대체하지 않음(Wine은 글꼴이
                // 있어도 대체 목록을 먼저 적용함)
                for (String f : cjkNames) {
                    int file = koreanFontFile(f);
                    if (file >= 0 && koreanCovered[file]) reg.removeValue(subKey, f);
                    else reg.setStringValue(subKey, f, face);
                }
            }

            // Korean Windows fonts, by their English and Korean names, are registered as
            // Wine font replacements (HKCU\Software\Wine\Fonts\Replacements, the
            // winetricks fakekorean method). Unlike FontSubstitutes, a replacement makes
            // the name exist as a font, so it also shows up when a game or app lists the
            // installed fonts. Only used for names whose generated font file is not in
            // place yet (sources not downloaded). The Korean face of the Noto CJK
            // collection is used whatever the container language is.
            // 한국어 Windows 글꼴을 영문·한글 이름으로 Wine 글꼴 대체 목록에 등록함
            // (HKCU\Software\Wine\Fonts\Replacements, winetricks fakekorean 방식).
            // FontSubstitutes와 달리 그 이름의 글꼴이 실제로 있는 것처럼 되므로, 게임이나
            // 프로그램이 설치된 글꼴 목록을 볼 때도 나타남. 만든 글꼴 파일이 아직 없는
            // 이름(원본을 못 받았음)에만 씀. 컨테이너 언어와 상관없이 Noto CJK 묶음의
            // 한국어 글꼴을 씀
            String koreanFace = src.getName().startsWith("NotoSansCJK") ? "Noto Sans CJK KR" : face;
            File userReg = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg");
            try (WineRegistryEditor reg = new WineRegistryEditor(userReg)) {
                reg.setCreateKeyIfNotExist(true);
                // Names whose font file carries them need no replacement (a real
                // font of that name exists); the others point to the Noto CJK face
                // 그 이름을 가진 글꼴 파일이 있으면 대체가 필요 없음(그 이름의 글꼴이 실제로
                // 있음). 나머지는 Noto CJK 글꼴을 가리킴
                for (int i = 0; i < KOREAN_FONT_NAMES.length; i++) {
                    if (koreanCovered[koreanFontFile(KOREAN_FONT_NAMES[i])]) {
                        reg.removeValue("Software\\Wine\\Fonts\\Replacements", KOREAN_FONT_NAMES[i]);
                    } else {
                        reg.setStringValue("Software\\Wine\\Fonts\\Replacements", KOREAN_FONT_NAMES[i], koreanFace);
                    }
                }
            }
            Log.d("XServerDisplayActivity", "CJK font link: " + entry);
        } catch (Exception e) {
            Log.w("XServerDisplayActivity", "CJK font link failed", e);
        }
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("imgVersion", imgVersion);
            containerDataChanged = true;
        }

        // Build the full "type-version" identifier(s) actually used for
        // extraction. For the combined DXVK+VKD3D option, both versions get
        // packed into one string, separated by "+", so extractDXWrapperFiles()
        // can pull each one out and install both independently.
        // 실제 압축 해제에 쓸 "타입-버전" 식별자를 완성하는 부분. DXVK+VKD3D
        // 통합 옵션일 땐 두 버전을 "+"로 구분해서 한 문자열에 담아, 아래
        // extractDXWrapperFiles()에서 각각 꺼내 독립적으로 설치하게 함
        String dxwrapper = this.dxwrapper;
        if (dxwrapper.equals("dxvk-vkd3d")) {
            // Containers saved before vkd3dVersion existed return "" here, which produced
            // the identifier "vkd3d-" - unresolvable, so VKD3D fell back to an arbitrary
            // installed profile. Fill missing keys from DefaultVersion instead.
            // vkd3dVersion 키가 생기기 전에 저장된 컨테이너는 여기서 ""를 돌려주고, 그 결과
            // "vkd3d-"라는 식별자가 만들어져 조회에 실패, 설치된 아무 프로필로 폴백됐음.
            // 빠진 키는 DefaultVersion 값으로 채움
            String dxvkVersion = dxwrapperConfig.get("version");
            if (dxvkVersion == null || dxvkVersion.isEmpty()) {
                // Latest tier build of this architecture first, the old constant last
                // 이 아키텍처의 Latest(최신) 단계 빌드를 먼저 쓰고, 옛 상수는 마지막에 씀
                dxvkVersion = fallbackComponentKey(true, DefaultVersion.DXVK);
                Log.w("XServerDisplayActivity", "dxwrapperConfig has no 'version'; using " + dxvkVersion);
            }

            String vkd3dVersion = dxwrapperConfig.get("vkd3dVersion");
            if (vkd3dVersion == null || vkd3dVersion.isEmpty()) {
                vkd3dVersion = fallbackComponentKey(false, DefaultVersion.VKD3D);
                Log.w("XServerDisplayActivity", "dxwrapperConfig has no 'vkd3dVersion'; using " + vkd3dVersion);
            }

            // Arch check: DefaultVersion holds arm64ec builds, so an x86_64 container
            // (or a config saved for the other arch) got DLLs it cannot load, and
            // D3D9-12 silently failed. Each version is now matched to this
            // container's Wine arch, using an installed profile of the right arch.
            // 아키텍처 확인: DefaultVersion은 arm64ec 빌드를 가리켜서, x86_64 컨테이너
            // (또는 다른 아키텍처용으로 저장된 설정)는 불러올 수 없는 DLL을 받았고
            // D3D9~12가 조용히 실패했음. 이제 각 버전을 이 컨테이너의 Wine
            // 아키텍처에 맞추고, 맞는 아키텍처로 설치된 프로필을 씀
            dxvkVersion = resolveWrapperVersion(ContentProfile.ContentType.CONTENT_TYPE_DXVK, dxvkVersion);
            vkd3dVersion = resolveWrapperVersion(ContentProfile.ContentType.CONTENT_TYPE_VKD3D, vkd3dVersion);

            dxwrapper = "dxvk-" + dxvkVersion + "+vkd3d-" + vkd3dVersion;
        }
        if (!dxwrapper.equals(container.getExtra("dxwrapper"))) {
            extractDXWrapperFiles(dxwrapper);
            container.putExtra("dxwrapper", dxwrapper);
            containerDataChanged = true;
        }

        String ddrawrapper = this.ddrawrapper;

        if (!ddrawrapper.equals(container.getExtra("ddrawrapper"))) {
            extractDDrawrapperFiles(ddrawrapper);
            container.putExtra("ddrawrapper", ddrawrapper);
            containerDataChanged = true;
        }

        if (ddrawrapper.equals("cnc-ddraw"))
            envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");

        // Only components this version offers; a shortcut saved earlier can still name one
        // that was taken out (vcrun2015)
        // 이 버전이 제공하는 항목만 씀. 예전에 저장한 바로가기에는 빠진 항목(vcrun2015)이
        // 남아 있을 수 있음
        String wincomponents = Container.offeredWinComponents(shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents());
        // Visual C++ 2015-2022 can have work with the same setting: DLLs taken after
        // it was turned on (or taken again newer), old files a game installer put
        // back, or files an exported container left out
        // Visual C++ 2015~2022는 설정이 같아도 할 일이 있을 수 있음: 켠 뒤에 꺼낸(또는 더
        // 새 버전으로 다시 꺼낸) DLL, 게임 설치 프로그램이 옛 파일로 덮은 경우, 내보낸
        // 컨테이너에서 빠진 파일
        File vcWindowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");
        vcrunOn = "1".equals(new KeyValueSet(wincomponents).get(VCRedistInstaller.ID, "0"));
        vcrunGameKey = shortcut != null ? VCRedistInstaller.gameKey(shortcut.getExtra("steamAppId"), shortcut.path) : null;
        vcrunGameLabel = shortcut != null ? shortcut.name : null;
        // If the app closed before the last game's DLLs were kept (see exitApp),
        // they are kept now for that game
        // 지난 게임의 DLL을 보관하기 전에 앱이 닫혔으면(exitApp 참고) 지금 그 게임용으로 보관함
        String lastGame = container.getExtra(VCRedistInstaller.LAST_GAME_EXTRA);
        if (!lastGame.isEmpty()) {
            VCRedistInstaller.collectNewer(this, imageFs, vcWindowsDir, lastGame, container.getExtra(VCRedistInstaller.LAST_GAME_NAME_EXTRA));
        }
        // Remember this game until its DLLs are kept (only while the component is on)
        // DLL을 보관할 때까지 이 게임을 기억함(항목이 켜져 있을 때만)
        boolean remember = vcrunOn && vcrunGameKey != null;
        if (!lastGame.equals(remember ? vcrunGameKey : "")) {
            container.putExtra(VCRedistInstaller.LAST_GAME_EXTRA, remember ? vcrunGameKey : null);
            container.putExtra(VCRedistInstaller.LAST_GAME_NAME_EXTRA, remember ? vcrunGameLabel : null);
            containerDataChanged = true;
        }
        if (!wincomponents.equals(container.getExtra("wincomponents"))
                || VCRedistInstaller.needsWork(imageFs, vcWindowsDir, vcrunOn, vcrunGameKey)) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme + "," + xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme + "," + xServer.screenInfo);
            containerDataChanged = true;
        }

        // Start menu "Computer" follows the same file manager choice as a launch without a shortcut
        // 시작 메뉴의 "Computer"도 바로가기 없이 실행할 때와 같은 파일 관리자를 따름
        WineStartMenuCreator.create(this, container, useGuestFileManager());
        // Save folders (AppData, Documents, Saved Games) point at the shared copy, so
        // a game keeps its saves on every layer (see SharedSaves)
        // 세이브 폴더(AppData, Documents, Saved Games)가 공용 사본을 가리키게 함. 그래서
        // 게임이 어느 레이어에서든 같은 세이브를 씀(SharedSaves 참고)
        com.winlator.cmod.container.SharedSaves.link(this, container);
        // Refresh removable-volume drives before the dosdevices links are rebuilt, so a
        // card inserted or swapped since the last launch is picked up. This writes to
        // Container.autoDrives, which saveData() does not persist.
        // dosdevices 링크를 다시 만들기 전에 착탈식 볼륨 드라이브를 갱신함. 지난 실행
        // 이후 꽂았거나 교체한 카드가 여기서 반영됨. Container.autoDrives에만 쓰며
        // saveData()는 이 값을 저장하지 않음
        container.setAutoDrives(StorageDrives.buildAutoDrives(this, container.getDrives()));
        WineUtils.createDosdevicesSymlinks(container);

        if (shortcut != null)
            startupSelection = shortcut.getExtra("startupSelection", String.valueOf(container.getStartupSelection()));
        else
            startupSelection = String.valueOf(container.getStartupSelection());

        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            WineUtils.changeServicesStatus(container, Byte.parseByte(startupSelection) != Container.STARTUP_SELECTION_NORMAL);
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }


        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {

        // Set environment variables
        // Pinned to C.UTF-8: imagefs ships no locale data, so a real locale such as ko_KR.UTF-8
        // makes setlocale() fail and fall back to ASCII-only "C", mangling non-ASCII paths.
        // The user's locale choice is carried by LANG instead.
        // C.UTF-8로 고정한다. imagefs에는 로케일 데이터가 없어 ko_KR.UTF-8 같은 실제 로케일을 넣으면
        // setlocale()이 실패하고 ASCII 전용 "C"로 떨어져 비ASCII 경로가 깨진다.
        // 사용자가 고른 로케일은 LANG으로 대신 전달한다.
        // Option (Settings, off by default): LC_ALL also gets the game
        // language. With LC_ALL pinned to C.UTF-8 it overrides LANG for every
        // category (POSIX rule), so Wine never sees ko_KR - a likely reason why
        // Korean-patched games fell back to their original text
        // 옵션(설정, 기본 꺼짐): LC_ALL에도 게임 언어를 넣음. LC_ALL을 C.UTF-8로
        // 고정하면 POSIX 규칙상 모든 항목에서 LANG보다 우선해서, Wine이 ko_KR을 보지
        // 못함 - 한국어 패치 게임이 원문으로 돌아간 이유일 가능성이 큼
        boolean realLocale = preferences.getBoolean("wine_real_locale", false)
                // A locale picked in the shortcut settings always
                // goes to LC_ALL too (that game needs that language)
                // 바로가기 설정에서 고른 로캘은 항상 LC_ALL에도 넣음(그 게임이
                // 그 언어를 필요로 함)
                || (shortcut != null && !shortcut.getExtra("lc_all").isEmpty());
        envVars.put("LC_ALL", realLocale ? LocaleEnv.normalizeLang(lc_all) : LocaleEnv.normalize());
        envVars.put("LANG", LocaleEnv.normalizeLang(lc_all));
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", imageFs.wineprefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty()
                ? "+" + wineDebugChannels.replace(",", ",+")
                : "-all"
        );
        // Cap the frame rate via DXVK's own built-in limiter, unless the user
        // has marked this device as gaming-only (then leave it uncapped).
        // DXVK 자체 프레임 제한 기능으로 상한을 걸되, "게이밍 전용 기기"로
        // 표시했으면 제한을 걸지 않음
        boolean unlimitedFPS = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("gaming_device_unlimited_fps", false);
        if (!unlimitedFPS) {
            int frameRateLimit = PreferenceManager.getDefaultSharedPreferences(this).getInt("frame_rate_limit", 120);
            envVars.put("DXVK_FRAME_RATE", String.valueOf(frameRateLimit));
        }
        // Clear any temporary directory
        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());


        // Create the appropriate launcher based on the container type
        GuestProgramLauncherComponent guestProgramLauncherComponent;

        bionicLauncher = new BionicProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(container.getWineVersion()),
                shortcut
        );
        guestProgramLauncherComponent = bionicLauncher;

        // Additional container checks and environment configuration
        if (container != null) {
            if (Byte.parseByte(startupSelection) == Container.STARTUP_SELECTION_AGGRESSIVE) {
                Log.d("XServerDisplayActivity", "Incorrect startup selection detected. Reverting to essential startup selection");
                container.putExtra("startupSelection", String.valueOf(Container.STARTUP_SELECTION_ESSENTIAL));
                container.saveData();
            }
            bionicLauncher.setContainer(this.container);
            bionicLauncher.setWineInfo(this.wineInfo);
            boolean wow64Mode = container.isWoW64Mode();
            // Construct the guest executable command
            String guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " + getWineStartCommand();
            // (Alternatively: "wine wineboot -u" or anything else you want)

            // Set up the guest program parameters
            guestProgramLauncherComponent.setWoW64Mode(wow64Mode);
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            // Merge in container’s environment variables
            envVars.putAll(container.getEnvVars());

            // Merge in shortcut environment variables if present
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));

            // If WINEESYNC is not defined, default to "1"
            if (!envVars.has("WINEESYNC")) {
                envVars.put("WINEESYNC", "1");
            }

            // Bind any drive paths the container defines
            ArrayList<String> bindingPaths = new ArrayList<>();
            // Must include auto drives, otherwise the SD card symlink exists but the
            // path is not bound and Wine sees an empty directory.
            // 자동 드라이브도 포함해야 함. 빠뜨리면 SD카드 심볼릭 링크는 만들어졌는데
            // 경로가 바인딩되지 않아 Wine 쪽에서 빈 폴더로 보임
            for (String[] drive : container.drivesIteratorWithAuto()) {
                bindingPaths.add(drive[1]);
            }
            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));

            // Box86/64 presets from container or shortcut
            // Hand-picked shortcut preset > crash-fallback preset > container preset
            // 바로가기에서 직접 고른 프리셋 > 크래시 대체 프리셋 > 컨테이너 프리셋
            guestProgramLauncherComponent.setBox64Preset(
                    shortcut != null
                            ? shortcut.getExtra("box64Preset",
                                    shortcut.getExtra(EXTRA_BOX64_AUTO_PRESET, container.getBox64Preset()))
                            : container.getBox64Preset()
            );
        }

        // Merge overrideEnvVars if present
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear(); // Clear overrideEnvVars as per smali logic
        }

        applyHudPolicy();

        // Stops Wine's winemenubuilder from writing .desktop files. It drops them
        // into xuser/Desktop - the folder the shortcut list reads - so every
        // installer (e.g. Steam) produced shortcuts even with the app's own .lnk
        // scan turned off, and its Exec line holds a quoted path the app then
        // mis-parsed ("Invalid name."). Appended so user overrides are kept.
        // Wine의 winemenubuilder가 .desktop 파일을 만들지 못하게 함. 이 파일이
        // 바로가기 목록이 읽는 xuser/Desktop에 생겨서, 앱의 .lnk 스캔을 꺼 둬도
        // 설치 프로그램(예: Steam)마다 바로가기가 생겼고, Exec 줄의 따옴표 경로를
        // 앱이 잘못 읽었음("Invalid name."). 사용자가 넣은 값은 유지되도록 덧붙임
        String dllOverrides = envVars.has("WINEDLLOVERRIDES") ? envVars.get("WINEDLLOVERRIDES") : "";
        if (!dllOverrides.contains("winemenubuilder")) {
            dllOverrides = dllOverrides.isEmpty()
                    ? "winemenubuilder.exe=d" : dllOverrides + ";winemenubuilder.exe=d";
        }
        // winhttp / version = native first, then builtin. Mod and
        // translation loaders (e.g. BepInEx's doorstop = winhttp.dll, many
        // patch loaders = version.dll) ship these next to the game exe, but Wine
        // prefers its builtin copy, so the patch never loaded (reported: Korean
        // patches showed the original text). Without a game copy the builtin
        // one is used, so other games are unaffected. A value the user set for
        // these DLLs is kept
        // winhttp / version을 네이티브 우선, 없으면 내장으로 둠. 모드·번역
        // 로더(예: BepInEx의 doorstop = winhttp.dll, 여러 패치 로더 = version.dll)가
        // 게임 exe 옆에 이 DLL을 두는데, Wine은 내장본을 먼저 써서 패치가 로드되지
        // 않았음(보고: 한국어 패치가 원문으로 나옴). 게임 폴더에 없으면 내장본을
        // 쓰므로 다른 게임에는 영향 없음. 사용자가 이 DLL에 넣은 값은 유지함
        if (!dllOverrides.contains("winhttp")) dllOverrides += ";winhttp=n,b";
        if (!dllOverrides.contains("version")) dllOverrides += ";version=n,b";
        envVars.put("WINEDLLOVERRIDES", dllOverrides);

        boolean enableGstreamer = container.isGstreamerWorkaround();
        if (shortcut != null && shortcut.hasExtra("gstreamerWorkaround")) {
            enableGstreamer = shortcut.getExtra("gstreamerWorkaround").equals("1");
        }

        if (enableGstreamer) {
            for (String envVar : Container.MEDIACONV_ENV_VARS) {
                String[] parts = envVar.split("=", 2);
                if (parts.length == 2) {
                    // Resolve the imagefs placeholder to this install's real path
                    // imagefs 자리표시자를 이 설치본의 실제 경로로 치환함
                    envVars.put(parts[0], parts[1].replace(Container.IMAGEFS_TOKEN, imageFs.getRootDir().getPath()));
                }
            }
        }

        // Create our overall XEnvironment with various components
        environment = new XEnvironment(this, imageFs);
        environment.addComponent(
                new SysVSharedMemoryComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)
                )
        );
        environment.addComponent(
                new XServerComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)
                )
        );


        environment.addComponent(new NetworkInfoUpdateComponent());

        // Audio driver logic
        if (audioDriver.equals("alsa") || audioDriver.equals("alsa-reflector")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");

            // Determine the mode based on the driver name
            // 드라이버 이름으로 리플렉터 모드 여부를 결정함
            boolean useReflector = audioDriver.equals("alsa-reflector");

            // Add the component and pass the mode directly into the constructor
            // 컴포넌트를 추가하면서 위에서 정한 모드를 생성자로 바로 넘김
            environment.addComponent(
                    new ALSAServerComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH),
                            useReflector
                    )
            );
        } else if (audioDriver.equals("pulseaudio")) {
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(
                    new PulseAudioComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)
                    )
            );
        }

        // RC (box86_64rc) file handling
        RCManager manager = new RCManager(this);
        manager.loadRCFiles();
        int rcfileId = shortcut == null
                ? container.getRCFileId()
                : Integer.parseInt(shortcut.getExtra("rcfileId", String.valueOf(container.getRCFileId())));
        RCFile rcfile = manager.getRcfile(rcfileId);

        File file = new File(container.getRootDir(), ".box64rc");
        String str = rcfile == null ? "" : rcfile.generateBox86_64rc();
        FileUtils.writeString(file, str);

        // Let Box64 inside Wine see this config
        envVars.put("BOX64_RCFILE", file.getAbsolutePath());

        // Pass final envVars to the launcher
        guestProgramLauncherComponent.setEnvVars(envVars);
        launchStartedAt = android.os.SystemClock.elapsedRealtime();
        startGameExitWatcher();
        // Tells the download service a game is running (pause-during-game option)
        // 다운로드 서비스에 게임이 실행 중임을 알림("게임 중 일시정지" 옵션)
        // (Marker is now written at the start of onCreate - see there)
        // (표시는 이제 onCreate 시작에서 씀 - 그쪽 참고)
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            recordBox64CrashIfAny(status);
            finish();
        });

        // Add the launcher to our environment
        environment.addComponent(guestProgramLauncherComponent);

        // Generate fexcore per app settings
        FEXCoreManager.createAppConfigFiles(this);

        // Start all environment components (XServer, Audio, etc.)
        environment.startEnvironmentComponents();

        // Start the WinHandler
        winHandler.start();

        // Properly initialize the WineRequestHandler with all necessary context before starting it
        if (wineRequestHandler != null) {
            wineRequestHandler.setContainer(this.container);
            wineRequestHandler.setShortcut(this.shortcut);
            wineRequestHandler.setEnvVars(this.envVars);
            wineRequestHandler.setWineInfo(this.wineInfo);
            wineRequestHandler.start();
        }

        // Reset dxwrapper config
        dxwrapperConfig = null;


    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        // HUD FPS = frames actually drawn that contained a new game frame
        // HUD FPS = 새 게임 프레임을 담아 실제로 그려진 프레임 수
        renderer.setOnFrameDrawnListener(() -> {
            if (frameRating != null) frameRating.onFrameDrawn();
        });

        if (shortcut != null) {
            if (shortcut.getExtra("forceFullscreen", "0").equals("1"))
                renderer.setForceFullscreenWMClass(shortcut.wmClass);
            renderer.setUnviewableWMClasses("explorer.exe");
            // Borderless is ON unless the shortcut explicitly turned it off ("0")
            // 바로가기에서 명시적으로 끄지("0") 않는 한 테두리 없음이 켜짐
            renderer.setBorderless(!"0".equals(shortcut.getExtra("borderless", "1")));
        }

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer, timeoutHandler, hideControlsRunnable);
        isMouseDisabled = preferences.getBoolean("touchscreen_mouse_disabled", false);
        touchpadView.setTouchscreenMouseDisabled(isMouseDisabled);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START))
                drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);


        inputControlsView = new InputControlsView(this, timeoutHandler, hideControlsRunnable);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);


        startTouchscreenTimeout();

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        }

        // Compact HUD: always created, because it also shows the
        // "starting" spinner while the game loads; engine name + FPS stay
        // afterwards only when "Show FPS" is on. Top-right, content-sized
        // 간단 HUD: 게임을 불러오는 동안 "시작 중" 스피너도 보여 주므로 항상
        // 만듦. 엔진 이름 + FPS는 "FPS 표시"가 켜져 있을 때만 이후에도 남음.
        // 오른쪽 위, 내용 크기만큼만 차지함
        if (container != null) {
            frameRating = new FrameRating(this, container);
            frameRating.setShowFps(container.isShowFPS());
            // Top-centre, content-sized: the old full-screen, left-aligned column
            // covered the picture. MangoHud replaces it when available
            // (applyHudPolicy() runs later and hides this one then).
            // 상단 가운데, 내용 크기만큼만 차지함: 예전엔 화면 전체 크기의 왼쪽 세로
            // 열이라 그림을 가렸음. MangoHud가 있으면 그걸 씀(나중에 실행되는
            // applyHudPolicy()가 이 오버레이를 숨김)
            FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.END);
            int hudMargin = (int) (8 * getResources().getDisplayMetrics().density);
            hudParams.setMargins(hudMargin, hudMargin, hudMargin, hudMargin);
            rootView.addView(frameRating, hudParams);
            frameRating.startLoading();
            // Games that never expose a render layer (no Vulkan/Mesa property)
            // would keep the spinner forever; it gives up after 90 seconds
            // 렌더 레이어 정보를 내보내지 않는 게임(Vulkan/Mesa 속성 없음)은 스피너가
            // 끝나지 않으므로 90초 뒤에 그만 돎
            // 120 s: first Steam launches build their app data first
            // 120초: 스팀 게임 첫 실행은 앱 데이터를 먼저 만들어서 오래 걸림
            frameRating.postDelayed(frameRating::finishLoading, 120_000);
        }

        // Get the fullscreen stretched extra from the shortcut if available
        String shortcutFullscreenStretched = shortcut != null ? shortcut.getExtra("fullscreenStretched") : null;

        // Proceed based on container and shortcut settings
        boolean shouldStretch = false;

        if (shortcut != null && shortcutFullscreenStretched != null) {
            // Shortcut exists and has a valid setting
            shouldStretch = shortcutFullscreenStretched.equals("1");
        } else if (container != null && container.isFullscreenStretched()) {
            // No shortcut or shortcut doesn't override, use the container's setting
            shouldStretch = true;
        }

        if (shouldStretch) {
            // Toggle fullscreen mode based on the final decision
            renderer.toggleFullscreen();
            touchpadView.toggleFullscreen();
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }

            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchpadView.setSimTouchScreen(simTouchScreen.equals("1"));
            // Saved by persistInputControlsChoice(); unset keeps the default (shown)
            // persistInputControlsChoice()가 저장함. 값이 없으면 기본값(표시) 유지
            inputControlsView.setShowTouchscreenControls(!"0".equals(shortcut.getExtra("showTouchscreenControls", "1")));
            // Input state at start; compared with the same line after the controls
            // dialog is confirmed (some games take mouse input only after that)
            // 시작 시 입력 상태. 컨트롤 창을 확인한 뒤의 같은 줄과 비교함(일부 게임은
            // 그 뒤에야 마우스 입력을 받음)
            touchpadView.post(() -> {
                // No controls profile but the controls layer still visible: it then
                // sits over the touchpad and swallows touches until the controls
                // dialog is confirmed once (device log: start controlsVisible=true
                // profile=none, after confirm controlsVisible=false). Hide it here,
                // the same as confirming "no profile"
                // 컨트롤 프로필이 없는데 컨트롤 층이 보이는 상태: 그러면 터치패드 위를
                // 덮어 컨트롤 창을 한 번 확인할 때까지 터치를 가로챘음(기기 로그: 시작
                // controlsVisible=true profile=none, 확인 후 controlsVisible=false).
                // "프로필 없음"을 확인한 것과 같게 여기서 숨김
                if (inputControlsView.getProfile() == null && inputControlsView.getVisibility() == View.VISIBLE) {
                    hideInputControls();
                }
                logInputState("start");
            });
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }


    private ActivityResultLauncher<Intent> controlsEitorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            }
    );

    private String parseShortcutNameFromDesktopFile(File desktopFile) {
        String shortcutName = "";
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Name=")) {
                        shortcutName = line.split("=")[1].trim();
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e("XServerDisplayActivity", "Error reading shortcut name from .desktop file", e);
            }
        }
        return shortcutName;
    }

    private void setTextColorForDialog(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                // If the child is a ViewGroup, recursively apply the color
                setTextColorForDialog((ViewGroup) child, color);
            } else if (child instanceof TextView) {
                // If the child is a TextView, set its text color
                ((TextView) child).setTextColor(color);
            }
        }
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.control_settings);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);

        dialog.getWindow().setBackgroundDrawableResource(R.drawable.neon_popup_bg);
        sProfile.setPopupBackgroundResource(R.drawable.neon_popup_bg);

        // Set text color for all TextViews in the dialog to white or black based on dark mode
        int textColor = ContextCompat.getColor(this, isDarkMode ? R.color.white : R.color.black);
        ViewGroup dialogViewGroup = (ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content);
        setTextColorForDialog(dialogViewGroup, textColor);

        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- " + getString(R.string.disabled) + " --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (inputControlsView.getProfile() != null && profile.id == inputControlsView.getProfile().id)
                    selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final SwitchCompat cbSimTouchScreen = dialog.findViewById(R.id.CBSimulateTouchScreen);
        cbSimTouchScreen.setChecked(touchpadView.isSimTouchScreen());

        final SwitchCompat cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        final SwitchCompat cbEnableTimeout = dialog.findViewById(R.id.CBEnableTimeout);
        cbEnableTimeout.setChecked(preferences.getBoolean("touchscreen_timeout_enabled", false));

        final SwitchCompat cbEnableHaptics = dialog.findViewById(R.id.CBEnableHaptics);
        cbEnableHaptics.setChecked(preferences.getBoolean("touchscreen_haptics_enabled", false));

        final SwitchCompat cbDisableTouchscreenMouse = dialog.findViewById(R.id.CBDisableTouchscreenMouse);
        cbDisableTouchscreenMouse.setChecked(preferences.getBoolean("touchscreen_mouse_disabled", false));


        final Runnable updateProfile = () -> {
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            } else hideInputControls();
        };

        // BUG FIX ("gear just bounces to the main screen", "opens it twice
        // on re-entry"): this used to launch MainActivity itself (with an
        // "edit_input_controls" extra for it to notice in onCreate and
        // forward into InputControlsFragment). MainActivity has no
        // launchMode/onNewIntent override, so every tap started a genuinely
        // new MainActivity instance stacked on top of the still-running
        // game - visually indistinguishable from being kicked back to the
        // main app, and each additional tap (or each return trip) piled up
        // another full instance, which is the "runs twice" symptom. There is
        // also no reason to go through MainActivity at all: it only exists to
        // forward into InputControlsFragment, which itself only exists to
        // forward into ControlsEditorActivity once the user finds and taps
        // ITS OWN settings button (see InputControlsFragment's BTControlsEditor
        // handler) - two extra hops for what should be one tap. Launching
        // ControlsEditorActivity directly, exactly like that handler does,
        // skips both.
        // 버그 수정("톱니바퀴 누르면 그냥 메인화면으로 튕김", "다시 들어가면
        // 두 번 실행됨"): 예전엔 MainActivity 자체를 실행했음("edit_input_
        // controls" 추가 데이터를 onCreate에서 감지해 InputControlsFragment로
        // 넘기는 방식). MainActivity에 launchMode나 onNewIntent 재정의가 없어서,
        // 누를 때마다 아직 실행 중인 게임 위에 완전히 새로운 MainActivity
        // 인스턴스가 쌓였음 - 겉보기엔 메인 앱으로 튕겨 나간 것과 구분이 안
        // 됐고, 추가로 누르거나 돌아올 때마다 인스턴스가 하나씩 더 쌓인 게
        // "두 번 실행됨" 증상이었음. 애초에 MainActivity를 거칠 이유도 없었음:
        // MainActivity는 InputControlsFragment로 넘기기만 하고,
        // InputControlsFragment도 사용자가 자기 설정 버튼을 직접 찾아 눌러야
        // ControlsEditorActivity로 넘어감(InputControlsFragment의
        // BTControlsEditor 처리부 참고) - 탭 한 번이면 될 걸 두 단계 거친 것임.
        // 그 처리부와 똑같이 ControlsEditorActivity를 바로 실행하면 둘 다
        // 건너뜀
        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            if (position <= 0) {
                AppUtils.showToast(this, R.string.no_profile_selected);
                return;
            }
            Intent intent = new Intent(this, ControlsEditorActivity.class);
            intent.putExtra("profile_id", inputControlsManager.getProfiles().get(position - 1).id);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
                updateProfile.run();
            };
            controlsEitorActivityResultLauncher.launch(intent);
        });

        dialog.setOnConfirmCallback(() -> {
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            // Remember what was picked here so the next launch starts the same way
            // 여기서 고른 값을 기억해서 다음 실행도 같은 상태로 시작하게 함
            int selPos = sProfile.getSelectedItemPosition();
            persistInputControlsChoice(selPos > 0 ? inputControlsManager.getProfiles().get(selPos - 1) : null,
                    cbShowTouchscreenControls.isChecked(), cbSimTouchScreen.isChecked());
            boolean isTimeoutEnabled = cbEnableTimeout.isChecked();
            boolean isHapticsEnabled = cbEnableHaptics.isChecked();
            boolean isMouseDisabled = cbDisableTouchscreenMouse.isChecked();
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
            editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
            editor.putBoolean("touchscreen_mouse_disabled", isMouseDisabled);
            editor.apply();

            if (isTimeoutEnabled) {
                startTouchscreenTimeout(); // Start the timeout functionality if enabled
            } else {
                touchpadView.setOnTouchListener(null); // Disable the listener if timeout is disabled
            }
            if (isMouseDisabled) {
                touchpadView.setTouchscreenMouseDisabled(true);
            } else
                touchpadView.setTouchscreenMouseDisabled(false);

            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            } else hideInputControls();
            touchpadView.setSimTouchScreen(cbSimTouchScreen.isChecked());
            updateProfile.run();
            logInputState("controls-confirmed");
        });

        dialog.setOnCancelCallback(updateProfile::run);

        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    /**
     * Saves the in-game input-control choices. Per shortcut when launched from
     * one (same "controlsProfile"/"simTouchScreen" extras the shortcut settings
     * dialog writes, so both screens stay in sync), and globally as the default
     * for launches without a shortcut. "0" means "no profile" - the same value
     * ShortcutSettingsDialog.loadControlsProfileSpinner() treats as none.
     * 게임 안에서 고른 입력 컨트롤 설정을 저장함. 바로가기로 실행했으면 그 바로가기에
     * 저장하고(바로가기 설정창이 쓰는 "controlsProfile"/"simTouchScreen" extra와 같아서
     * 두 화면이 서로 맞음), 바로가기 없이 실행할 때 쓸 기본값으로도 전역 저장함.
     * "0"은 "프로필 없음"이며 ShortcutSettingsDialog.loadControlsProfileSpinner()도
     * 같은 값을 없음으로 취급함
     */
    private void persistInputControlsChoice(ControlsProfile profile, boolean showTouchControls, boolean simTouch) {
        preferences.edit()
                .putInt("last_controls_profile_id", profile != null ? profile.id : 0)
                .putBoolean("show_touchscreen_controls_enabled", showTouchControls)
                .apply();
        if (shortcut != null) {
            shortcut.putExtra("controlsProfile", profile != null ? String.valueOf(profile.id) : "0");
            shortcut.putExtra("simTouchScreen", simTouch ? "1" : "0");
            shortcut.putExtra("showTouchscreenControls", showTouchControls ? "1" : "0");
            shortcut.saveData();
        }
    }

    // restoreGlobalProfile: false for shortcut launches (see the caller in onCreate)
    // restoreGlobalProfile: 바로가기로 실행하면 false임(onCreate의 호출부 참고)
    private void simulateConfirmInputControlsDialog(boolean restoreGlobalProfile) {
        // Simulate setting the relative mouse movement and touchscreen controls from preferences

        // Default is now true: with false the on-screen buttons of a selected
        // profile were never drawn, which looked like the virtual pad was missing
        // 기본값을 true로 바꿈: false면 선택한 프로필의 화면 버튼이 아예 그려지지
        // 않아서 가상 패드가 없는 것처럼 보였음
        boolean isShowTouchscreenControls = preferences.getBoolean("show_touchscreen_controls_enabled", true);
        inputControlsView.setShowTouchscreenControls(isShowTouchscreenControls);

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        boolean isHapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", false);

        // Apply these settings as if the user confirmed the dialog
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
        editor.putBoolean("touchscreen_haptics_enabled", isHapticsEnabled);
        editor.apply();

        // Restores the profile last picked in-game. "selected_profile_index" was
        // read here but never written anywhere, so this always fell to "hidden".
        // An id is stored instead of a list index so reordering can't shift it.
        // 게임 안에서 마지막으로 고른 프로필을 복원함. 예전 "selected_profile_index"는
        // 여기서 읽기만 하고 어디서도 저장하지 않아서 항상 "숨김"으로 끝났음.
        // 목록 순서가 바뀌어도 어긋나지 않게 인덱스 대신 id를 저장함
        ControlsProfile lastProfile = restoreGlobalProfile
                ? inputControlsManager.getProfile(preferences.getInt("last_controls_profile_id", 0))
                : null;
        if (lastProfile != null) {
            showInputControls(lastProfile);
        } else {
            // No profile selected, ensure the controls are hidden
            hideInputControls();
        }

        // Timeout logic should only apply if the controls are visible
        if (isTimeoutEnabled && inputControlsView.getVisibility() == View.VISIBLE) {
            startTouchscreenTimeout(); // Start timeout if enabled and controls are visible
        } else {
            touchpadView.setOnTouchListener(null); // Disable the timeout listener if not needed
        }

        Log.d("XServerDisplayActivity", "Input controls simulated confirmation executed.");
    }

    @SuppressLint("ClickableViewAccessibility")
    private void startTouchscreenTimeout() {
        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);

        if (isTimeoutEnabled) {
            // Show controls initially and set up touch event listeners
            inputControlsView.setVisibility(View.VISIBLE);
            Log.d("XServerDisplayActivity", "Timeout is enabled, setting up timeout logic.");

            // Attach the OnTouchListener to reset the timeout on touch events
            touchpadView.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    // Reset the timeout on any touch event

                    // Keep the controls visible
                    inputControlsView.setVisibility(View.VISIBLE);

                    // Remove any pending hide callbacks and reset the timeout
                    timeoutHandler.removeCallbacks(hideControlsRunnable);
                    timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Reset timeout
                }

                return false; // Allow the touch event to propagate
            });

            // Reset the timeout when the controls are initially displayed
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Hide after 5 seconds of inactivity
        } else {
            // If timeout is disabled, keep the controls always visible
            Log.d("XServerDisplayActivity", "Timeout is disabled, controls will stay visible.");

            inputControlsView.setVisibility(View.VISIBLE); // Ensure controls are visible
            timeoutHandler.removeCallbacks(hideControlsRunnable); // Remove any existing hide callbacks
            touchpadView.setOnTouchListener(null); // Remove the touch listener
        }
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        inputControlsView.invalidate();

        // If the selected profile is a virtual gamepad, we must enable the P1 slot.
        if (profile.isVirtualGamepad()) {
            ControllerManager controllerManager = ControllerManager.getInstance();
            // Ensure Player 1 slot is enabled so a vjoy device is created for it.
            controllerManager.setSlotEnabled(0, true);
            // The physical P1 assignment is no longer cleared here. unassignSlot(0)
            // wiped the saved Bluetooth pad every time a virtual-gamepad profile was
            // shown, so the pad had to be assigned again by hand. Both now feed P1;
            // whichever was used last wins, which is what a user switching between
            // screen buttons and a real pad expects.
            // 여기서 더 이상 실물 P1 할당을 지우지 않음. unassignSlot(0)은 가상 게임패드
            // 프로필이 뜰 때마다 저장된 블루투스 패드 할당을 지워서 매번 손으로 다시
            // 지정해야 했음. 이제 둘 다 P1으로 들어가고 마지막에 쓴 쪽이 반영됨 -
            // 화면 버튼과 실물 패드를 번갈아 쓰는 사용자가 기대하는 동작임
            // Update its internal state.
            if (winHandler != null) {
                winHandler.refreshControllerMappings();
            }
        }
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        ControlsProfile hiddenProfile = inputControlsView.getProfile();
        // If the profile we are hiding was a virtual gamepad...
        if (hiddenProfile != null && hiddenProfile.isVirtualGamepad()) {
            ControllerManager controllerManager = ControllerManager.getInstance();
            // ...and if no physical controller is assigned to P1...
            if (controllerManager.getAssignedDeviceForSlot(0) == null) {
                // ...then disable the slot so we don't have an orphaned vjoy device.
                controllerManager.setSlotEnabled(0, false);
            }
        }

        inputControlsView.invalidate();
    }

    // Per-shortcut "Desktop GPU" toggle. Makes DXVK's DXGI/D3D9 adapter report
    // an AMD Radeon RX 580 (vendor 1002, device 67df) instead of the phone GPU,
    // for games that refuse to start on an unknown/mobile adapter. AMD rather
    // than NVIDIA because DXVK itself reports NVIDIA GPUs as AMD by default to
    // keep games off NVAPI, which is not available here. D3D12 goes through the
    // same DXGI adapter. WineD3D is NOT covered (it reads the container's
    // registry GPU name instead).
    // 숏컷별 "데스크탑 GPU" 토글임. DXVK의 DXGI/D3D9 어댑터가 폰 GPU 대신
    // AMD Radeon RX 580(vendor 1002, device 67df)으로 보고하게 함. 모르는/모바일
    // 어댑터에서 실행을 거부하는 게임용임. NVIDIA가 아니라 AMD인 이유: DXVK 자체가
    // NVAPI를 피하려고 기본적으로 NVIDIA GPU를 AMD로 보고하며, 여기엔 NVAPI가 없음.
    // D3D12도 같은 DXGI 어댑터를 거침. WineD3D는 적용 안 됨(컨테이너 레지스트리의
    // GPU 이름 설정을 따름)
    private void applyDesktopGpuSpoof() {
        if (shortcut == null || !"1".equals(shortcut.getExtra("gpuSpoof", "0"))) return;
        String spoof = ";dxgi.customVendorId = 1002;dxgi.customDeviceId = 67df"
                + ";d3d9.customVendorId = 1002;d3d9.customDeviceId = 67df;";
        String cfg = envVars.get("DXVK_CONFIG");
        // Inserted before DXVKConfig's closing quote; the leading ';' keeps the
        // first key from being glued to the opening quote
        // DXVKConfig가 붙인 닫는 따옴표 앞에 끼워 넣음. 앞의 ';'는 첫 키가 여는
        // 따옴표에 붙어 버리지 않게 하기 위함
        if (cfg.endsWith("\"")) cfg = cfg.substring(0, cfg.length() - 1) + spoof + "\"";
        else cfg = cfg + spoof;
        envVars.put("DXVK_CONFIG", cfg);
        Log.d("XServerDisplayActivity", "Desktop GPU spoof applied: " + cfg);
    }

    // HUD policy.
    // 1) Containers created before this change still carry the old default
    //    DXVK_HUD (6 lines over the picture); only that exact untouched value is
    //    dropped - a DXVK_HUD the user typed is kept.
    // 2) "Show FPS": if the imagefs ships the MangoHud Vulkan layer, MangoHud is
    //    turned on as a single line at the top centre and the Android overlay is
    //    not created. Otherwise the Android overlay (FrameRating) is used in the
    //    same top-centre one-line form (see setupFrameRatingOverlay()).
    // HUD 정책.
    // 1) 이 변경 전에 만든 컨테이너는 옛 기본 DXVK_HUD(화면 위 6줄)를 갖고 있음.
    //    손대지 않은 그 값일 때만 지움 - 사용자가 직접 넣은 DXVK_HUD는 유지함.
    // 2) "FPS 표시": imagefs에 MangoHud Vulkan 레이어가 있으면 MangoHud를 상단
    //    가운데 한 줄로 켜고 안드로이드 오버레이는 만들지 않음. 없으면 안드로이드
    //    오버레이(FrameRating)를 같은 상단 가운데 한 줄 형태로 씀
    //    (setupFrameRatingOverlay() 참고)
    private boolean mangoHudActive = false;
    // True when this launch goes through the SteamLite agent (see setupXEnvironment)
    // 이번 실행이 SteamLite 에이전트를 거치면 true(setupXEnvironment 참고)
    private boolean steamAgentActive = false;

    private void applyHudPolicy() {
        if (Container.LEGACY_DEFAULT_DXVK_HUD.equals(envVars.get("DXVK_HUD"))) envVars.remove("DXVK_HUD");

        mangoHudActive = false;
        // "Show FPS" always uses the compact Android HUD (engine +
        // FPS + loading spinner). MangoHud's own text could not carry the
        // loading state and its size was reported as too large
        // "FPS 표시"는 항상 간단한 안드로이드 HUD(엔진 + FPS + 로딩 스피너)를
        // 씀. MangoHud 글자로는 로딩 상태를 보여 줄 수 없었고 크기가 너무 크다는
        // 보고가 있었음
        if (true) return;
        if (container == null || !container.isShowFPS()) return;
        if (!isMangoHudAvailable()) {
            Log.d("XServerDisplayActivity", "HUD: MangoHud layer not found in imagefs, using overlay");
            return;
        }
        envVars.put("MANGOHUD", "1");
        String cfg = envVars.get("MANGOHUD_CONFIG");
        // Replace only the stock default config; a custom one is respected
        // 기본 설정값일 때만 바꿈. 사용자가 바꾼 설정은 존중함
        if (cfg == null || cfg.isEmpty() || cfg.equals("engine_version,gpu_stats=0")) {
            envVars.put("MANGOHUD_CONFIG", "position=top-center,horizontal,hud_compact,hud_no_margin,"
                    + "fps,frametime=0,frame_timing=0,cpu_stats=0,gpu_stats=0,font_size=18,background_alpha=0.3");
        }
        mangoHudActive = true;
        Log.d("XServerDisplayActivity", "HUD: MangoHud enabled (top-center, one line)");
    }

    // Looks for the MangoHud library or its Vulkan layer manifest in imagefs
    // imagefs 안에서 MangoHud 라이브러리 또는 Vulkan 레이어 매니페스트를 찾음
    private boolean isMangoHudAvailable() {
        File root = imageFs.getRootDir();
        String[] dirs = {"usr/lib", "usr/lib/mangohud", "usr/share/vulkan/implicit_layer.d", "usr/share/vulkan/explicit_layer.d"};
        for (String d : dirs) {
            File[] files = new File(root, d).listFiles();
            if (files == null) continue;
            for (File f : files) if (f.getName().toLowerCase(java.util.Locale.ENGLISH).contains("mangohud")) return true;
        }
        return false;
    }

    private void extractGraphicsDriverFiles() {
        String adrenoToolsDriverId = "";
        String selectedDriverVersion;

        String currentWrapperVersion = graphicsDriverConfig.getOrDefault("version", DefaultVersion.WRAPPER);
        String isAdrenotoolsTurnip = graphicsDriverConfig.getOrDefault("adrenotoolsTurnip", "1"); // Default to "1"

        selectedDriverVersion = currentWrapperVersion;

        if (shortcut != null) {
            currentWrapperVersion = shortcut.getExtra("wrapperGraphicsDriverVersion", graphicsDriverConfig.get("version"));
            selectedDriverVersion = currentWrapperVersion;
        }

        adrenoToolsDriverId = (selectedDriverVersion.contains(DefaultVersion.WRAPPER)) ? DefaultVersion.WRAPPER : selectedDriverVersion;
        Log.d("GraphicsDriverExtraction", "Adrenotools DriverID: " + adrenoToolsDriverId);

        File rootDir = imageFs.getRootDir();

        if (dxwrapper.equals("dxvk") || dxwrapper.equals("dxvk-vkd3d")) {
            DXVKConfig.setEnvVars(this, dxwrapperConfig, envVars);
            applyDesktopGpuSpoof();
        }
        if (dxwrapper.equals("vkd3d") || dxwrapper.equals("dxvk-vkd3d")) {
            DXWrapperConfigDialog.setEnvVars(this, dxwrapperConfig, envVars);
        }


        boolean useDRI3 = preferences.getBoolean("use_dri3", true);
        if (!useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "sw");
        }

        if (currentWrapperVersion.toLowerCase().contains("turnip") && isAdrenotoolsTurnip.equals("0"))
            envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/freedreno_icd.aarch64.json");
        else
            envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");
        envVars.put("GALLIUM_DRIVER", "zink");
        envVars.put("LIBGL_KOPPER_DISABLE", "true");

        // 1. Get the main WRAPPER selection (e.g., "Wrapper-v2") from the class field.
        String mainWrapperSelection = this.graphicsDriver;

        // 2. Get the WRAPPER that was last saved to the container's settings.
        // BUG FIX: wrapper files are extracted into the SHARED imagefs, but the
        // "already extracted" marker was stored per container. Container A
        // (Wrapper) and B (Wrapper-v2) each matched their own marker, so neither
        // re-extracted and A could run on v2's files. The marker is now global.
        // 버그 수정: wrapper 파일은 여러 컨테이너가 공유하는 imagefs에 풀리는데
        // "이미 풀었음" 표시는 컨테이너별로 저장됐음. 컨테이너 A(Wrapper)와
        // B(Wrapper-v2)가 각자 자기 표시와 일치해서 아무도 다시 풀지 않았고, A가
        // v2 파일로 실행될 수 있었음. 이제 표시를 전역으로 저장함
        String lastInstalledMainWrapper = preferences.getString("imagefs_last_wrapper", "");

        // 3. Check if we need to extract a new wrapper file.
        if (firstTimeBoot || !mainWrapperSelection.equals(lastInstalledMainWrapper)) {
            // We only extract if the selection is actually a wrapper file.
            if (mainWrapperSelection.toLowerCase().startsWith("wrapper")) {
                String assetPath = "graphics_driver/" + mainWrapperSelection.toLowerCase() + ".tzst";
                Log.d("GraphicsDriverExtraction", "WRAPPER selection changed or first boot. Extracting: " + assetPath);
                boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetPath, rootDir);
                if (success) {
                    // Global marker (see above) / 전역 표시(위 참고)
                    preferences.edit().putString("imagefs_last_wrapper", mainWrapperSelection).apply();
                }
            }

            // 4. Extract common libraries, but only when the container is first created.
            if (firstTimeBoot) {
                Log.d("XServerDisplayActivity", "First time container boot, extracting extra_libs.tzst");
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs.tzst", rootDir);
            }
        }

        // equals(), not != : a String from the config is a different object from
        // the "System" literal, so != was always true
        // != 대신 equals(): 설정에서 읽은 String은 "System" 리터럴과 다른 객체라
        // !=는 항상 true였음
        if (!"System".equals(adrenoToolsDriverId)) {
            AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
            adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId);
        }

        envVars.put("WRAPPER_VK_VERSION", "1.3");

        String blacklistedExtensions = graphicsDriverConfig.get("blacklistedExtensions");
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions);

        String maxDeviceMemory = graphicsDriverConfig.get("maxDeviceMemory");
        if (maxDeviceMemory != null && Integer.parseInt(maxDeviceMemory) > 0)
            envVars.put("UTIL_LAYER_VMEM_MAX_SIZE", maxDeviceMemory);

        String frameSync = graphicsDriverConfig.getOrDefault("frameSync", "Normal");
        if (frameSync.equals("Always") && useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "forcesync");
        } else if (frameSync.equals("Never")) {
            envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", "1");
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
        if (!vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1");
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig);
        }
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // If we hold capture, captured events will come through the listener/Activity.
        // Skip the external path to avoid duplication.
        if (pointerCaptureRequested &&
                (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            // Still allow WinHandler to see gyro/other sources if you rely on it
            boolean handledByWinHandler = winHandler != null && winHandler.onGenericMotionEvent(event);
            return handledByWinHandler || super.dispatchGenericMotionEvent(event);
        }

        boolean handledByWinHandler = winHandler != null && winHandler.onGenericMotionEvent(event);
        boolean handledByTouchpadView = touchpadView != null && touchpadView.onExternalMouseEvent(event);
        boolean handledBySuper = super.dispatchGenericMotionEvent(event);

        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            resetTouchscreenTimeoutFromController();
        }

        return handledByWinHandler || handledByTouchpadView || handledBySuper;
    }


    private static final int RECAPTURE_DELAY_MS = 10000; // 10 seconds

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        // Handle the PlayStation or Xbox Home button to open the drawer
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE || event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
                openXServerDrawer(); // Method to open the XServer drawer
                return true; // Indicate the event was handled
            }
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Release pointer capture when Volume Down key is pressed
            if (touchpadView != null && pointerCaptureRequested) {
                touchpadView.releasePointerCapture();
                touchpadView.setOnCapturedPointerListener(null);
                pointerCaptureRequested = false;

                // Show toast message for pointer release
                showToast(this, "Pointer capture released for 10 seconds");

                // Schedule recapture after 10 seconds
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (touchpadView != null) {
                        touchpadView.requestPointerCapture();
                        touchpadView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                            @Override
                            public boolean onCapturedPointer(View view, MotionEvent event) {
                                handleCapturedPointer(event);
                                return true;
                            }
                        });
                        pointerCaptureRequested = true;

                        // Show toast message for pointer recapture
                        showToast(this, "Pointer re-captured. If not working, press again to release and re-capture");
                    }
                }, RECAPTURE_DELAY_MS);

                return true; // Indicate that the event was handled
            }
        }

        // Fallback to existing input handling
        boolean isGameController = ExternalController.isGameController(event.getDevice());
        if (isGameController) resetTouchscreenTimeoutFromController();

        // BUG FIX: the old one-liner returned FALSE when InputControlsView or
        // WinHandler DID handle a pad key (the "!handled && ..." chain collapses to
        // false). Android then treats the key as unhandled and synthesizes a
        // FLAG_FALLBACK key: logcat showed BUTTON_THUMBL/START each followed by a
        // DPAD_CENTER (flags 0x408), and BUTTON_B falls back to BACK - so one press
        // became two inputs, and B could open the menu / leave the game.
        // Now a handled key returns true, and fallback keys coming from a pad are
        // swallowed because the original key was already delivered.
        // 버그 수정: 예전 한 줄짜리 식은 InputControlsView나 WinHandler가 패드 키를
        // 처리했을 때 오히려 false를 돌려줬음("!처리됨 && ..." 연쇄가 false로 끝남).
        // 그러면 안드로이드가 키를 미처리로 보고 FLAG_FALLBACK 키를 새로 만들어 냄:
        // 로그캣에서 BUTTON_THUMBL/START 뒤마다 DPAD_CENTER(flags 0x408)가 따라왔고,
        // BUTTON_B는 BACK으로 대체됨 - 한 번 눌러도 입력이 두 번 들어가고, B 버튼이
        // 메뉴를 열거나 게임을 빠져나갈 수 있었음. 이제 처리된 키는 true를 돌려주고,
        // 원래 키가 이미 전달됐으므로 패드에서 온 대체 키는 버림
        if (isGameController && (event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) return true;
        if (inputControlsView.onKeyEvent(event)) return true;
        if (winHandler.onKeyEvent(event)) return true;
        if (xServer.keyboard.onKeyEvent(event)) return true;
        return !isGameController && super.dispatchKeyEvent(event);
    }

    // Keeps the on-screen touch controls visible while the user is actively
    // playing with an external controller. Without this, only touches on
    // InputControlsView/TouchpadView reset the 5s auto-hide timer (see the
    // setup in onCreate below), so a controller session with the touchscreen
    // otherwise untouched would silently hide the controls mid-play even
    // though the game is actively being played.
    // 외장 컨트롤러로 활발히 플레이 중일 때도 온스크린 터치 컨트롤이
    // 계속 보이게 함. 이게 없으면 InputControlsView/TouchpadView 터치만
    // 5초 자동숨김 타이머를 리셋시켜서(아래 onCreate의 설정 참고),
    // 화면은 안 만지고 컨트롤러로만 플레이하는 세션에서는 실제로는 활발히
    // 플레이 중인데도 온스크린 컨트롤이 조용히 사라졌음
    private void resetTouchscreenTimeoutFromController() {
        if (inputControlsView == null || timeoutHandler == null || hideControlsRunnable == null) return;
        // Same default as everywhere else (false = always visible)
        // 다른 곳과 같은 기본값 사용(false = 항상 표시)
        if (!preferences.getBoolean("touchscreen_timeout_enabled", false)) return;
        inputControlsView.setVisibility(View.VISIBLE);
        timeoutHandler.removeCallbacks(hideControlsRunnable);
        timeoutHandler.postDelayed(hideControlsRunnable, 5000);
    }


    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private static final String TAG = "DXWrapperExtraction";

    // Marker embedded in the assemblyIdentity so we only ever overwrite manifests we wrote.
    // 우리가 쓴 매니페스트만 덮어쓰기 위해 assemblyIdentity에 심어두는 식별 표식.
    private static final String UTF8_MANIFEST_MARKER = "WGLator.Utf8CodePage";

    // External SxS manifest forcing the process ANSI code page to UTF-8.
    // Wine reads this through the activation context (implemented in Wine 7.7, bug 52223),
    // so it works on Proton 9.0 too.
    // 프로세스 ANSI 코드페이지를 UTF-8로 강제하는 외부 SxS 매니페스트.
    // Wine은 activation context로 이 값을 읽는다(Wine 7.7, 버그 52223에서 구현).
    // 따라서 현재 기본값인 Proton 9.0에서도 동작한다.
    private static final String UTF8_ACTIVE_CODEPAGE_MANIFEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\">\n"
                    + "  <assemblyIdentity type=\"win32\" name=\"" + UTF8_MANIFEST_MARKER + "\" version=\"1.0.0.0\"/>\n"
                    + "  <application xmlns=\"urn:schemas-microsoft-com:asm.v3\">\n"
                    + "    <windowsSettings>\n"
                    + "      <activeCodePage xmlns=\"http://schemas.microsoft.com/SMI/2019/WindowsSettings\">UTF-8</activeCodePage>\n"
                    + "    </windowsSettings>\n"
                    + "  </application>\n"
                    + "</assembly>\n";


    // Installs the chosen graphics wrapper's DLLs. DXVK (D3D9-11) and VKD3D
    // (D3D12) are always installed together now, as one combined option -
    // see the "dxvk-vkd3d" identifier built above. wined3d just restores the
    // stock Wine DLLs, no DXVK/VKD3D involved.
    // 선택한 그래픽 래퍼의 DLL을 설치함. DXVK(D3D9~11)와 VKD3D(D3D12)는
    // 이제 하나의 통합 옵션으로 항상 같이 설치됨 - 위에서 만든 "dxvk-vkd3d"
    // 식별자 참고. wined3d는 그냥 원래 Wine DLL을 복원하는 것뿐, DXVK/VKD3D와 무관함
    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll"};

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("+vkd3d-")) {
            // Split "dxvk-<ver>+vkd3d-<ver>" into its two halves and install
            // each independently.
            // "dxvk-<버전>+vkd3d-<버전>"을 반으로 잘라 각각 독립적으로 설치
            String[] parts = dxwrapper.split("\\+");
            extractDxvkFiles(parts[0], windowsDir);
            extractVkd3dFiles(parts[1], windowsDir);
        } else if (dxwrapper.contains("wined3d")) {
            Log.d(TAG, "Restoring original DLL files for wined3d.");
            restoreOriginalDllFiles(dlls);
        }
    }

    // Installs DXVK. Also pulls in D8VK for D3D8 support if the chosen DXVK
    // version predates DXVK 2.4 (D8VK was merged into DXVK itself at 2.4).
    // DXVK 설치. 선택한 DXVK 버전이 2.4 이전이면 D8VK(D3D8 지원)도 같이
    // 설치함(D8VK는 DXVK 2.4부터 DXVK 자체에 병합되었음)
    private void extractDxvkFiles(String dxwrapper, File windowsDir) {
        Log.d(TAG, "Extracting DXVK wrapper files, version: " + dxwrapper);

        ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
        if (profile != null) {
            Log.d(TAG, "Applying user-defined DXVK content profile: " + dxwrapper);
            contentsManager.applyContent(profile);
            return;
        }

        // TarCompressorUtils.extract() returns false when the asset does not exist,
        // which used to be ignored - the result was "the app runs but no DXVK DLL is
        // ever installed", with nothing in the log to say so.
        // TarCompressorUtils.extract()는 에셋이 없으면 false를 반환하는데 이걸
        // 무시하고 있었음. 그 결과 "앱은 실행되는데 DXVK DLL은 하나도 설치되지
        // 않는" 상태가 되고, 로그에도 아무 흔적이 남지 않았음
        Log.d(TAG, "Extracting fallback DXVK .tzst archive: " + dxwrapper);
        boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxwrapper + ".tzst", windowsDir, onExtractFileListener);

        if (extracted) {
            if (compareVersion(StringUtils.parseNumber(dxwrapper), "2.4") < 0) {
                Log.d(TAG, "Extracting d8vk as part of DXVK version " + dxwrapper);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-" + DefaultVersion.D8VK + ".tzst", windowsDir, onExtractFileListener);
            }
            return;
        }

        // Neither a profile nor a bundled asset matched. Rather than leave the prefix
        // with no D3D9-11 implementation at all, use the first installed DXVK profile
        // whose architecture matches the requested one.
        // 프로필도 번들 에셋도 매칭되지 않은 경우. 프리픽스에 D3D9~11 구현이
        // 아예 없는 상태로 두는 대신, 요청된 아키텍처와 맞는 DXVK 프로필 중
        // 첫 번째 것을 사용함
        ContentProfile substitute = findSubstituteProfile(ContentProfile.ContentType.CONTENT_TYPE_DXVK, dxwrapper);
        if (substitute != null) {
            Log.e(TAG, "DXVK \"" + dxwrapper + "\" not found; falling back to installed profile: " + substitute.verName);
            contentsManager.applyContent(substitute);
        } else {
            Log.e(TAG, "DXVK \"" + dxwrapper + "\" not found and no DXVK content is installed - D3D9-11 will not work.");
        }
    }

    // Picks a replacement profile of the given type when the requested identifier
    // cannot be resolved. Architecture matters more than version here: an arm64ec
    // Wine tree cannot load x86_64 DLLs, so the arm64ec-ness of the requested name
    // is matched first, and only then does it settle for anything installed.
    // 요청한 식별자를 찾을 수 없을 때 같은 타입의 대체 프로필을 고름.
    // 여기서는 버전보다 아키텍처가 중요함 - arm64ec Wine 트리는 x86_64 DLL을
    // 로드할 수 없으므로, 먼저 요청 이름의 arm64ec 여부를 맞춰보고
    // 그래도 없으면 설치된 아무 프로필이나 사용함
    /**
     * Returns requested ("verName-verCode") if an installed profile with that
     * name exists AND matches this container's arch; otherwise the first installed
     * profile of the matching arch; otherwise requested unchanged (the .tzst /
     * substitute path below then logs what is missing).
     * 요청값("verName-verCode")과 같은 이름의 설치 프로필이 있고 이 컨테이너의
     * 아키텍처와도 맞으면 그대로 돌려줌. 아니면 맞는 아키텍처로 설치된 첫 프로필,
     * 그것도 없으면 요청값을 그대로 돌려줌(그 뒤 .tzst/대체 경로가 무엇이 없는지
     * 로그로 남김)
     */
    private String resolveWrapperVersion(ContentProfile.ContentType type, String requested) {
        boolean wantArm64EC = wineInfo != null && wineInfo.isArm64EC();
        List<ContentProfile> profiles = contentsManager.getProfiles(type);
        if (profiles == null) return requested;
        ContentProfile firstMatchingArch = null;
        for (ContentProfile p : profiles) {
            if (p.remoteUrl != null || p.verName == null) continue;
            boolean isArm = p.verName.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec");
            if (isArm != wantArm64EC) continue;
            String name = p.verName + "-" + p.verCode;
            if (name.equals(requested)) return requested;
            if (firstMatchingArch == null) firstMatchingArch = p;
        }
        if (firstMatchingArch == null) return requested;
        String picked = firstMatchingArch.verName + "-" + firstMatchingArch.verCode;
        Log.w("XServerDisplayActivity", type + " " + requested + " does not match container arch (arm64ec="
                + wantArm64EC + "); using " + picked);
        return picked;
    }

    private ContentProfile findSubstituteProfile(ContentProfile.ContentType type, String requestedName) {
        List<ContentProfile> profiles = contentsManager.getProfiles(type);
        if (profiles == null || profiles.isEmpty()) return null;

        // Deriving this from requestedName fails when the name is malformed - a container
        // missing vkd3dVersion asks for "vkd3d-", which contains no "arm64ec", so an
        // x86_64 profile got installed onto an arm64ec prefix. The parsed Wine
        // architecture is authoritative; the name is only a fallback.
        // requestedName에서 유도하면 이름이 깨졌을 때 틀림. vkd3dVersion이 없는 컨테이너는
        // "vkd3d-"를 요청하는데 여기에 "arm64ec"가 없어서, arm64ec 프리픽스에 x86_64
        // 프로필이 설치됐음. 파싱된 Wine 아키텍처가 정답이고 이름은 보조 수단일 뿐임
        boolean wantArm64EC = (wineInfo != null)
                ? wineInfo.isArm64EC()
                : requestedName.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec");
        // BUG FIX: wantArm64EC was computed but never used, so any profile could
        // be returned - including one for the other arch
        // 버그 수정: wantArm64EC를 계산만 하고 안 써서, 다른 아키텍처용 프로필까지
        // 아무거나 돌려줬음
        for (ContentProfile p : profiles) {
            if (p.remoteUrl != null || p.verName == null) continue;
            if (p.verName.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec") == wantArm64EC) return p;
        }
        return null;
    }
    // Installs VKD3D-Proton (D3D12).
    // VKD3D-Proton(D3D12) 설치
    private void extractVkd3dFiles(String dxwrapper, File windowsDir) {
        Log.d(TAG, "Extracting VKD3D wrapper files, version: " + dxwrapper);

        ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
        if (profile != null) {
            Log.d(TAG, "Applying user-defined VKD3D content profile: " + dxwrapper);
            contentsManager.applyContent(profile);
            return;
        }

        // Same silent-failure problem as DXVK above: a missing asset returned false
        // and nothing was reported.
        // 위 DXVK와 같은 조용한 실패 문제. 에셋이 없으면 false만 반환되고
        // 아무것도 보고되지 않았음
        Log.d(TAG, "Extracting fallback VKD3D .tzst archive: " + dxwrapper);
        boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/" + dxwrapper + ".tzst", windowsDir, onExtractFileListener);
        if (extracted) return;

        ContentProfile substitute = findSubstituteProfile(ContentProfile.ContentType.CONTENT_TYPE_VKD3D, dxwrapper);
        if (substitute != null) {
            Log.e(TAG, "VKD3D \"" + dxwrapper + "\" not found; falling back to installed profile: " + substitute.verName);
            contentsManager.applyContent(substitute);
        } else {
            Log.e(TAG, "VKD3D \"" + dxwrapper + "\" not found and no VKD3D content is installed - D3D12 will not work.");
        }
    }
    private void extractDDrawrapperFiles(String ddrawrapper) {
        final String[] dlls = {"ddraw.dll","d3dimm.dll"};
        final String[] glideDlls = {"glide.dll", "glide2x.dll", "glide3x.dll", "3DfxSpl.dll", "3DfxSpl2.dll", "3DfxSpl3.dll"};

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        Log.d("XServerDisplayActivity", "Deleting glide dlls before extraction");
        for (String glideDLL : glideDlls) {
            FileUtils.delete(new File(windowsDir + "/syswow64/" + glideDLL));
        }

        if (ddrawrapper.equals("wined3d")) {
            Log.d("XserverDisplayActivity", "Restoring original dlls for WineD3D");
            restoreOriginalDllFiles(dlls);
        }
        else {
            Log.d("XServerDisplayActivity", "Extracting ddrawrapper " + ddrawrapper);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, onExtractFileListener);
        }

        Log.d("XServerDisplayActivity", "Extracting nglide wrapper");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir, onExtractFileListener);
    }


    private static int compareVersion(String varA, String varB) {
        final String[] levelsA = varA.split("\\.");
        final String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        int numA, numB;

        for (int i = 0; i < minLen; i++) {
            numA = Integer.parseInt(levelsA[i]);
            numB = Integer.parseInt(levelsB[i]);
            if (numA != numB)
                return numA - numB;
        }

        if (levelsA.length != levelsB.length)
            return levelsA.length - levelsB.length;

        return 0;
    }

    private void extractWinComponentFiles() {
        Log.d("XServerDisplayActivity", "Extracting WinComponents");
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = Container.offeredWinComponents(shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents());

            // Last applied state, compared by name. It used to be walked in step with
            // the current list by position, which reads past its end (and throws)
            // once the default list gains an entry such as vcrun2015
            // 마지막으로 적용한 상태. 이름으로 비교함. 예전에는 현재 목록과 위치로 짝지어
            // 걸었는데, 기본 목록에 vcrun2015 같은 항목이 늘면 끝을 넘어 읽다가 예외가 났음
            KeyValueSet oldWinComponents = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS));

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                // A component not applied before (no old value) is always applied
                // 전에 적용한 적이 없는 항목(예전 값 없음)은 항상 적용함
                String oldValue = oldWinComponents.get(wincomponent[0], null);
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");
                // Visual C++ with work to do (see needsWork) runs even when the
                // setting did not change
                // 할 일이 있는 Visual C++(needsWork 참고)는 설정이 바뀌지 않았어도 처리함
                boolean vcrunPending = VCRedistInstaller.ID.equals(identifier)
                        && VCRedistInstaller.needsWork(imageFs, windowsDir, useNative, vcrunGameKey);
                if (oldValue != null && wincomponent[1].equals(oldValue) && !firstTimeBoot && !vcrunPending) continue;

                if (!wineInfo.isArm64EC() && identifier.contains("opengl") && useNative)
                    continue;

                if (VCRedistInstaller.ID.equals(identifier)) {
                    // Visual C++ 2015-2022: files are only added, never taken out
                    // Visual C++ 2015~2022: 파일은 넣기만 하고 빼지 않음
                    if (useNative) {
                        // Copied from the files taken from Microsoft's installers into
                        // imagefs, only where the prefix has none or an older version.
                        // Not taken yet -> Wine's builtin DLLs stay in place (the
                        // override is native,builtin, so the builtin ones are used)
                        // Microsoft 설치 파일에서 꺼내 imagefs에 둔 파일을, 프리픽스에 없거나
                        // 더 낮은 버전일 때만 복사함. 아직 안 꺼냈으면 Wine 기본 DLL이 그대로
                        // 남음(override가 native,builtin이라 기본 DLL이 쓰임)
                        if (!VCRedistInstaller.applyTo(imageFs, windowsDir, vcrunGameKey))
                            Log.w("XServerDisplayActivity", "vcrun2015 is on but not installed yet - Wine builtin DLLs are used");
                        WineUtils.overrideWinComponentDlls(this, container, identifier, true);
                    }
                    else if (VCRedistInstaller.isMarked(windowsDir)) {
                        // Off after EZlator put files in: they stay, and the override
                        // "builtin" makes Wine use its own DLLs
                        // EZlator가 파일을 넣은 뒤 끔: 파일은 두고, override "builtin"으로
                        // Wine 기본 DLL을 쓰게 함
                        WineUtils.setWinComponentDllLoadOrder(this, container, identifier, "builtin");
                    }
                    else {
                        // Off and never put in by EZlator: the override is removed and
                        // the files are not touched (a runtime the user installed in
                        // the container stays as it is)
                        // 꺼짐이고 EZlator가 넣은 적 없음: override만 지우고 파일은 건드리지
                        // 않음(사용자가 컨테이너 안에서 설치한 런타임은 그대로 둠)
                        WineUtils.overrideWinComponentDlls(this, container, identifier, false);
                    }
                    // Files on the mark that are gone (an exported container leaves them
                    // out) and could not be filled from the store get Wine's builtin back
                    // 표시 목록에 있는데 없어진 파일(내보낸 컨테이너에서 빠짐) 중 보관소로
                    // 채우지 못한 것은 Wine 기본 파일을 다시 넣음
                    restoreBuiltinFiles(VCRedistInstaller.missingApplied(windowsDir));
                    Log.d("XServerDisplayActivity", "Setting wincomponent " + identifier + " to " + String.valueOf(useNative));
                    continue;
                }
                else if (useNative) {
                    // <id>.tzst, or an uncompressed <id>.tar
                    // <id>.tzst, 없으면 압축하지 않은 <id>.tar
                    boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, onExtractFileListener)
                            || TarCompressorUtils.extract(TarCompressorUtils.Type.NONE, this, "wincomponents/"+identifier+".tar", windowsDir, onExtractFileListener);
                    if (!extracted) Log.w("XServerDisplayActivity", "wincomponent package missing in APK: " + identifier);
                }
                else {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                    }
                }
                Log.d("XServerDisplayActivity", "Setting wincomponent " + identifier + " to " + String.valueOf(useNative));
                WineUtils.overrideWinComponentDlls(this, container, identifier, useNative);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, this);
            }

            // A component that was applied as native but is no longer offered is put
            // back to Wine's builtin DLLs once: overrides removed and the builtin files
            // copied over the native ones
            // native로 적용됐다가 더 이상 제공하지 않는 항목은 한 번 Wine 기본 DLL로
            // 되돌림: override를 지우고 기본 파일로 native 파일을 덮어씀
            KeyValueSet currentWinComponents = new KeyValueSet(wincomponents);
            for (String[] oldComponent : oldWinComponents) {
                String identifier = oldComponent[0];
                if (currentWinComponents.get(identifier, null) != null || !oldComponent[1].equals("1")) continue;
                if (!wincomponentsJSONObject.has(identifier)) continue;
                // Visual C++ files are never taken out (only added)
                // Visual C++ 파일은 빼지 않음(넣기만 함)
                if (VCRedistInstaller.ID.equals(identifier)) continue;
                JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                for (int i = 0; i < dlnames.length(); i++) {
                    String dlname = dlnames.getString(i);
                    dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                }
                Log.d("XServerDisplayActivity", "wincomponent no longer offered, restoring builtin: " + identifier);
                WineUtils.overrideWinComponentDlls(this, container, identifier, false);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, false, this);
            }

            if (!dlls.isEmpty()) restoreOriginalDllFiles(dlls.toArray(new String[0]));
        }
        catch (JSONException e) {}
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File system32dlls = null;
        File syswow64dlls = null;

        if (wineInfo.isArm64EC())
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/aarch64-windows");
        else
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/x86_64-windows");

        syswow64dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");


        for (String dll : dlls) {
            File srcFile = new File(system32dlls, dll);
            File dstFile = new File(windowsDir, "system32/" + dll);
            FileUtils.copy(srcFile, dstFile);
            srcFile = new File(syswow64dlls, dll);
            dstFile = new File(windowsDir, "syswow64/" + dll);
            FileUtils.copy(srcFile, dstFile);
        }
   }

    // Puts Wine's builtin file back only where it is missing, per folder
    // ("system32/<name>" or "syswow64/<name>"); an existing file in the other
    // folder is not touched
    // 없어진 자리에만 폴더별로 Wine 기본 파일을 다시 넣음("system32/<이름>" 또는
    // "syswow64/<이름>"). 다른 폴더에 있는 파일은 건드리지 않음
    private void restoreBuiltinFiles(java.util.List<String> relPaths) {
        if (relPaths == null || relPaths.isEmpty()) return;
        File windowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX+"/drive_c/windows");
        File system32dlls = new File(imageFs.getWinePath() + (wineInfo.isArm64EC() ? "/lib/wine/aarch64-windows" : "/lib/wine/x86_64-windows"));
        File syswow64dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");
        for (String rel : relPaths) {
            int slash = rel.indexOf('/');
            if (slash <= 0) continue;
            String sub = rel.substring(0, slash);
            String name = rel.substring(slash + 1);
            File dst = new File(windowsDir, rel);
            if (dst.isFile()) continue;
            File src = new File("syswow64".equals(sub) ? syswow64dlls : system32dlls, name);
            boolean ok = src.isFile() && FileUtils.copy(src, dst) && dst.isFile();
            Log.d("XServerDisplayActivity", "builtin restored for missing " + rel + ": " + ok);
        }
    }

    // winhandler.exe / wfm.exe are ANSI binaries: argv comes in through GetCommandLineA and the
    // target is launched with ShellExecuteA. Under the default code page 1252 every non-ASCII
    // path character collapses to '?', so Korean folder names fail with STATUS 0xc0000033.
    // Dropping a UTF-8 activeCodePage manifest beside each exe makes Wine serve their A-variant
    // APIs in UTF-8 instead.
    //
    // The same prefix is reachable two ways: the live imageFs mount that the rest of this file
    // uses (extractDXWrapperFiles, extractWinComponentFiles), and the container's own directory
    // that WineUtils.createDosdevicesSymlinks uses. Whether they resolve to the same place
    // depends on when the container link is in effect, so both are tried and deduped by
    // canonical path - writing the manifest twice to one location is pointless, missing it
    // entirely is fatal.
    //
    // winhandler.exe / wfm.exe는 ANSI 바이너리다. GetCommandLineA로 인자를 받고 ShellExecuteA로 실행한다.
    // 기본 코드페이지 1252에서는 비ASCII 문자가 전부 '?'로 뭉개져 한글 폴더명이 0xc0000033으로 실패한다.
    // 각 exe 옆에 UTF-8 activeCodePage 매니페스트를 두면 Wine이 그 프로세스의 A 계열 API를 UTF-8로 처리한다.
    //
    // 같은 프리픽스에 도달하는 경로가 두 개다. 이 파일의 나머지 코드가 쓰는 imageFs 마운트 경로와,
    // WineUtils.createDosdevicesSymlinks가 쓰는 컨테이너 자체 경로다. 둘이 같은 곳을 가리키는지는
    // 컨테이너 연결 시점에 달려 있으므로, 양쪽 다 시도하되 실제 경로가 같으면 한 번만 쓴다.
    // 한 곳에 두 번 쓰는 건 무의미하지만 아예 못 쓰면 치명적이기 때문.
    private void ensureUtf8CodePageManifests() {
        // Collect every distinct windows directory that actually exists on disk right now.
        // 지금 디스크에 실제로 존재하는 windows 폴더를 중복 없이 모은다.
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        ArrayList<File> windowsDirs = new ArrayList<>();

        File fromImageFs = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");
        File fromContainer = (container != null)
                ? new File(container.getRootDir(), ".wine/drive_c/windows")
                : null;

        for (File dir : new File[]{fromImageFs, fromContainer}) {
            if (dir == null || !dir.isDirectory()) continue;
            try {
                // Canonical path collapses the xuser link, so a shared target is written once.
                // 정규 경로로 비교하면 xuser 링크가 풀려서 같은 대상은 한 번만 기록된다.
                if (seen.add(dir.getCanonicalPath())) windowsDirs.add(dir);
            } catch (IOException e) {
                windowsDirs.add(dir);
            }
        }

        if (windowsDirs.isEmpty()) {
            Log.w("XServerDisplayActivity", "UTF-8 manifest: no windows dir found (tried "
                    + fromImageFs + " / " + fromContainer + ")");
            return;
        }

        // Both ANSI launchers need it. wfm.exe is the in-guest file manager.
        // 두 ANSI 런처 모두 필요하다. wfm.exe는 게스트 내 파일 관리자.
        final String[] exeNames = {"winhandler.exe", "wfm.exe"};

        for (File windowsDir : windowsDirs) {
            for (String exeName : exeNames) {
                try {
                    if (!new File(windowsDir, exeName).isFile()) {
                        Log.w("XServerDisplayActivity", "UTF-8 manifest: " + exeName
                                + " missing in " + windowsDir.getPath() + ", skipping");
                        continue;
                    }

                    File manifestFile = new File(windowsDir, exeName + ".manifest");

                    // Content is a fixed constant, so an exact match means nothing to do.
                    // 내용이 고정 상수이므로 완전히 일치하면 다시 쓸 필요가 없다.
                    String current = manifestFile.isFile() ? FileUtils.readString(manifestFile) : null;
                    if (UTF8_ACTIVE_CODEPAGE_MANIFEST.equals(current)) continue;

                    if (FileUtils.writeString(manifestFile, UTF8_ACTIVE_CODEPAGE_MANIFEST)) {
                        Log.d("XServerDisplayActivity", "Deployed UTF-8 activeCodePage manifest -> "
                                + manifestFile.getPath());
                    } else {
                        Log.w("XServerDisplayActivity", "Failed to write UTF-8 manifest -> "
                                + manifestFile.getPath());
                    }
                } catch (Exception e) {
                    Log.w("XServerDisplayActivity", "Error deploying UTF-8 manifest for " + exeName, e);
                }
            }
        }
    }

    /**
     * Phase 2: decides whether this launch goes through the SteamLite agent
     * (shortcut has a Steam AppID, agent installed, user signed in) and adds the
     * session env. Called from getWineStartCommand() because the command line is
     * built before the rest of the environment.
     * 2단계: 이번 실행을 SteamLite 에이전트로 할지 정함(바로가기에 Steam AppID가
     * 있고, 에이전트가 설치돼 있고, 로그인 상태). 세션 환경변수도 넣음. 명령줄이
     * 나머지 환경 설정보다 먼저 만들어지므로 getWineStartCommand()에서 호출함
     */
    private void prepareSteamAgent() {
        steamAgentActive = false;
        if (shortcut == null) return;
        int appId = 0;
        try { appId = Integer.parseInt(shortcut.getExtra("steamAppId", "0")); } catch (NumberFormatException ignored) {}
        if (appId <= 0) return;
        if (!com.winlator.cmod.steam.SteamLiteManager.isInstalled(this)) {
            Log.w("XServerDisplayActivity", "Steam AppID set but SteamLite not installed - normal launch");
            return;
        }
        File driveC = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c");
        // Downloaded Steam games: appmanifest + common/<dir> links into this container
        // 받은 Steam 게임: appmanifest + common/<폴더> 링크를 이 컨테이너에 연결함
        com.winlator.cmod.steam.SteamGameInstaller.linkLibraryInto(this, driveC);
        if (com.winlator.cmod.steam.SteamLiteManager.stageIntoContainer(this, driveC)
                && com.winlator.cmod.steam.SteamLiteManager.applyEnv(this, envVars, appId)) {
            steamAgentActive = true;
            Log.i("XServerDisplayActivity", "Steam agent launch for appId=" + appId);
            // Diagnostics: Wine's stdout/stderr is discarded unless a debug callback
            // exists, so the agent's own messages never reached logcat. Lines that
            // look Steam-related are mirrored under tag "SteamAgent".
            // 진단용: 디버그 콜백이 없으면 Wine의 stdout/stderr가 버려져서 에이전트
            // 메시지가 로그캣에 전혀 안 남았음. Steam 관련으로 보이는 줄만 "SteamAgent"
            // 태그로 로그캣에 옮김
            // The agent is a GUI .exe, so it has no stdout - it and the Valve client
            // write log FILES instead (confirmed: the stdout capture below stayed
            // empty on 2026-09-27). New/changed .txt/.log files under the Steam
            // folder are dumped to logcat ("SteamAgent") 40 s and 100 s after start.
            // 에이전트는 GUI .exe라 표준 출력이 없고, 에이전트와 Valve 클라이언트는
            // 로그를 파일로 남김(2026-09-27에 아래 표준 출력 수집이 비어 있었던 것으로
            // 확인). Steam 폴더 아래 새로 생기거나 바뀐 .txt/.log 파일을 시작 40초,
            // 100초 뒤에 로그캣("SteamAgent")으로 뽑아냄
            // Diagnostics below are for debug builds only: release builds must not
            // copy Steam/agent logs (account name, SteamID) into logcat, nor slow Wine
            // down with trace channels
            // 아래 진단은 디버그 빌드 전용임: 릴리즈 빌드는 Steam/에이전트 로그(계정
            // 이름, SteamID)를 로그캣에 옮기면 안 되고, trace 채널로 Wine을 느리게
            // 만들어서도 안 됨
            if (com.winlator.cmod.core.StorageDrives.isReleaseBuild(this)) return;
            final File steamDirForLogs = new File(driveC, "Program Files (x86)/Steam");
            final long launchTime = System.currentTimeMillis() - 5000;
            // Also C:\ root: the agent's own log (wn-launcher.log, WinNative lineage) is
            // written there, not under Steam. Dumps at 20/45/90 s - steam.exe exited
            // at ~50 s last time, so the old 40/100 s schedule missed the end.
            // C:\ 루트도 봄: 에이전트 자체 로그(WinNative 계열 wn-launcher.log)는 Steam
            // 폴더가 아니라 여기에 씀. 20/45/90초에 출력함 - 지난번 steam.exe가 약
            // 50초에 종료돼서 예전 40/100초 일정으론 끝부분을 놓쳤음
            Thread dumper = new Thread(() -> {
                try {
                    for (long wait : new long[]{20000, 25000, 45000}) {
                        Thread.sleep(wait);
                        dumpSteamAgentLogs(driveC, launchTime, 3);   // C:\ root only / C:\ 루트만
                        dumpSteamAgentLogs(steamDirForLogs, launchTime, 0);
                    }
                } catch (InterruptedException ignored) {}
            }, "SteamAgentLogs");
            dumper.setDaemon(true);
            dumper.start();

            // Diagnostics (logcat 2026-09-27 21:16): steam.exe exited on its own ~37 s in,
            // no game process appeared and the agent left no log file. Wine's own
            // process + error channels show whether the game exe was spawned and why
            // it died (missing DLL, crash, early exit). Capped at 2000 lines.
            // 진단용(로그캣 2026-09-27 21:16): steam.exe가 약 37초에 스스로 종료됐고,
            // 게임 프로세스는 안 생겼으며, 에이전트 로그 파일도 없었음. Wine 자체의
            // process + err 채널로 게임 exe가 실행됐는지, 왜 죽었는지(DLL 누락, 크래시,
            // 조기 종료)를 봄. 최대 2000줄
            envVars.put("WINEDEBUG", "err+all,+process");
            final int[] captured = {0};
            com.winlator.cmod.core.ProcessHelper.addDebugCallback(line -> {
                if (line == null || captured[0] > 2000) return;
                String l = line.toLowerCase(java.util.Locale.ENGLISH);
                if (l.contains("err:") || l.contains(":process:") || l.contains(".exe")
                        || l.contains("steam") || l.contains("agent") || l.contains("logon") || l.contains("wn_")) {
                    captured[0]++;
                    Log.i("SteamAgent", line.length() > 400 ? line.substring(0, 400) : line);
                }
            });
        } else {
            Log.w("XServerDisplayActivity", "Steam agent not used (not signed in or staging failed) - normal launch");
        }
    }

    // Prints the last 80 lines of every .txt/.log under dir (3 levels deep) that
    // changed since `since` - small files only
    // dir 아래(3단계까지) since 이후 바뀐 .txt/.log 파일마다 마지막 80줄을 출력함 -
    // 작은 파일만
    private static void dumpSteamAgentLogs(File dir, long since, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth < 3) dumpSteamAgentLogs(f, since, depth + 1);
                continue;
            }
            String n = f.getName().toLowerCase(java.util.Locale.ENGLISH);
            if (!(n.endsWith(".txt") || n.endsWith(".log")) || f.lastModified() < since || f.length() > 2_000_000) continue;
            try {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1);
                int from = Math.max(0, lines.size() - 80);
                Log.i("SteamAgent", "==== " + f.getPath() + " (" + lines.size() + " lines) ====");
                for (int i = from; i < lines.size(); i++) {
                    String l = lines.get(i);
                    Log.i("SteamAgent", l.length() > 400 ? l.substring(0, 400) : l);
                }
            } catch (Exception e) {
                Log.w("SteamAgent", "read failed: " + f, e);
            }
        }
    }

    private String getWineStartCommand() {
        prepareSteamAgent();
        // Initialize overrideEnvVars if not already done
        EnvVars envVars = getOverrideEnvVars();

        // Define default arguments
        String args = "";

        if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " " + execArgs : "";

            // BUG FIX (Steam "Invalid name."): the .desktop Exec path can already be
            // wrapped in quotes. endsWith(".lnk") then failed on the closing quote,
            // the path was split like an .exe and the command became
            // /dir "C:\\users\\Public\\Desktop "Steam.lnk"" - an invalid name for Wine.
            // Quotes are stripped first and the extension check ignores case.
            // 버그 수정(Steam "Invalid name."): .desktop의 Exec 경로가 이미 따옴표로
            // 감싸져 있을 수 있음. 그러면 닫는 따옴표 때문에 endsWith(".lnk")가
            // 실패하고 .exe처럼 쪼개져서 명령이 /dir "C:\\users\\Public\\Desktop "Steam.lnk""가
            // 됐음 - Wine이 못 읽는 이름. 따옴표를 먼저 벗기고 확장자는 대소문자 무시로 비교함
            String shortcutPath = shortcut.path.trim();
            if (shortcutPath.length() > 1 && shortcutPath.startsWith("\"") && shortcutPath.endsWith("\""))
                shortcutPath = shortcutPath.substring(1, shortcutPath.length() - 1);
            if (shortcutPath.toLowerCase(java.util.Locale.ENGLISH).endsWith(".lnk")) {
                args += "\"" + shortcutPath + "\"" + execArgs;
            } else {
                String exeDir = FileUtils.getDirname(shortcutPath);
                String filename = FileUtils.getName(shortcutPath);

                int dotIndex = filename.lastIndexOf(".");
                int spaceIndex = (dotIndex != -1) ? filename.indexOf(" ", dotIndex) : -1;

                if (spaceIndex != -1) {
                    execArgs = filename.substring(spaceIndex + 1) + execArgs;
                    filename = filename.substring(0, spaceIndex);
                }

                args += "/dir " + StringUtils.escapeDOSPath(exeDir) + " \"" + filename + "\"" + execArgs;
            }
        } else {
            // Append EXTRA_EXEC_ARGS from overrideEnvVars if it exists
            if (envVars.has("EXTRA_EXEC_ARGS")) {
                args += " " + envVars.get("EXTRA_EXEC_ARGS");
                envVars.remove("EXTRA_EXEC_ARGS"); // Remove the key after use
            } else {
                // Container opened without a shortcut: the file manager window.
                // X86_64 gets Wine's own explorer.exe, see useGuestFileManager()
                // 바로가기 없이 컨테이너를 열었을 때 띄우는 파일 관리자 창임.
                // X86_64는 Wine 자체 explorer.exe를 씀. useGuestFileManager() 참고
                args += useGuestFileManager() ? "\"wfm.exe\"" : "\"explorer.exe\" C:\\";
            }
        }

        // Deploy the UTF-8 code page manifest before winhandler.exe is ever started.
        // winhandler.exe가 기동되기 전에 UTF-8 코드페이지 매니페스트를 먼저 배치한다.
        ensureUtf8CodePageManifests();

        // Agent wraps the game: steam.exe "<game exe>" [args]. Only the plain .exe
        // form is wrapped; .lnk shortcuts start normally.
        // 에이전트가 게임을 감쌈: steam.exe "<게임 exe>" [인수]. 일반 .exe 형태만
        // 감싸고, .lnk 바로가기는 그대로 실행함
        if (steamAgentActive && shortcut != null) {
            String p = shortcut.path.trim();
            if (p.length() > 1 && p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
            if (!p.toLowerCase(java.util.Locale.ENGLISH).endsWith(".lnk")) {
                String execArgs = shortcut.getExtra("execArgs");
                // argv[1] = spec file, not the exe (see SteamLiteManager.writeSpecFile)
                // argv[1] = exe가 아니라 spec 파일(SteamLiteManager.writeSpecFile 참고)
                int appId = 0;
                try { appId = Integer.parseInt(shortcut.getExtra("steamAppId", "0")); } catch (NumberFormatException ignored) {}
                File driveCForSpec = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c");
                String spec = com.winlator.cmod.steam.SteamLiteManager.writeSpecFile(driveCForSpec, appId, p);
                String launchArg = spec != null ? spec : p;
                if (spec != null) envVars.put("WN_STEAM_GAMEEXE_FILE", spec);
                args = "\"" + com.winlator.cmod.steam.SteamLiteManager.STEAM_DIR_WIN + "\\steam.exe\" \"" + launchArg + "\""
                        + (!execArgs.isEmpty() ? " " + execArgs : "");
            }
        }

        // Construct the final command
        String command = "winhandler.exe " + args;

        return command;
    }
    private String getExecutable() {
        String filename = "";
        if (shortcut != null) {
            filename = FileUtils.getName(shortcut.path);
        }
        else
            // Same program getWineStartCommand() launches without a shortcut (log file name)
            // 바로가기 없이 실행할 때 getWineStartCommand()가 띄우는 것과 같은 프로그램임(로그 파일 이름용)
            filename = useGuestFileManager() ? "wfm.exe" : "explorer.exe";
        return filename;
    }

    // Whether the in-guest file manager (wfm.exe) is used. On X86_64 (box64)
    // containers wfm.exe stops before its main window appears, with no error or
    // exception in the Wine log. It depends on timing: with the relay debug channel
    // on (which only slows execution) the window opens, and the Box64 Stability /
    // Compatibility presets did not change it. Wine's own explorer.exe opens fine
    // there, so X86_64 uses "explorer.exe C:\" instead. ARM64EC keeps wfm.exe.
    // Before wineInfo is known the original behaviour (wfm.exe) is kept.
    // 게스트 파일 관리자(wfm.exe)를 쓸지 정함. X86_64(box64) 컨테이너에서 wfm.exe는
    // 메인 창이 뜨기 전에 멈추고, Wine 로그에 오류나 예외가 없음. 타이밍에 따라
    // 달라짐: 실행만 느리게 만드는 relay 디버그 채널을 켜면 창이 열리고, Box64
    // 안정성 / 호환성 프리셋으로는 바뀌지 않았음. 같은 환경에서 Wine 자체
    // explorer.exe는 정상으로 열리므로 X86_64는 "explorer.exe C:\"로 대신함.
    // ARM64EC는 wfm.exe를 그대로 씀. wineInfo가 정해지기 전에는 원래 동작(wfm.exe)을 유지함
    // Seen only from Proton/Wine 11 on: x86_64 layers below 11 keep wfm.exe too
    // Proton/Wine 11부터만 확인됨: 11 미만의 x86_64 레이어도 wfm.exe를 그대로 씀
    // Version key of the Latest tier's DXVK (dxvk = true) or VKD3D build for this
    // container's architecture, when it is installed; otherwise the given constant
    // 이 컨테이너 아키텍처용 Latest(최신) 단계 DXVK(dxvk = true) 또는 VKD3D 빌드의 버전 키.
    // 설치되어 있지 않으면 주어진 상수를 씀
    private String fallbackComponentKey(boolean dxvk, String constant) {
        try {
            boolean arm = wineInfo != null && wineInfo.isArm64EC();
            com.winlator.cmod.container.LayerTiers.ArchSet set = com.winlator.cmod.container.LayerTiers.TIER_LATEST.arch(arm);
            String key = com.winlator.cmod.container.LayerTiers.versionKey(com.winlator.cmod.container.LayerTiers.installedProfile(
                    this, contentsManager, dxvk ? set.dxvk : set.vkd3d));
            return key != null ? key : constant;
        } catch (Exception e) {
            return constant;
        }
    }

    // One log line with everything that decides how touches reach the game
    // 터치가 게임에 전달되는 방식을 정하는 값들을 한 줄로 남김
    private void logInputState(String when) {
        try {
            ControlsProfile p = inputControlsView.getProfile();
            Log.i("InputState", when
                    + " simTouch=" + touchpadView.isSimTouchScreen()
                    + " mouseDisabled=" + isMouseDisabled
                    + " profile=" + (p != null ? p.getName() : "none")
                    + " controlsVisible=" + (inputControlsView.getVisibility() == View.VISIBLE)
                    + " touchpadVisible=" + (touchpadView.getVisibility() == View.VISIBLE)
                    + " touchpadEnabled=" + touchpadView.isEnabled()
                    + " touchpadFocus=" + touchpadView.hasFocus()
                    + " pointerCapture=" + touchpadView.hasPointerCapture());
        } catch (Exception e) {
            Log.w("InputState", "log failed: " + e);
        }
    }

    private boolean useGuestFileManager() {
        if (wineInfo == null || wineInfo.isArm64EC()) return true;
        try {
            String v = wineInfo.version != null ? wineInfo.version : "";
            int major = Integer.parseInt(v.split("\\.")[0].replaceAll("[^0-9]", ""));
            return major < 11;
        } catch (Exception e) {
            return false;
        }
    }


    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) {
            overrideEnvVars = new EnvVars();
        }
        return overrideEnvVars;
    }

    // BUG FIX (Wine log 2026-09-29 00:11, x86_64 container: only
    // "No driver from L\"pulse\" could be initialized", ALSA never tried, no sound).
    // The registry was written only when the driver differed from the container's
    // remembered value, so a prefix whose user.reg lost or never got the value kept
    // Wine on "pulse". The registry is now checked on every launch and rewritten
    // whenever it does not match the selected driver.
    // 버그 수정(Wine 기록 2026-09-29 00:11, x86_64 컨테이너: "No driver from L\"pulse\"
    // could be initialized"만 나오고 ALSA는 시도조차 안 돼서 소리가 안 났음). 예전엔
    // 드라이버가 컨테이너에 기억된 값과 다를 때만 레지스트리를 썼기 때문에, user.reg에
    // 값이 없거나 사라진 환경은 Wine이 계속 "pulse"를 썼음. 이제 실행할 때마다
    // 레지스트리를 확인하고 선택한 드라이버와 다르면 다시 씀
    private void changeWineAudioDriver() {
        String wanted = audioDriver.startsWith("alsa") ? "alsa" : audioDriver.equals("pulseaudio") ? "pulse" : null;
        if (wanted == null) return;
        File userRegFile = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/user.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            String current = registryEditor.getStringValue("Software\\Wine\\Drivers", "Audio", null);
            if (!wanted.equals(current)) {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", wanted);
                Log.i("XServerDisplayActivity", "Wine audio driver registry " + current + " -> " + wanted);
            }
        }
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    /**
     * Automatic ALSA -> PulseAudio fallback per shortcut.
     * An ALSA session leaves a marker file while its audio stream is open
     * (ALSAClient writes "active", or "failed" if the stream could not open) and a
     * clean exit deletes it. If the marker is still there on the next launch of the
     * same shortcut, the last session crashed with audio open or audio failed, so
     * this shortcut is switched to PulseAudio and the user is told once.
     * Only for arm64ec: on x86_64 the Wine "pulse" driver itself failed to load in
     * the 2026-09-29 log, so switching there would only trade one silence for another.
     * 바로가기별 ALSA -> PulseAudio 자동 전환.
     * ALSA 세션은 오디오 스트림이 열려 있는 동안 표식 파일을 남기고(ALSAClient가
     * "active", 스트림을 못 열었으면 "failed"를 씀), 정상 종료하면 지움. 같은 바로가기의
     * 다음 실행에서 표식이 남아 있으면 지난 세션이 오디오가 열린 채 죽었거나 오디오가
     * 실패한 것이므로, 이 바로가기를 PulseAudio로 바꾸고 사용자에게 한 번 알림.
     * arm64ec에서만 적용함: x86_64는 2026-09-29 기록에서 Wine "pulse" 드라이버 자체가
     * 로드되지 않아서, 바꿔봐야 무음이 다른 무음으로 바뀔 뿐임
     */
    private File audioGuardMarker;

    private void applyAudioFallback() {
        if (shortcut == null || shortcut.file == null) return;
        audioGuardMarker = new File(getFilesDir(), "audio_guard_" + Integer.toHexString(shortcut.file.getAbsolutePath().hashCode()));
        if (audioDriver.startsWith("alsa") && audioGuardMarker.exists()) {
            String state = "";
            try { state = new String(java.nio.file.Files.readAllBytes(audioGuardMarker.toPath())); } catch (Exception ignored) {}
            audioGuardMarker.delete();
            if (wineInfo != null && wineInfo.isArm64EC()) {
                Log.w("XServerDisplayActivity", "Audio guard left behind (" + state + ") - switching this shortcut to PulseAudio");
                audioDriver = "pulseaudio";
                shortcut.putExtra("audioDriver", "pulseaudio");
                shortcut.saveData();
                runOnUiThread(() -> android.widget.Toast.makeText(this, R.string.audio_fallback_notice, android.widget.Toast.LENGTH_LONG).show());
            } else {
                Log.w("XServerDisplayActivity", "Audio guard left behind (" + state + ") on x86_64 - PulseAudio unavailable there, keeping ALSA");
            }
        }
        com.winlator.cmod.alsaserver.ALSAClient.setAudioGuardMarker(audioDriver.startsWith("alsa") ? audioGuardMarker : null);
    }

    // Clean end of a session: the guard must not survive / 정상 종료: 감시 표식을 남기지 않음
    private void clearAudioGuard() {
        com.winlator.cmod.alsaserver.ALSAClient.setAudioGuardMarker(null);
        if (audioGuardMarker != null && audioGuardMarker.exists()) {
            String state = "";
            try { state = new String(java.nio.file.Files.readAllBytes(audioGuardMarker.toPath())); } catch (Exception ignored) {}
            // "failed" is kept on purpose: audio never worked, so switch next time
            // "failed"는 일부러 남김: 오디오가 아예 안 됐으니 다음 실행에 전환함
            if (!"failed".equals(state)) audioGuardMarker.delete();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern_common.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void changeFrameRatingVisibility(Window window, Property property) {
        // MangoHud draws the HUD itself - never show the second overlay
        // MangoHud가 직접 그리므로 두 번째 오버레이는 절대 띄우지 않음
        if (frameRating == null || mangoHudActive) return;

        if (property != null) {
            if (frameRatingWindowId == -1 && (property.nameAsString().contains("_UTIL_LAYER") || property.nameAsString().contains("_MESA_DRV"))) {
                frameRatingWindowId = window.id;
                // The HUD window is the game's render surface: fullscreen crops to it
                // HUD 창이 게임의 렌더링 면임: 전체화면은 이 창에 맞춰 잘라 냄
                xServerView.getRenderer().preferredFullscreenWindowId = window.id;
                Log.d("XServerDisplayActivity", "Showing hud for Window " + window.getName());
                frameRating.update();
            }
            if (property.nameAsString().contains("_UTIL_LAYER_ENGINE_NAME")) {
                frameRating.setRenderer(property.toString());
            }
            if (property.nameAsString().contains("_UTIL_LAYER_GPU_NAME")) {
                frameRating.setGpuName(property.toString());
            }
        }
        else if (frameRatingWindowId != -1) {
            frameRatingWindowId = -1;
            Log.d("XServerDisplayActivity", "Hiding hud for Window " + window.getName());
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
            frameRating.reset();
        }
    }

    private void scheduleSecondaryExecution(String secondaryExec, int delaySeconds) {
        if (winHandler != null) {
            winHandler.execWithDelay(secondaryExec, delaySeconds);
            Log.d("XServerDisplayActivity", "Scheduled secondary execution: " + secondaryExec + " with delay: " + delaySeconds);
        } else {
            Log.e("XServerDisplayActivity", "WinHandler is null, cannot schedule secondary execution.");
        }
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }


    private void setupAudioDeviceListener() {
        // Get the Android AudioManager system service
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Create the callback instance
        audioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                if (environment != null) {
                    ALSAServerComponent alsaComponent = environment.getComponent(ALSAServerComponent.class);
                    if (alsaComponent != null) {
                        Log.d("AudioDeviceCallback", "Audio device added. Triggering rebuild.");
                        alsaComponent.notifyAudioDeviceChanged();
                    }
                }
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                if (environment != null) {
                    ALSAServerComponent alsaComponent = environment.getComponent(ALSAServerComponent.class);
                    if (alsaComponent != null) {
                        Log.d("AudioDeviceCallback", "Audio device removed. Triggering rebuild.");
                        alsaComponent.notifyAudioDeviceChanged();
                    }
                }
            }
        };

        // Register the callback with the system.
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, new Handler(Looper.getMainLooper()));
    }
}
