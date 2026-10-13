package com.winlator.cmod;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ControllerAssignmentDialog;
import com.winlator.cmod.contentdialog.SaveEditDialog;
import com.winlator.cmod.contentdialog.SaveSettingsDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.saves.Save;
import com.winlator.cmod.saves.SaveManager;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    // Caps Kotlin's Dispatchers.IO at 16 threads. JavaSteam's depot
    // downloader decompresses on Dispatchers.IO and each worker thread keeps an
    // 8MB ThreadLocal window buffer (VZipUtil); with the default cap of 64
    // threads that alone could reach 512MB, and the logcat of 2026-10-02 shows
    // OutOfMemoryError at VZipUtil.windowBufferPool on the 256MB heap.
    // 16 threads -> at most 128MB of buffers. Must run before Dispatchers.IO is
    // first used, so it sits in the launcher activity's static initializer.
    // Kotlin Dispatchers.IO를 최대 16스레드로 제한함. JavaSteam의 depot
    // 다운로더는 Dispatchers.IO에서 압축을 풀고, 작업 스레드마다 8MB ThreadLocal
    // 버퍼(VZipUtil)를 가짐. 기본 상한 64스레드면 이것만으로 512MB까지 갈 수 있고,
    // 2026-10-02 로그캣에 256MB 힙에서 VZipUtil.windowBufferPool OutOfMemoryError가
    // 찍혀 있음. 16스레드면 버퍼는 최대 128MB임. Dispatchers.IO를 처음 쓰기 전에
    // 실행되어야 하므로 런처 액티비티의 static 초기화 블록에 둠
    static {
        if (System.getProperty("kotlinx.coroutines.io.parallelism") == null) {
            System.setProperty("kotlinx.coroutines.io.parallelism", "16");
        }
    }

    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    private DrawerLayout drawerLayout;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;

    // Add SaveSettingsDialog and SaveEditDialog instances
    private SaveSettingsDialog saveSettingsDialog;
    private SaveEditDialog saveEditDialog;
    private SaveManager saveManager;
    private ContainerManager containerManager;
    // First-run guide ("I know nothing" / "I've done this before"). Non-null
    // only while it is on screen; permission results are routed to it then
    // 첫 실행 안내창("아무것도 몰라요" / "해본적 있어요")임. 화면에 떠 있는 동안만
    // null이 아니고, 그동안 권한 결과는 이 창으로 보냄
    private FirstRunGuide firstRunGuide;

    // Download plans the guide can start
    // 안내창이 시작할 수 있는 다운로드 방식
    public static final int FIRST_RUN_EASY = 0;       // everything + auto container / 전부 + 컨테이너 자동 생성
    public static final int FIRST_RUN_ESSENTIAL = 1;  // system files only / 시스템 파일만
    public static final int FIRST_RUN_ALL = 2;        // everything, container asked / 전부, 컨테이너는 물어봄


    private boolean isDarkMode;

    private Callback<Uri> openFileCallback; //imagepickerview.java에 쓰임

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Steam download housekeeping: resume a download paused for a game (the
        // game exit restarts this activity) and delete a stopped one's folder
        // 스팀 다운로드 정리: 게임 때문에 멈춘 다운로드를 이어 받고(게임 종료가 이
        // 액티비티를 다시 시작함), 정지한 다운로드의 폴더를 지움
        try {
            com.winlator.cmod.steam.SteamDownloadState.housekeeping(this);
        } catch (Exception e) {
            Log.w("MainActivity", "steam download housekeeping failed", e);
        }
        // Orphan sweep: a fresh main process means no game is running
        // here, so any guest process still alive is left over from an earlier
        // session (e.g. the steam.exe at ~700% CPU in logcat 2026-10-02 22:01~22:55)
        // 남은 프로세스 정리: 메인 프로세스가 새로 떴다는 건 여기서 도는 게임이
        // 없다는 뜻이므로, 살아 있는 게스트 프로세스는 이전 세션의 잔류임(예: 로그캣
        // 2026-10-02 22:01~22:55에서 CPU 약 700%를 쓰던 steam.exe)
        if (savedInstanceState == null) {
            new Thread(() -> com.winlator.cmod.core.ProcessHelper.terminateAllGuestProcesses("startup-orphans"),
                    "OrphanSweep").start();
        }

        // Initialize the controller management system
        ControllerManager.getInstance().init(getApplicationContext());


        // Load the user's preferred theme
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        isDarkMode = sharedPreferences.getBoolean("dark_mode", false);

        // Apply the theme based on the preference
        if (isDarkMode) {
            setTheme(R.style.Theme_App_Neon_Dark);
        } else {
            setTheme(R.style.Theme_App_Neon_Light);
        }


        setContentView(R.layout.main_activity);
        // Dark mode: the navigation bar takes the app background, so only the white
        // gesture handle shows - the same look as the home screen
        // 다크 모드: 내비게이션 바를 앱 배경색으로 칠해서 흰 제스처 줄만 보이게 함 -
        // 홈 화면과 같은 모양
        if (isDarkMode) {
            android.util.TypedValue bg = new android.util.TypedValue();
            getTheme().resolveAttribute(R.attr.neonBg, bg, true);
            int color = bg.resourceId != 0 ? androidx.core.content.ContextCompat.getColor(this, bg.resourceId) : bg.data;
            getWindow().setNavigationBarColor(color);
            if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        }

        drawerLayout = findViewById(R.id.DrawerLayout);
        NavigationView navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);

        setSupportActionBar(findViewById(R.id.Toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.neon_ic_toolbar_menu);
        }

        // Removed: this forced every drawer label to flat black/white right
        // after inflation, overwriting app:itemTextColor (neon_drawer_text_tint)
        // from main_activity.xml - which is why the selected "Shortcuts" row
        // kept black text instead of the accent colour. The state list in the
        // layout already handles both themes. Same bug class as the in-game
        // drawer override removed from XServerDisplayActivity.
        // 제거함: 인플레이트 직후 드로어 글자를 전부 검정/흰색으로 강제해서
        // main_activity.xml의 app:itemTextColor(neon_drawer_text_tint)를
        // 덮어쓰고 있었음 - 선택된 "Shortcuts" 행 글자가 강조색이 아니라
        // 검정으로 남던 이유임. 레이아웃의 상태 목록이 이미 두 테마를 다
        // 처리함. XServerDisplayActivity에서 지운 인게임 드로어 강제 코드와
        // 같은 종류의 버그임
        

        // Initialize SaveManager and ContainerManager
        saveManager = new SaveManager(this);
        containerManager = new ContainerManager(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            actionBar.setHomeAsUpIndicator(R.drawable.neon_ic_toolbar_back);
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.main_menu_input_controls));
            navigationView.setCheckedItem(R.id.main_menu_input_controls);
        } else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : R.id.main_menu_shortcuts;

            actionBar.setHomeAsUpIndicator(R.drawable.neon_ic_toolbar_menu);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);

            // Request storage permissions, then install system files, then
            // check for bundled/downloaded contents, then show the recommended
            // contents dialog on first run - all chained together.
            // 저장소 권한 요청 → 시스템 파일 설치 → 번들/다운로드된 컨텐츠 확인 →
            // 첫 실행 시 추천 구성요소 다이얼로그, 이 순서로 전부 연결 일괄처리
            // Fresh install (no system files yet): the first-run guide takes
            // over - it explains, asks for the permission and starts the
            // download itself. Updates of an existing install keep the old path
            // 새로 설치한 경우(시스템 파일 없음): 첫 실행 안내창이 넘겨받음 - 설명,
            // 권한 요청, 다운로드 시작을 안내창이 직접 함. 기존 설치의 업데이트는
            // 예전 경로를 그대로 탐
            if (!com.winlator.cmod.xenvironment.ImageFs.find(this).isValid()) {
                firstRunGuide = new FirstRunGuide(this, () -> firstRunGuide = null);
                firstRunGuide.show();
            } else {
                boolean waitingForPerms = requestAppPermissions();
                if (!waitingForPerms) {
                    ImageFsInstaller.installIfNeeded(this, () -> checkForAndInstallAssetContents(null)); //통합 소스코드
                }
            }
        }
    }

    // ==================== First-run guide hooks ====================
    // ==================== 첫 실행 안내창 연결부 ====================

    public boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    // Shows the system permission prompt; the answer arrives in
    // onRequestPermissionsResult(), which hands it to the guide
    // 시스템 권한 요청창을 띄움. 답은 onRequestPermissionsResult()로 오고,
    // 거기서 안내창에 넘김
    public void requestStoragePermission() {
        requestAppPermissions();
    }

    // System files first (imagefs), then bundled asset contents, then the
    // part that depends on the plan the user picked in the guide
    // 시스템 파일(imagefs) 먼저, 그다음 번들 asset 구성요소, 그다음 안내창에서
    // 고른 방식에 따라 달라지는 부분을 진행함
    public void startFirstRunDownload(int plan) {
        ImageFsInstaller.installFromAssets(this, () -> checkForAndInstallAssetContents(() -> {
            if (plan == FIRST_RUN_ESSENTIAL) {
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean("recommended_contents_installed", true).apply();
                showGoToComponentsPrompt();
            } else {
                installRecommendedContentsInBackground(plan == FIRST_RUN_EASY);
            }
        }));
    }

    // After "essentials only": offer a direct jump to the components screen,
    // where Proton, DXVK/VKD3D and drivers are picked by hand
    // "필수만 받기" 뒤에 구성요소 화면으로 바로 가는 길을 제시함. Proton,
    // DXVK/VKD3D, 드라이버는 거기서 직접 골라 받음
    private void showGoToComponentsPrompt() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.first_run_essential_done_title)
                .setMessage(R.string.first_run_essential_done_message)
                .setCancelable(false)
                .setPositiveButton(R.string.first_run_go_components, (d, w) -> openComponentsScreen())
                .setNegativeButton(R.string.first_run_later, null)
                .show();
    }

    // Same as picking the drawer entry
    // 드로어 항목을 누른 것과 같음
    public void openComponentsScreen() {
        NavigationView navigationView = findViewById(R.id.NavigationView);
        MenuItem item = navigationView.getMenu().findItem(R.id.main_menu_contents);
        onNavigationItemSelected(item);
        navigationView.setCheckedItem(R.id.main_menu_contents);
    }
    /**
     * Install *.wcp bundles from assets/contents/ that are not already
     * unpacked in /files/contents/<Type>/<verDir>/.
     *
     * Handles case differences and the fact that install dirs end with
     * “-<verCode>” while the .wcp filename may omit that suffix.
     */
    /**
     * Scan assets/contents/ for *.wcp bundles and install the ones that do
     * not already exist in /files/contents/<Type>/<verDir>/.
     *
     * Handles the following real-world quirks:
     *   • Folder name may be "type-verDir-<code>" (duplicated type prefix)
     *   • .wcp may have a leading 'v' in the version that the folder omits
     *   • '-' and '_' are treated as equivalent separators
     *   • Case insensitive everywhere
     *
     * The PreloaderDialog appears only when an install actually starts.
     */
    /**
     * 함수 주요 목적: 앱 내부 assets/contents/ 경로의 *.wcp 압축 파일들을 스캔하여, 기기 내 저장소(/files/contents/<Type>/<verDir>/)에 아직 존재하지 않는 항목들만 선별해 자동 설치합니다.
     * 주요 예외 처리(Quirks) 및 매칭 로직:
     * 중복 타입 접두사 처리: 설치 디렉터리 이름이 type-verDir-<code> 형태로 타입명이 중복 포함되는 경우를 대응합니다.
     * 버전 표기 정규화: .wcp 파일명에 포함된 버전의 선행 문자 v(예: v1.12.0)와 폴더명의 생략된 버전 표기 간의 차이를 보정합니다.
     * 구분자 및 대소문자 무시: 하이픈(-)과 언더바(_)를 동일한 구분자로 취급하며, 전체 경로와 파일명을 대소문자 구분 없이 비교합니다.
     * UI 동작 방식: 실제 파일 설치(압축 해제) 프로세스가 시작되는 시점에만 PreloaderDialog가 활성화되어 화면에 표시됩니다.
      **/
    // Components downloaded automatically once, on first launch, so the app
// works well out of the box without the user needing to browse Contents.
// 앱을 처음 켤 때 한 번 자동으로 다운로드되는 구성요소들.
// 사용자가 Contents 화면을 직접 뒤지지 않아도 바로 잘 돌아가게 하기 위함
    // First-run downloads: the Latest layer tier, both architectures. The list,
    // its sources and fallbacks live in LayerTiers so the first run and the
    // Containers screen tier buttons install exactly the same builds.
    // Entries are {label, primary URL, fallback URL...}.
    // 첫 실행 다운로드: Latest(최신) 레이어 단계, 두 아키텍처. 목록·출처·대체 주소는
    // LayerTiers에 있어서 첫 실행과 컨테이너 화면의 단계 버튼이 완전히 같은 빌드를
    // 설치함. 항목은 {표시 이름, 기본 주소, 대체 주소...}임
    private static final String[][] RECOMMENDED_CONTENTS =
            com.winlator.cmod.container.LayerTiers.firstRunContents();


    // Graphics driver for this device on first run (Latest tier): fixed, checked
    // StevenMXZ / ExynosTools builds picked by GPU, see TurnipPicker. null = the
    // device driver is used as it is (Adreno 710/720/722, Mali, unknown GPUs)
    // 첫 실행 때 이 기기에 넣을 그래픽 드라이버(Latest 단계): GPU별로 고른, 확인한
    // StevenMXZ / ExynosTools 고정 빌드임. TurnipPicker 참고. null = 기기 드라이버를
    // 그대로 씀(Adreno 710/720/722, Mali, 알 수 없는 GPU)
    @Nullable
    private String[] pickGraphicsDriverForDevice() {
        String[] pick = com.winlator.cmod.contents.TurnipPicker.pick(GPUInformation.getRenderer(), android.os.Build.MANUFACTURER);
        Log.i("MainActivity", "[recommended] GPU " + GPUInformation.getRenderer() + " -> "
                + (pick != null ? pick[0] : "device driver"));
        return pick;
    }

    private void checkForAndInstallAssetContents(@Nullable Runnable onCompletion) {
        Log.d("MainActivity", "[contents] checkForAndInstallAssetContents 시작");

        Executors.newSingleThreadExecutor().execute(() -> {
            PreloaderDialog spinner = new PreloaderDialog(this);
            boolean spinnerShown = false;
            boolean completionCalled = false;

            try {
                Map<String, Set<String>> installed = new HashMap<>();
                File root = ContentsManager.getContentDir(this);

                File[] typeDirs = root.listFiles();
                if (typeDirs != null) {
                    for (File typeDir : typeDirs) {
                        if (!typeDir.isDirectory()) continue;
                        String typeKey = typeDir.getName().toLowerCase();
                        Set<String> vers = new HashSet<>();
                        File[] verDirs = typeDir.listFiles();
                        if (verDirs != null) {
                            for (File verDir : verDirs) {
                                if (!verDir.isDirectory()) continue;
                                String cleaned = verDir.getName().toLowerCase().replace('_', '-');
                                String dupPrefix = typeKey + "-";
                                if (cleaned.startsWith(dupPrefix)) cleaned = cleaned.substring(dupPrefix.length());
                                vers.add(cleaned);
                            }
                        }
                        installed.put(typeKey, vers);
                    }
                }

                String[] assetNames = getAssets().list("contents");
                if (assetNames == null) assetNames = new String[0];
                Log.d("MainActivity", "[contents] assets/contents 안 파일 개수: " + assetNames.length);

                List<String> toInstall = new ArrayList<>();
                for (String asset : assetNames) {
                    if (!asset.endsWith(".wcp")) continue;
                    String base = asset.substring(0, asset.length() - 4);
                    int sep = Math.min(
                            base.indexOf('-') == -1 ? Integer.MAX_VALUE : base.indexOf('-'),
                            base.indexOf('_') == -1 ? Integer.MAX_VALUE : base.indexOf('_'));
                    if (sep == Integer.MAX_VALUE) continue;

                    String typeKey = base.substring(0, sep).toLowerCase();
                    String verRaw = base.substring(sep + 1);
                    String verNorm = verRaw.toLowerCase().replace('_', '-');
                    String verNoV = verNorm.startsWith("v") ? verNorm.substring(1) : verNorm;

                    Set<String> vers = installed.get(typeKey);
                    boolean exists = false;
                    if (vers != null) {
                        for (String dir : vers) {
                            if (dir.equals(verNorm) || dir.equals(verNoV)
                                    || dir.startsWith(verNorm + "-") || dir.startsWith(verNoV + "-")) {
                                exists = true;
                                break;
                            }
                        }
                    }
                    if (!exists) toInstall.add(asset);
                }

                Log.d("MainActivity", "[contents] 새로 설치할 항목 개수: " + toInstall.size());

                if (!toInstall.isEmpty()) {
                    ContentsManager cm = new ContentsManager(this);
                    File tmpDir = new File(getCacheDir(), "wcp_asset_tmp");
                    if (!tmpDir.exists()) tmpDir.mkdirs();

                    for (String asset : toInstall) {
                        if (!spinnerShown) {
                            spinnerShown = true;
                            runOnUiThread(() -> spinner.show(R.string.installing_contents));
                        }
                        Log.d("MainActivity", "[contents] 설치 시도: " + asset);

                        File tmp = new File(tmpDir, asset);
                        try (InputStream in = getAssets().open("contents/" + asset);
                             OutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        } catch (Exception copyEx) {
                            Log.e("MainActivity", "[contents] 복사 실패: " + asset, copyEx);
                            continue;
                        }

                        ContentsManager.OnInstallFinishedCallback cb =
                                new ContentsManager.OnInstallFinishedCallback() {
                                    private boolean first = true;
                                    @Override public void onSucceed(ContentProfile p) {
                                        if (first) { first = false; cm.finishInstallContent(p, this); }
                                    }
                                    @Override public void onFailed(ContentsManager.InstallFailedReason r, Exception e) {
                                        Log.e("MainActivity", "[contents] 설치 실패: " + asset + " (" + r + ")", e);
                                    }
                                };
                        cm.extraContentFile(Uri.fromFile(tmp), cb);
                        tmp.delete();
                    }
                }
            } catch (Exception e) {
                Log.e("MainActivity", "[contents] 전체 실패", e);
            } finally {
                final boolean shown = spinnerShown;
                if (!completionCalled) {
                    completionCalled = true;
                    Log.d("MainActivity", "[contents] 완료 콜백 실행");
                    runOnUiThread(() -> {
                        if (shown) spinner.close();
                        if (onCompletion != null) onCompletion.run();
                    });
                }
            }
        });
    }

    // Byte-progress listener that forwards to the dialog at most every 100 ms.
    // Downloader reports after every 1 KiB read; posting each of those to the
    // UI thread would queue hundreds of thousands of updates for a large file.
    // The final call (downloaded == total) always goes through so the bar
    // visibly reaches 100% before the next item starts.
    // 다이얼로그로 최대 100ms마다 한 번씩만 넘기는 바이트 진행률 리스너임.
    // Downloader는 1 KiB 읽을 때마다 보고하는데, 그걸 전부 UI 스레드로 보내면
    // 큰 파일 하나에 수십만 번의 갱신이 쌓임. 마지막 호출(downloaded == total)은
    // 항상 통과시켜서, 다음 항목이 시작되기 전에 막대가 100%까지 차는 게 보이게 함
    private Downloader.ProgressListener throttledFileProgress(DownloadProgressDialog dialog) {
        final long[] lastPost = {0L};
        return (downloaded, total) -> {
            long now = System.currentTimeMillis();
            boolean finished = total > 0 && downloaded >= total;
            if (!finished && now - lastPost[0] < 100) return;
            lastPost[0] = now;
            runOnUiThread(() -> dialog.setFileProgress(downloaded, total));
        };
    }

    public void installRecommendedContentsInBackground() {
        installRecommendedContentsInBackground(false);
    }

    // autoCreateContainer: true = make the default container without asking
    // ("I know nothing" path); false = ask once at the end as before
    // autoCreateContainer: true = 묻지 않고 기본 컨테이너를 만듦("아무것도
    // 몰라요" 경로), false = 예전처럼 마지막에 한 번 물어봄
    public void installRecommendedContentsInBackground(boolean autoCreateContainer) {
        Log.d("MainActivity", "[recommended] 다운로드 시작");
        ContentsManager manager = new ContentsManager(this);
        DownloadProgressDialog progressDialog = new DownloadProgressDialog(this);
        runOnUiThread(() -> progressDialog.show(R.string.installing_content));

        Executors.newSingleThreadExecutor().execute(() -> {
            // 그래픽 드라이버까지 포함한 전체 개수로 퍼센트 계산 (게이지가 처음부터 끝까지 한 번에 감)
            String[] driverEntry = pickGraphicsDriverForDevice();
            int totalSteps = RECOMMENDED_CONTENTS.length + (driverEntry != null ? 1 : 0);
            int completedSteps = 0;

            for (String[] item : RECOMMENDED_CONTENTS) {
                String label = item[0];
                String url = item[1];
                final int stepIndexForLabel = completedSteps;
                try {
                    Log.d("MainActivity", "[recommended] 다운로드 중: " + label + " <- " + url);
                    runOnUiThread(() -> {
                        // Name on the title line, position on the overall line,
                        // current file's bar reset to 0 for the new item.
                        // 제목 줄엔 이름, 전체 줄엔 순번, 현재 파일 막대는 새
                        // 항목을 위해 0으로 되돌림
                        progressDialog.setLabel(label);
                        progressDialog.setProgress(0);
                        progressDialog.setOverall(stepIndexForLabel + 1, stepIndexForLabel, totalSteps);
                        progressDialog.setFileStatus("연결 중…");
                    });

                    File temp = new File(getCacheDir(), "rec_temp_" + System.nanoTime());
                    boolean ok = Downloader.downloadFile(url, temp, throttledFileProgress(progressDialog));
                    // First URL unreachable: try the entry's fallback URL, if it has one
                    // 첫 주소를 받지 못함: 항목에 대체 주소가 있으면 그것을 시도함
                    for (int f = 2; !ok && f < item.length; f++) {
                        Log.w("MainActivity", "[recommended] " + label + " failed, trying fallback " + item[f]);
                        ok = Downloader.downloadFile(item[f], temp, throttledFileProgress(progressDialog));
                    }
                    if (!ok) { completedSteps++; continue; }

                    runOnUiThread(() -> progressDialog.setFileStatus("설치 중…"));

                    manager.extraContentFile(Uri.fromFile(temp), new ContentsManager.OnInstallFinishedCallback() {
                        private boolean first = true;

                        @Override
                        public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                            Log.w("MainActivity", "설치 실패: " + label + " (" + reason + ")");
                        }
                        @Override
                        public void onSucceed(ContentProfile profile) {
                            if (first) {
                                first = false;
                                // Remember which installed entry this URL became, so the
                                // tier buttons and automatic containers use this exact build
                                // 이 주소가 어떤 설치 항목이 됐는지 기록함. 단계 버튼과
                                // 자동 컨테이너가 정확히 이 빌드를 쓰게 하기 위함
                                com.winlator.cmod.container.LayerTiers.record(MainActivity.this, url, profile);
                                manager.finishInstallContent(profile, this);
                            }
                        }
                    });
                    temp.delete();
                } catch (Exception e) {
                    Log.e("MainActivity", "[recommended] 처리 중 예외: " + label, e);
                }
                completedSteps++;
            }


            if (driverEntry != null) {
                String label = driverEntry[0];
                String url = driverEntry[1];
                final int stepIndexForLabel = completedSteps;
                try {
                    runOnUiThread(() -> {
                        progressDialog.setLabel(label);
                        progressDialog.setProgress(0);
                        progressDialog.setOverall(stepIndexForLabel + 1, stepIndexForLabel, totalSteps);
                        progressDialog.setFileStatus("연결 중…");
                    });
                    File driverTemp = new File(getCacheDir(), "driver_temp_" + System.nanoTime() + ".zip");
                    boolean ok = Downloader.downloadFile(url, driverTemp, throttledFileProgress(progressDialog));
                    if (ok) {
                        runOnUiThread(() -> progressDialog.setFileStatus("설치 중…"));
                        AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(this);
                        String installedDriver = adrenotoolsManager.installDriver(Uri.fromFile(driverTemp));
                        // Install failed: nothing is set, so the device driver stays in use
                        // 설치 실패: 아무것도 지정하지 않으므로 기기 드라이버가 그대로 쓰임
                        if (installedDriver == null || installedDriver.isEmpty()) {
                            Log.w("MainActivity", "[recommended] driver install failed, device driver stays: " + label);
                            driverTemp.delete();
                            throw new IllegalStateException("driver install failed");
                        }
                        // Auto-installed drivers come from the recommended source, so they
                        // list under that sub-tab in Contents > GraphicTools
                        // 자동 설치 드라이버는 권장 배포처 것이므로 Contents > GraphicTools의
                        // 해당 하위 탭에 나오게 기록함
                        new ContentsManager(this).setDriverProvider(installedDriver,
                                com.winlator.cmod.contents.ContentProfile.guessProvider(url));
                        // Newest downloaded driver becomes the automatic default
                        // 가장 최근에 받은 드라이버를 자동 기본값으로 씀
                        com.winlator.cmod.core.DriverAutoSelect.onDriverInstalled(this, installedDriver);
                    }
                    driverTemp.delete();
                } catch (Exception e) {
                    Log.e("MainActivity", "[recommended] 그래픽 드라이버 설치 실패: " + label, e);
                }
            }

            runOnUiThread(() -> {
                progressDialog.setProgress(100);
                progressDialog.setOverall(totalSteps, totalSteps, totalSteps);
                progressDialog.closeOnUiThread();
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean("recommended_contents_installed", true).apply();
                AppUtils.showToast(this, "추천 구성요소 설치 완료");
                if (autoCreateContainer) {
                    // Skipped when a container already exists (e.g. imagefs reinstall)
                    // 컨테이너가 이미 있으면 건너뜀(예: imagefs 재설치)
                    // Both layers: ARM64EC ("container 1") and X86_64. Existing
                    // containers are reused, so a reinstall makes nothing new
                    // 두 레이어 모두 만듦: ARM64EC("1번 컨테이너")와 X86_64. 기존
                    // 컨테이너는 그대로 쓰므로 재설치 때는 새로 만들지 않음
                    Executors.newSingleThreadExecutor().execute(() -> {
                        com.winlator.cmod.container.Container arm = new ContainerManager(this).ensureDefaultContainers(manager);
                        runOnUiThread(() -> AppUtils.showToast(this, arm != null
                                ? getString(R.string.first_run_container_created)
                                : getString(R.string.neon_no_wine_installed)));
                    });
                    Log.d("MainActivity", "[recommended] 전체 완료 (컨테이너 자동)");
                    return;
                }
                // Ask once whether to auto-create a default container, so the user
                // can start adding games immediately without a separate setup step.
                // 컨테이너를 자동으로 만들지 한 번 물어봐서, 사용자가 별도 설정 없이
                // 바로 게임을 추가할 수 있게 함
                new AlertDialog.Builder(this)
                        .setTitle("기본 컨테이너 생성")
                        .setMessage("게임을 바로 추가할 수 있도록 기본 컨테이너를 만들까요? (그래픽 드라이버: 가장 최근에 받은 드라이버가 자동 적용되며, 나중에 게임별로 바꿀 수 있습니다)")
                        .setPositiveButton("만들기", (d, w) -> {
                            ContainerManager cm = new ContainerManager(this);
                            cm.createDefaultContainerAsync(manager, newContainer ->
                                    AppUtils.showToast(this, "기본 컨테이너가 생성되었습니다"));
                        })
                        .setNegativeButton("나중에", null)
                        .show();
                Log.d("MainActivity", "[recommended] 전체 완료");
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            // While the first-run guide is up it owns the install chain
            // 첫 실행 안내창이 떠 있는 동안은 설치 흐름을 그 창이 맡음
            if (firstRunGuide != null) {
                firstRunGuide.onPermissionResult(granted);
                return;
            }
            if (granted) {
                // 수정
                ImageFsInstaller.installIfNeeded(this, () -> checkForAndInstallAssetContents(null));

            }
        }
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d("WinActivity", "onActivityResult called with requestCode: " + requestCode + " and resultCode: " + resultCode);

        if (saveSettingsDialog != null && saveSettingsDialog.isShowing()) {
            Log.d("WinActivity", "Forwarding result to SaveSettingsDialog");
            saveSettingsDialog.onActivityResult(requestCode, resultCode, data);
        } else if (saveEditDialog != null && saveEditDialog.isShowing()) {
            Log.d("WinActivity", "Forwarding result to SaveEditDialog");
            saveEditDialog.onActivityResult(requestCode, resultCode, data);
        } else {
            Log.d("WinActivity", "No dialog found for request code: " + requestCode);
        }
    }


    // Method to show SaveEditDialog
    public void showSaveEditDialog(Save saveToEdit) {
        saveEditDialog = new SaveEditDialog(this, saveManager, containerManager, saveToEdit);

        // neon_popup_bg reads ?attr/neonSurface, which already resolves per
        // light/dark theme, so no isDarkMode branch is needed here anymore
        // neon_popup_bg는 ?attr/neonSurface를 참조해서 라이트/다크 테마에
        // 따라 알아서 결정되므로 여기서 isDarkMode 분기가 더 이상 필요 없음
        saveEditDialog.getWindow().setBackgroundDrawableResource(R.drawable.neon_popup_bg);

        saveEditDialog.show();
    }

    public void onSaveAdded() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
        if (currentFragment instanceof SavesFragment) {
            ((SavesFragment) currentFragment).refreshSavesList();
        }
    }

    // BUG FIX: this was leftover from an older app layout where Containers
    // was the home screen. It always sent back-press to Containers no matter
    // which screen was showing, and exited the app if Containers itself was
    // already visible - so from Shortcuts, back went to Containers, and from
    // Containers, back closed the app instead of returning to Shortcuts.
    // Shortcuts is the home screen now (see the drawer's default selection),
    // so back should return there from anywhere else, and exit only when
    // already on Shortcuts. The backstack check up front is also new: without
    // it, back pressed while inside a pushed screen (e.g. Containers' "Add"
    // flow, which uses addToBackStack) skipped that pop entirely and jumped
    // straight to this logic instead.
    // 버그 수정: Containers가 홈 화면이던 예전 레이아웃의 잔재였음. 어느
    // 화면에 있든 뒤로가기를 무조건 Containers로 보냈고, Containers가 이미
    // 떠 있으면 앱을 종료시켰음 - 그래서 Shortcuts에서 뒤로가면 Containers로
    // 갔고, Containers에서 뒤로가면 Shortcuts로 안 가고 앱이 꺼졌음.
    // 이제 Shortcuts가 홈 화면이므로(드로어 기본 선택 참고) 다른 화면에서는
    // 뒤로가기가 거기로 가야 하고, 이미 Shortcuts일 때만 종료돼야 함. 앞에 둔
    // 백스택 체크도 새로 추가함 - 이게 없으면 push된 화면(예: addToBackStack을
    // 쓰는 Containers의 "추가" 흐름) 안에서 뒤로가기를 눌러도 그 pop을 건너뛰고
    // 바로 이 로직으로 넘어갔음
    @Override
    public void onBackPressed() {
        FragmentManager fragmentManager = getSupportFragmentManager();

        if (fragmentManager.getBackStackEntryCount() > 0) {
            super.onBackPressed();
            return;
        }

        List<Fragment> fragments = fragmentManager.getFragments();
        for (Fragment fragment : fragments) {
            if (fragment instanceof ShortcutsFragment && fragment.isVisible()) {
                finish();
                return;
            }
        }

        show(new ShortcutsFragment(), true);
    }


    public void setOpenFileCallback(Callback<Uri> openFileCallback) {   // ← 이 메서드 추가
        this.openFileCallback = openFileCallback;
    }

    private boolean requestAppPermissions() {
        boolean hasWritePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasReadPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        if (hasWritePermission && hasReadPermission) {
            return false; // All permissions are granted
        }

        if (!hasWritePermission || !hasReadPermission) {
            String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        }

        return true; // Permissions are still being requested
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            // Toggle the drawer
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        } else if (menuItem.getItemId() == R.id.saves_menu_add) {
            // Check if we are editing a save
            Intent intent = getIntent();
            int editSaveId = intent.getIntExtra("edit_save_id", -1);
            Save saveToEdit = editSaveId >= 0 ? saveManager.getSaveById(editSaveId) : null;

            // Create and show SaveEditDialog or SaveSettingsDialog as appropriate
            if (saveToEdit != null) {
                // Ensure previous dialog is dismissed before showing a new one
                if (saveEditDialog != null && saveEditDialog.isShowing()) {
                    saveEditDialog.dismiss();
                }
                showSaveEditDialog(saveToEdit); // Use the correct method to show SaveEditDialog
            } else {
                saveSettingsDialog = new SaveSettingsDialog(this, saveManager, containerManager);

                // neon_popup_bg reads ?attr/neonSurface, which already resolves per
                // light/dark theme, so no isDarkMode branch is needed here anymore
                // neon_popup_bg는 ?attr/neonSurface를 참조해서 라이트/다크 테마에
                // 따라 알아서 결정되므로 여기서 isDarkMode 분기가 더 이상 필요 없음
                saveSettingsDialog.getWindow().setBackgroundDrawableResource(R.drawable.neon_popup_bg);

                saveSettingsDialog.show();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(menuItem);
        }
    }


    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        switch (item.getItemId()) {
            case R.id.main_menu_shortcuts:
                show(new ShortcutsFragment(), false);  // Forward animation
                break;
            case R.id.main_menu_containers:
                show(new ContainersFragment(), false);  // Forward animation
                break;
            case R.id.main_menu_input_controls:
                show(new InputControlsFragment(selectedProfileId), false);  // Forward animation
                break;
            case R.id.main_menu_contents:
                show(new ContentsFragment(), false);  // Forward animation
                break;
            // main_menu_adrenotools_gpu_drivers removed: driver install, the
            // installed list and removal all live in Contents > GraphicTools now
            // (ContentsManager adds installed AdrenoTools drivers to that list),
            // so AdrenotoolsFragment.java was deleted.
            // main_menu_adrenotools_gpu_drivers를 지움: 드라이버 설치, 설치 목록,
            // 삭제가 모두 Contents > GraphicTools에 있음(ContentsManager가 설치된
            // AdrenoTools 드라이버를 그 목록에 넣음). 그래서 AdrenotoolsFragment.java를
            // 삭제함
            // main_menu_saves removed: Saves is no longer a top-level screen.
            // It's reached per-container from Containers > row menu >
            // "Manage Saves" (ContainersFragment.showListItemMenu()), which
            // opens SavesFragment pre-filtered to that one container.
            // main_menu_saves를 지움: Saves는 이제 최상위 화면이 아님.
            // Containers > 행 메뉴 > "Manage Saves"에서 컨테이너별로 들어감
            // (ContainersFragment.showListItemMenu()). 그 컨테이너 것만 걸러진
            // SavesFragment가 열림
            case R.id.main_menu_settings:
                show(new SettingsFragment(), false);  // Forward animation
                break;
            case R.id.main_menu_about:
                showAboutDialog();
                break;
            case R.id.main_menu_steam:
                // Separate activity so the login thread never blocks the menu
                // 로그인 스레드가 메뉴를 막지 않게 별도 액티비티로 띄움
                // A drawer page like the others (was a separate activity
                // without the toolbar / hamburger menu)
                // 다른 화면과 같은 드로어 페이지임(예전엔 툴바 / 햄버거 메뉴가
                // 없는 별도 액티비티였음)
                show(new com.winlator.cmod.steam.SteamFragment(), false);
                break;
        }
        return true;
    }


    private void show(Fragment fragment, boolean reverse) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (reverse) {
            fragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_in_down, R.anim.slide_out_up)  // Reverse animation
                    .replace(R.id.FLFragmentContainer, fragment)
                    .commit();
        } else {
            fragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)  // Forward animation
                    .replace(R.id.FLFragmentContainer, fragment)
                    .commit();
        }

        drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void showAboutDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.about_dialog);
        dialog.findViewById(R.id.LLBottomBar).setVisibility(View.GONE);

        // neon_dialog_bg pulls ?attr/neonSurface / neonGlowMid from the theme,
        // so one resource id covers both light and dark - no isDarkMode branch
        // needed, unlike the old two-drawable setup.
        // neon_dialog_bg는 테마에서 ?attr/neonSurface / neonGlowMid를 가져오므로
        // 리소스 id 하나로 라이트/다크를 다 커버함. 예전처럼 드로어블 2개를 두고
        // isDarkMode로 가르는 코드가 필요 없음
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.neon_dialog_bg);

        try {
            final PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);

            TextView tvWebpage = dialog.findViewById(R.id.TVWebpage);
            // About dialog link points to the EZlator repository instead of the upstream site
            // 정보 창 링크를 원본 사이트 대신 EZlator 저장소로 연결함
            tvWebpage.setText(Html.fromHtml("<a href=\"https://github.com/4thyaN6/EZlator\">github.com/4thyaN6/EZlator</a>", Html.FROM_HTML_MODE_LEGACY));
            tvWebpage.setMovementMethod(LinkMovementMethod.getInstance());

            ((TextView) dialog.findViewById(R.id.TVAppVersion)).setText(getString(R.string.version) + " " + pInfo.versionName);
            // Update check on tap only / 누를 때만 업데이트 확인
            dialog.findViewById(R.id.TVCheckUpdate).setOnClickListener(v -> com.winlator.cmod.core.AppUpdateChecker.check(this));
            // Report log: made only when tapped, the user picks where it goes
            // 제보용 로그: 누를 때만 만들고, 보낼 곳은 사용자가 고름
            dialog.findViewById(R.id.TVReportLog).setOnClickListener(v -> com.winlator.cmod.core.ReportLogBuilder.makeAndShare(this));

            // Credits + third-party notices. Rules used for this list:
            //  - only what the app ships or downloads is listed, with its license;
            //  - PRoot, OpenXR-SDK and the Big Picture music were removed because
            //    the APK no longer contains them (PRoot never shipped: no
            //    libproot.so and no cpp/proot/ exist in the project);
            //  - full inventory and open questions: THIRD_PARTY_NOTICES.md (repo root).
            // 크레딧 + 제3자 고지임. 이 목록의 기준:
            //  - 앱이 실제로 포함하거나 내려받는 것만, 라이선스와 함께 적음
            //  - PRoot, OpenXR-SDK, Big Picture 음악은 APK에 더 이상 없어서 뺌
            //    (PRoot는 원래부터 안 들어 있었음: 프로젝트에 libproot.so도
            //    cpp/proot/도 없음)
            //  - 전체 목록과 미결 사항은 저장소 루트의 THIRD_PARTY_NOTICES.md 참고
            // Link to the full third-party notice in the repository
            // (repo_url in strings.xml; set it once the GitHub repo exists)
            // 저장소의 전체 제3자 고지 문서 링크(strings.xml의 repo_url. GitHub
            // 저장소를 만든 뒤 주소를 넣을 것)
            String noticesLink = "<b><a href=\"" + getString(R.string.repo_url) + "/blob/main/THIRD_PARTY_NOTICES.md\">"
                    + getString(R.string.third_party_notices_link) + "</a></b>";
            String creditsAndThirdPartyAppsHTML = String.join("<br />",
                    noticesLink,
                    getString(R.string.trademark_notice),
                    "---",
                    "Winlator was created by Brunodev85 (<a href=\"https://github.com/brunodev85\">Git</a>) — MIT",
                    "Winlator Cmod by <a href=\"https://github.com/coffincolors/winlator\">coffincolors</a>, <a href=\"https://github.com/Pipetto-crypto/winlator\">Pipetto-crypto</a>",
                    "Winlator Glibc by longjunyu2 (<a href=\"https://github.com/longjunyu2/winlator/\">Fork</a>)",
                    "Winlator OpenXR by lvonasek (<a href=\"https://github.com/lvonasek\">Git</a>)",
                    "---",
                    "Ubuntu RootFs (<a href=\"https://releases.ubuntu.com/focal\">Focal Fossa</a>)",
                    "Wine (<a href=\"https://www.winehq.org\">winehq.org</a>) — LGPL-2.1+",
                    "Box64 / WOWBox64 by <a href=\"https://github.com/ptitSeb/box64\">ptitSeb</a> — MIT",
                    "FEX-Emu / FEXCore (<a href=\"https://github.com/FEX-Emu/FEX\">github.com/FEX-Emu/FEX</a>) — MIT",
                    "Mesa (Turnip/Zink/VirGL) (<a href=\"https://www.mesa3d.org\">mesa3d.org</a>) — MIT",
                    "libadrenotools by bylaws (<a href=\"https://github.com/bylaws/libadrenotools\">Git</a>) — BSD-2-Clause",
                    "vkBasalt (<a href=\"https://github.com/DadSchoorse/vkBasalt\">Git</a>) — zlib",
                    "DXVK (<a href=\"https://github.com/doitsujin/dxvk\">github.com/doitsujin/dxvk</a>) — zlib",
                    "VKD3D-Proton (<a href=\"https://github.com/HansKristian-Work/vkd3d-proton\">Git</a>) — LGPL-2.1",
                    "D8VK (<a href=\"https://github.com/AlpyneDreams/d8vk\">github.com/AlpyneDreams/d8vk</a>) — zlib",
                    "CNC DDraw (<a href=\"https://github.com/FunkyFr3sh/cnc-ddraw\">github.com/FunkyFr3sh/cnc-ddraw</a>) — MIT",
                    "DxWrapper by Elisha Riedlinger (<a href=\"https://github.com/elishacloud/dxwrapper\">Git</a>) — zlib",
                    "nGlide by Zeus Software (<a href=\"https://www.zeus-software.com\">zeus-software.com</a>) — freeware",
                    "SDL2 (<a href=\"https://www.libsdl.org\">libsdl.org</a>) — zlib",
                    "hidapi (<a href=\"https://github.com/libusb/hidapi\">github.com/libusb/hidapi</a>) — BSD",
                    "PulseAudio 13.0, libsndfile, GNU libltdl (<a href=\"https://www.freedesktop.org/wiki/Software/PulseAudio/\">PulseAudio</a>) — LGPL-2.1+",
                    "MidiSynth by KyoSherlock (<a href=\"https://github.com/KyoSherlock\">Git</a>) — Apache-2.0",
                    "SONiVOX EAS GM Wavetable soundfont, © 1993 Sonic Network, Inc. — <a href=\"https://creativecommons.org/licenses/by/3.0/\">CC BY 3.0</a>",
                    "---",
                    "JavaSteam (<a href=\"https://github.com/Longi94/JavaSteam\">Git</a>) — MIT",
                    "Bouncy Castle (<a href=\"https://www.bouncycastle.org\">bouncycastle.org</a>) — Bouncy Castle License (MIT)",
                    "ZXing (<a href=\"https://github.com/zxing/zxing\">Git</a>) — Apache-2.0",
                    "Apache Commons Compress (<a href=\"https://commons.apache.org/proper/commons-compress/\">Apache</a>) — Apache-2.0",
                    // LZX decoder files taken from dorkbox CabParser (core/cab)
                    // dorkbox CabParser에서 가져온 LZX 해제 파일(core/cab)
                    "dorkbox CabParser, LZX decoder (<a href=\"https://github.com/dorkbox/CabParser\">Git</a>) — Apache-2.0",
                    "zstd-jni (<a href=\"https://github.com/luben/zstd-jni\">Git</a>) — BSD-2-Clause",
                    "XZ for Java (<a href=\"https://tukaani.org/xz/java.html\">tukaani.org</a>) — public domain",
                    "AndroidX, Material Components, FlexboxLayout (Google) — Apache-2.0",
                    // Transitive libraries pulled in by JavaSteam (from gradle dependencies)
                    // JavaSteam이 가져오는 전이 라이브러리임(gradle dependencies 결과 기준)
                    "Kotlin stdlib, kotlinx.coroutines / serialization / io (JetBrains) — Apache-2.0",
                    "Ktor (<a href=\"https://github.com/ktorio/ktor\">Git</a>), OkHttp, Okio (Square) — Apache-2.0",
                    "Protocol Buffers (<a href=\"https://github.com/protocolbuffers/protobuf\">Git</a>) — BSD-3-Clause",
                    "Apache Commons Lang — Apache-2.0, SLF4J API — MIT",
                    "---",
                    "Proton-wine build by GameNative (<a href=\"https://github.com/GameNative/proton-wine\">github.com/GameNative/proton-wine</a>)",
                    "Turnip driver builds by StevenMXZ (<a href=\"https://github.com/StevenMXZ/Adreno-Tools-Drivers\">Adreno-Tools-Drivers</a>)",
                    "ExynosTools by WearyConcern1165 (<a href=\"https://github.com/WearyConcern1165/ExynosTools\">Git</a>)",
                    "Component catalog by nicholasx417 (<a href=\"https://github.com/nicholasx417/WinNative-Components\">WinNative-Components</a>)",
                    "The412Banner (<a href=\"https://github.com/The412Banner\">Git</a>)",
                    // Downloaded at run time, a separate program (see THIRD_PARTY_NOTICES.md)
                    // 실행 중에 받는 별도 프로그램임(THIRD_PARTY_NOTICES.md 참고)
                    "SteamLite agent by The412Banner, derived from WinNative wn-steam-launcher (<a href=\"https://github.com/The412Banner/winlator-contents/tree/main/steamlite/agent-src\">source</a>) — GPL-3.0"
            );

            TextView tvCreditsAndThirdPartyApps = dialog.findViewById(R.id.TVCreditsAndThirdPartyApps);
            tvCreditsAndThirdPartyApps.setText(Html.fromHtml(creditsAndThirdPartyAppsHTML, Html.FROM_HTML_MODE_LEGACY));
            tvCreditsAndThirdPartyApps.setMovementMethod(LinkMovementMethod.getInstance());

        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        dialog.show();
        // Screen-relative width: 90% of the screen, capped at 760dp
        // 화면 비례 폭: 화면의 90%, 최대 760dp
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.min((int) (dm.widthPixels * 0.9f), (int) (760 * dm.density));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

}
