package com.winlator.cmod;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
// Steam download / update / 스팀 다운로드 / 업데이트
import com.winlator.cmod.steam.SteamDownloadPanel;
import com.winlator.cmod.steam.SteamDownloadState;
import com.winlator.cmod.steam.SteamUpdateChecker;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.DXVKConfig;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import java.util.HashMap;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GameArchiveExtractor;
import com.winlator.cmod.core.StorageDrives;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.WineUtils;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

// Added for the neon shortcuts screen: detail sheet, tile grid, and the
// localized size / relative-time formatters.
// 네온 숏컷 화면용으로 추가함: 상세창, 타일 격자, 그리고 현지화된 용량 /
// 상대 시간 표기 유틸임
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
// Tile icon drawing (pixel-art scaling control) and image import
// 타일 아이콘 그리기(픽셀아트 확대 제어)와 이미지 가져오기용
import android.graphics.drawable.BitmapDrawable;
import com.winlator.cmod.core.ImageImportUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Window;
import androidx.recyclerview.widget.GridLayoutManager;
import java.util.Locale;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

public class ShortcutsFragment extends Fragment {
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;

    private PreloaderDialog preloaderDialog;

    // View mode. Persisted so the choice survives leaving and re-entering
    // the screen - a view toggle that resets itself is worse than none.
    // 보기 방식임. 화면을 나갔다 들어와도 유지되도록 저장함.
    // 매번 초기화되는 전환 버튼은 없느니만 못함
    private static final String PREF_VIEW_MODE = "shortcuts_view_mode";
    private static final String MODE_TILE = "tile";
    private static final String MODE_LIST = "list";
    private String viewMode = MODE_TILE;
    private View btViewTile;
    private View btViewList;
    // The toggle view this fragment placed in the toolbar slot, kept so that
    // onDestroyView removes exactly this view and nothing else.
    // 이 프래그먼트가 툴바 슬롯에 넣은 토글 뷰임. onDestroyView에서 정확히
    // 이것만 빼기 위해 보관함
    private View toolbarToggleView;

    // ==================== Tile image / background cover ====================
    // ==================== 타일 이미지 / 배경 커버 ====================

    // Which image a pending picker result is for
    // 대기 중인 선택기 결과가 어느 이미지용인지 나타냄
    private static final int IMAGE_SLOT_TILE = 1;
    private static final int IMAGE_SLOT_COVER = 2;

    // Longer-edge pixel caps for the stored copies. A tile is at most a few
    // hundred px on screen; the cover fills the detail sheet, so it gets more
    // 저장 사본의 긴 변 픽셀 상한임. 타일은 화면에서 최대 수백 px이고, 커버는
    // 상세창 전체를 채우므로 더 크게 둠
    private static final int TILE_IMAGE_MAX_PX = 512;
    private static final int COVER_IMAGE_MAX_PX = 1600;

    // Icons narrower than this are scaled up without smoothing, so small
    // 32/48/64px icons stay sharp pixel blocks instead of turning into blur
    // 이보다 좁은 아이콘은 보간 없이 확대함. 32/48/64px 작은 아이콘이 뿌옇게
    // 번지지 않고 또렷한 픽셀 블록으로 보임
    private static final int SMOOTH_ICON_MIN_PX = 128;

    private Shortcut pendingImageShortcut;
    private int pendingImageSlot = 0;

    // Image extensions shown by the in-app picker (all formats BitmapFactory
    // decodes; ImageImportUtils re-encodes them anyway)
    // 앱 내 선택기가 보여 주는 이미지 확장자임(BitmapFactory가 읽는 형식 전부.
    // 어차피 ImageImportUtils가 다시 인코딩함)
    private static final String[] IMAGE_EXTENSIONS = {
            ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif", ".ico", ".heic", ".heif"};

    // In-app picker result: either a file inside the game folder tree, or a
    // request to open the system picker instead ("external image" button)
    // 앱 내 선택기 결과임: 게임 폴더 쪽의 파일이거나, 시스템 선택기를 대신
    // 열어 달라는 요청("외부 이미지 추가" 버튼)임
    private final ActivityResultLauncher<Intent> folderImagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Shortcut target = pendingImageShortcut;
                int slot = pendingImageSlot;
                if (target == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    pendingImageShortcut = null;
                    pendingImageSlot = 0;
                    return;
                }
                if (result.getData().getBooleanExtra("useExternalPicker", false)) {
                    // pending state is kept for the system picker's result
                    // 대기 상태는 시스템 선택기 결과용으로 그대로 둠
                    openSystemImagePicker();
                    return;
                }
                pendingImageShortcut = null;
                pendingImageSlot = 0;
                String selected = result.getData().getStringExtra("selectedFile");
                if (selected != null) importShortcutImage(target, slot, Uri.fromFile(new File(selected)));
            });

    // System document picker limited to images. Note: no start folder is
    // passed, so the system picker itself decides where it opens (it
    // reopens the folder last used from this app)
    // 이미지로 한정한 시스템 문서 선택기임. 참고: 시작 폴더를 넘기지 않으므로
    // 어디서 열릴지는 시스템 선택기가 정함(이 앱에서 마지막으로 쓴 폴더를 다시 엶)
    private final ActivityResultLauncher<Intent> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Shortcut target = pendingImageShortcut;
                int slot = pendingImageSlot;
                pendingImageShortcut = null;
                pendingImageSlot = 0;
                if (target == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null) importShortcutImage(target, slot, uri);
            });

    // Opens the in-app picker at the game's own folder (where the exe is),
    // because cover/tile images are usually kept next to the game. The
    // "external image" button inside it widens the search to the system picker
    // 앱 내 선택기를 게임 폴더(exe가 있는 곳)에서 엶. 커버/타일 이미지는 보통
    // 게임 옆에 두기 때문임. 그 안의 "외부 이미지 추가" 버튼으로 시스템
    // 선택기까지 범위를 넓힘
    private void openShortcutImagePicker(Shortcut shortcut, int slot) {
        pendingImageShortcut = shortcut;
        pendingImageSlot = slot;

        File exe = shortcut.resolveExeFile();
        File startDir = exe != null ? exe.getParentFile() : null;
        if (startDir == null || !startDir.isDirectory()) startDir = Environment.getExternalStorageDirectory();

        Intent intent = new Intent(getContext(), com.winlator.cmod.saves.CustomFilePickerActivity.class);
        intent.putExtra("initialDirectory", startDir.getAbsolutePath());
        intent.putExtra("selectFileMode", true);
        intent.putExtra("extensionFilters", IMAGE_EXTENSIONS);
        intent.putExtra("pickerTitle", getString(slot == IMAGE_SLOT_TILE
                ? R.string.neon_tile_image : R.string.neon_background_cover));
        intent.putExtra("externalPickerLabel", getString(R.string.neon_image_external));
        folderImagePickerLauncher.launch(intent);
    }

    private void openSystemImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        imagePickerLauncher.launch(intent);
    }

    // Change / reset menu for one image slot. The reset entry only appears
    // when a custom image is actually set
    // 이미지 한 칸의 변경 / 되돌리기 메뉴임. 되돌리기 항목은 사용자 지정
    // 이미지가 실제로 있을 때만 나옴
    private void showShortcutImageMenu(final Shortcut shortcut, final int slot, final Dialog detailDialog) {
        final Context context = getContext();
        if (context == null) return;
        final boolean isTile = slot == IMAGE_SLOT_TILE;
        boolean hasCustom = isTile ? shortcut.hasCustomTileImage() : shortcut.hasCustomCoverArt();

        String choose = getString(R.string.neon_image_choose);
        String reset = getString(isTile ? R.string.neon_tile_image_reset : R.string.neon_background_cover_remove);
        String[] items = hasCustom ? new String[]{choose, reset} : new String[]{choose};

        new AlertDialog.Builder(context)
                .setTitle(isTile ? R.string.neon_tile_image : R.string.neon_background_cover)
                .setItems(items, (d, which) -> {
                    // The sheet is closed first and reopened afterwards, so it
                    // always shows the image state that was just saved
                    // 상세창을 먼저 닫고 나중에 다시 열어서, 방금 저장한 이미지
                    // 상태가 항상 보이게 함
                    if (detailDialog != null) detailDialog.dismiss();
                    if (which == 0) {
                        openShortcutImagePicker(shortcut, slot);
                    } else {
                        if (isTile) shortcut.setCustomTileImage(null);
                        else shortcut.setCustomCoverArt(null);
                        loadShortcutsList();
                        showShortcutDetail(shortcut);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Tile copies are PNG (icons often have transparency); covers are JPEG
    // because they sit under an opaque scrim and JPEG is far smaller
    // 타일 사본은 PNG임(아이콘에 투명 영역이 많음). 커버는 불투명한 어둠 막
    // 아래에 깔리고 JPEG가 훨씬 작아서 JPEG로 저장함
    private void importShortcutImage(final Shortcut shortcut, final int slot, final Uri uri) {
        final Context appContext = requireContext().getApplicationContext();
        final boolean isTile = slot == IMAGE_SLOT_TILE;
        Executors.newSingleThreadExecutor().execute(() -> {
            File out = isTile
                    ? shortcut.newCustomImageFile("tile", ".png")
                    : shortcut.newCustomImageFile("cover", ".jpg");
            boolean ok = isTile
                    ? ImageImportUtils.importScaled(appContext, uri, out, TILE_IMAGE_MAX_PX, Bitmap.CompressFormat.PNG, 100)
                    : ImageImportUtils.importScaled(appContext, uri, out, COVER_IMAGE_MAX_PX, Bitmap.CompressFormat.JPEG, 90);
            if (ok) {
                if (isTile) shortcut.setCustomTileImage(out);
                else shortcut.setCustomCoverArt(out);
                // Decode the cover here, off the UI thread, so reopening the
                // sheet does not stall on a large JPEG
                // 커버를 여기서(UI 스레드 밖) 미리 디코딩해서 상세창을 다시 열 때
                // 큰 JPEG 때문에 멈추지 않게 함
                if (!isTile) shortcut.getCoverArt();
            }
            Activity a = getActivity();
            if (a == null) return;
            a.runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(appContext,
                        ok ? R.string.neon_image_applied : R.string.neon_image_failed,
                        Toast.LENGTH_SHORT).show();
                loadShortcutsList();
                showShortcutDetail(shortcut);
            });
        });
    }

    // Puts the shortcut's tile picture into an ImageView: custom tile image
    // first, then the exe icon scaled up to fill the tile, then the app logo.
    // Returns true when something filled the frame (so the small centred
    // fallback icon can stay hidden)
    // 숏컷의 타일 그림을 ImageView에 넣음: 사용자 지정 타일 이미지 우선, 없으면
    // exe 아이콘을 타일에 꽉 차게 확대, 그것도 없으면 앱 로고임. 틀을 채운
    // 경우 true를 돌려줌(가운데 작은 대체 아이콘을 숨긴 채로 두기 위함)
    private boolean bindTilePicture(ImageView target, Shortcut shortcut) {
        Bitmap tile = shortcut.getTileImage();
        if (tile != null) {
            target.setImageBitmap(tile);
            return true;
        }
        if (shortcut.icon != null) {
            BitmapDrawable drawable = new BitmapDrawable(getResources(), shortcut.icon);
            drawable.setFilterBitmap(shortcut.icon.getWidth() >= SMOOTH_ICON_MIN_PX);
            target.setImageDrawable(drawable);
            return true;
        }
        target.setImageDrawable(null);
        return false;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public void onDestroyView() {
        // Remove only the exact view this fragment added. The slot is shared
        // by the whole activity, and during a replace animation a NEW
        // ShortcutsFragment can already have put its own toggle there before
        // this old one is destroyed - clearing the whole slot would wipe it.
        // 이 프래그먼트가 넣은 뷰만 정확히 뺌. 슬롯은 액티비티 전체가 같이
        // 쓰는데, 교체 애니메이션 도중에는 새 ShortcutsFragment가 이 옛
        // 프래그먼트가 파괴되기 전에 자기 토글을 이미 넣어 둘 수 있음 - 슬롯을
        // 통째로 비우면 그걸 지워 버림
        if (toolbarToggleView != null && toolbarToggleView.getParent() instanceof ViewGroup) {
            ((ViewGroup) toolbarToggleView.getParent()).removeView(toolbarToggleView);
        }
        toolbarToggleView = null;
        super.onDestroyView();
        //stopFileObservers(); // Stop watching to prevent memory leaks
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        // Containers made before tier names get the tier mark and name once
        // 단계 이름 이전에 만든 컨테이너에 단계 표시와 이름을 한 번 붙임
        com.winlator.cmod.container.LayerTiers.adoptUntagged(getContext(), manager);
        preloaderDialog = new PreloaderDialog(getActivity());
        loadShortcutsList();
        maybeRecommendLayer();
       // startFileObservers(); // Start watching for new file
        // Title lives in the toolbar, next to the hamburger, on one line - the
        // same place every other screen puts its title. The inline header row
        // that used to sit below the toolbar was removed from
        // neon_shortcuts_fragment.xml; the Tile/List chips moved into the
        // toolbar's action slot (see onCreateView).
        // 제목은 툴바의 햄버거 바로 옆, 한 줄에 둠 - 다른 모든 화면이 제목을
        // 두는 곳과 같음. 툴바 아래에 있던 인라인 헤더 줄은
        // neon_shortcuts_fragment.xml에서 뺐고, 타일/리스트 칩은 툴바의 동작
        // 슬롯으로 옮김(onCreateView 참고)
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.shortcuts);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (recyclerView != null && manager != null) loadShortcutsList();
    }

    // MainActivity declares orientation|screenSize|screenLayout in its
    // configChanges (AndroidManifest line 60), so rotating does NOT recreate
    // the activity and onCreateView never runs again. Without this override
    // the column count stays at whatever it was when the screen was built.
    // MainActivity가 configChanges에 orientation|screenSize|screenLayout을
    // 선언해 둠(AndroidManifest 60번째 줄). 그래서 회전해도 액티비티가 재생성되지
    // 않고 onCreateView도 다시 안 돎. 이 오버라이드가 없으면 열 수가 화면을
    // 처음 만들 때 값에 그대로 묶임
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (MODE_TILE.equals(viewMode)) applyLayoutManager();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.neon_shortcuts_fragment, container, false);

        // The ids changed with the layout: RVShortcuts / TVEmpty / BTAddShortcut
        // 레이아웃과 함께 id가 바뀜: RVShortcuts / TVEmpty / BTAddShortcut
        recyclerView = frameLayout.findViewById(R.id.RVShortcuts);
        emptyTextView = frameLayout.findViewById(R.id.TVEmpty);

        // No DividerItemDecoration any more. The cards carry their own margins,
        // and a flat grey line between two glowing cards reads as a glitch.
        // DividerItemDecoration을 더 안 씁. 카드가 자기 여백을 가지고 있고,
        // 빛나는 카드 사이의 평평한 회색 선은 오류처럼 보임
        viewMode = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString(PREF_VIEW_MODE, MODE_TILE);

        // Tile/List toggle is inflated into the toolbar's action slot
        // (FLToolbarActions in main_activity.xml), so it sits on the same line
        // as the hamburger and the title instead of on a second row.
        // 타일/리스트 전환 칩을 툴바의 동작 슬롯(main_activity.xml의
        // FLToolbarActions)에 넣음. 그래서 두 번째 줄이 아니라 햄버거, 제목과
        // 같은 줄에 놓임
        ViewGroup toolbarSlot = requireActivity().findViewById(R.id.FLToolbarActions);
        // Category chips + Tile/List in one row
        // 분류 칩 + 타일/목록을 한 줄로 둠
        toolbarToggleView = inflater.inflate(R.layout.neon_shortcut_toolbar, toolbarSlot, false);
        toolbarSlot.addView(toolbarToggleView);
        filter = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(PREF_FILTER, FILTER_ALL);
        // One "View" chip opens the category / layout / columns menu
        // "보기" 칩 하나가 분류 / 배치 / 줄당 개수 메뉴를 엶
        View btViewMenu = toolbarToggleView.findViewById(R.id.BTViewMenu);
        btViewMenu.setOnClickListener(this::showViewMenu);
        applyLayoutManager();

        // Wire up the "Add Shortcut" FAB — must come before return, or it's unreachable
        // "바로가기 추가" FAB 연결 — return보다 먼저 와야 함(안 그러면 도달 불가능한 코드가 됨)
        FloatingActionButton fabAddShortcut = frameLayout.findViewById(R.id.BTAddShortcut);
        fabAddShortcut.setOnClickListener(v -> pickExecutableAndCreateShortcut());

        return frameLayout;
    }

    // Same .desktop-writing logic as createDesktopFileFromLnk(), but takes a
    // Windows-style path directly instead of parsing a .lnk file.
    // createDesktopFileFromLnk()와 같은 .desktop 작성 로직인데, .lnk 파싱 없이
    // 윈도우 스타일 경로를 직접 받음
    private boolean createDesktopFileFromExePath(String windowsExePath, Container container, String displayName) {

            String lnkName = displayName;
            File desktopFile = new File(container.getDesktopDir(), lnkName + ".desktop");
            if (desktopFile.exists()) {
                int i = 2;
                while (new File(container.getDesktopDir(), lnkName + " (" + i + ").desktop").exists()) i++;
                desktopFile = new File(container.getDesktopDir(), lnkName + " (" + i + ").desktop");
            }

            String filesDir = getContext().getFilesDir().getAbsolutePath();
            String genericHomePath = filesDir + "/imagefs/home/xuser";
            String winePrefix = genericHomePath + "/.wine/dosdevices/z:" + genericHomePath + "/.wine";
            String escapedTargetPath = windowsExePath.replace("\\", "\\\\\\\\");
            String execCommand = "env WINEPREFIX=\"" + winePrefix + "\" wine " + escapedTargetPath;

            File genericDosdevicesDir = new File(genericHomePath, ".wine/dosdevices");
            String driveLetter = windowsExePath.substring(0, 1).toLowerCase();
            File driveSymlink = new File(genericDosdevicesDir, driveLetter + ":");
            String pathAfterDrive = windowsExePath.substring(windowsExePath.indexOf('\\') + 1);
            String windowsWorkingDir = FileUtils.getDirname(pathAfterDrive);
            String finalWorkingPath = new File(driveSymlink, windowsWorkingDir).getAbsolutePath();
            String wmClass = FileUtils.getName(windowsExePath);

            // Pull the icon straight out of the exe's own PE resources so the
            // shortcut isn't stuck with a blank Icon= line. Best-effort: any
            // failure here (unsupported icon format, no icon resource, exe
            // not readable yet) just leaves iconValue empty, same as before
            // exe 자체의 PE 리소스에서 바로 아이콘을 뽑아서 숏컷의 Icon= 값이
            // 빈 채로 남지 않게 함. 실패해도 괜찮음: 여기서 뭐가 잘못되든
            // (지원 안 하는 아이콘 형식, 아이콘 리소스 없음, 아직 못 읽는 exe
            // 등) iconValue는 그냥 기존처럼 빈 채로 남음
            String iconValue = "";
            File realExeFile = new File(driveSymlink, pathAfterDrive);
            // The icon is named after the .desktop file, not the
            // display name. Two games both called "Game" got "Game.desktop" and
            // "Game (2).desktop" but shared icons/64/Game.png, so the second
            // extraction replaced the first game's icon
            // 아이콘 이름을 표시 이름이 아니라 .desktop 파일 이름으로 정함. 이름이
            // 둘 다 "Game"인 게임은 "Game.desktop"과 "Game (2).desktop"이 되는데
            // icons/64/Game.png를 같이 써서, 두 번째 추출이 첫 게임의 아이콘을 덮어썼음
            String iconName = FileUtils.getBasename(desktopFile.getPath());
            File iconFile = new File(container.getIconsDir(64), iconName + ".png");
            if (ExeIconExtractor.extractLargestIconToFile(realExeFile, iconFile)) {
                iconValue = iconName;
            }

            String content =
                    "[Desktop Entry]\n" +
                            "Name=" + lnkName + "\n" +
                            "Exec=" + execCommand + "\n" +
                            "Type=Application\n" +
                            "StartupNotify=true\n" +
                            "Path=" + finalWorkingPath + "\n" +
                            "Icon=" + iconValue + "\n" +
                            "StartupWMClass=" + wmClass + "\n\n" +
                            "[Extra Data]\n" +
                            "container_id:" + container.id + "\n";

            // FileUtils.writeString() already catches IOException internally and
            // returns false on failure — no need for our own try/catch here
            // FileUtils.writeString()이 내부에서 IOException을 이미 처리하고
            // 실패 시 false를 돌려주므로, 여기서 별도 try/catch가 필요 없음
            return FileUtils.writeString(desktopFile, content);

    }

    // Convert an Android File under a container's C: drive into a Windows-style
    // path string (e.g. "C:\Games\Example.exe"). Returns null if the file isn't
    // actually under that container's drive_c.
    // 컨테이너 C 드라이브 하위의 안드로이드 File을 윈도우 스타일 경로 문자열로 변환.
    // 그 컨테이너의 drive_c 밖의 파일이면 null 반환
    @Nullable
    private String toWindowsPath(File exeFile, Container container) {
        File dosDevicesDir = new File(container.getRootDir(), ".wine/dosdevices");
        File[] driveLinks = dosDevicesDir.listFiles();
        if (driveLinks == null) return null;
        try {
            // The deepest matching drive wins: a game in the EZlator folder is
            // under both D: (EZlator) and Y: (storage root), and D:\game.exe is
            // the expected path. The match must end at a folder boundary so
            // ".../EZlator2" is not taken as ".../EZlator"
            // 가장 깊이 맞는 드라이브를 씀: EZlator 폴더의 게임은 D:(EZlator)와
            // Y:(저장소 루트) 양쪽에 걸리는데, 기대하는 경로는 D:\game.exe임. 일치는
            // 폴더 경계에서 끝나야 함(".../EZlator2"를 ".../EZlator"로 보지 않게)
            String filePath = exeFile.getCanonicalPath();
            String bestDrive = null;
            String bestTarget = null;
            for (File driveLink : driveLinks) {
                String driveName = driveLink.getName();
                if (!driveName.matches("[a-z]:")) continue;
                String targetPath = driveLink.getCanonicalFile().getAbsolutePath();
                boolean inside = filePath.equals(targetPath)
                        || filePath.startsWith(targetPath.endsWith("/") ? targetPath : targetPath + "/");
                if (!inside) continue;
                if (bestTarget == null || targetPath.length() > bestTarget.length()) {
                    bestTarget = targetPath;
                    bestDrive = driveName;
                }
            }
            if (bestDrive != null) {
                String relative = filePath.substring(bestTarget.length());
                if (relative.startsWith("/")) relative = relative.substring(1);
                return bestDrive.toUpperCase(java.util.Locale.ENGLISH) + "\\" + relative.replace("/", "\\");
            }
        } catch (Exception e) {
            Log.e("ShortcutCreation", "경로 변환 실패", e);
        }
        return null;
    }

    private static final int REQUEST_PICK_EXE = 4201;

    // Entry point for the "Add Shortcut" FAB. No container question any more:
    // a first-time user cannot answer it. The shortcut goes into the first
    // ARM64EC container (created if missing); the layer button in the detail
    // sheet moves it to X86_64 later for games that need it.
    // Fallbacks when no ARM64EC Wine/Proton is installed: the first existing
    // container, then a default container of whatever is installed.
    // "바로가기 추가" FAB의 진입점임. 컨테이너를 더 이상 묻지 않음: 처음 쓰는
    // 사람은 답할 수 없는 질문임. 숏컷은 첫 번째 ARM64EC 컨테이너로 들어가고
    // (없으면 만듦), X86_64가 필요한 게임은 나중에 상세창의 레이어 버튼으로 옮김.
    // ARM64EC Wine/Proton이 설치되어 있지 않을 때의 대체: 기존 첫 컨테이너,
    // 그다음은 설치된 것으로 만든 기본 컨테이너임
    private void pickExecutableAndCreateShortcut() {
        Container arm = manager.findFirstContainer(true);
        if (arm != null) {
            launchExePicker(arm);
            // The X86_64 container is made quietly in the background if it is
            // missing, so the layer button has somewhere to move games to
            // X86_64 컨테이너가 없으면 백그라운드에서 조용히 만듦. 레이어 버튼이
            // 게임을 옮길 곳이 있게 하기 위함
            if (manager.findFirstContainer(false) == null) {
                final Context appContext = requireContext().getApplicationContext();
                Executors.newSingleThreadExecutor().execute(() ->
                        new ContainerManager(appContext).getOrCreateContainerForArch(new ContentsManager(appContext), false));
            }
            return;
        }
        final Activity activity = getActivity();
        if (activity == null) return;
        preloaderDialog.show(R.string.steam_creating_arm64ec_container);
        final Context appContext = activity.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            // Both layers at once: ARM64EC (used now) and X86_64 (layer button)
            // 두 레이어를 한 번에 만듦: ARM64EC(지금 씀)와 X86_64(레이어 버튼용)
            Container created = new ContainerManager(appContext)
                    .ensureDefaultContainers(new ContentsManager(appContext));
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (!isAdded()) return;
                // Re-read so the new container is in this fragment's manager
                // 새 컨테이너가 이 프래그먼트의 manager에도 들어가도록 다시 읽음
                manager = new ContainerManager(getContext());
                if (created != null) {
                    Container fresh = manager.getContainerById(created.id);
                    launchExePicker(fresh != null ? fresh : created);
                    return;
                }
                ArrayList<Container> containers = manager.getContainers();
                if (!containers.isEmpty()) {
                    launchExePicker(containers.get(0));
                } else {
                    manager.createDefaultContainerAsync(new ContentsManager(getContext()), newContainer -> {
                        if (newContainer != null) launchExePicker(newContainer);
                        else AppUtils.showToast(getContext(), getString(R.string.neon_no_wine_installed));
                    });
                }
            });
        });
    }

    private Container pendingContainer;

    // Folder names that say nothing about the game; the name is looked for above them
    // 게임을 알려 주지 않는 폴더 이름. 이름은 그 위 폴더에서 찾음
    private static final java.util.Set<String> GENERIC_EXE_DIRS = new java.util.HashSet<>(java.util.Arrays.asList(
            "bin", "bin32", "bin64", "binaries", "win32", "win64", "x86", "x64", "x86_64", "amd64",
            "release", "retail", "shipping", "exe", "system", "program", "game", "data"));
    // Container folders: reaching one means the game has no folder of its own
    // 담는 폴더임: 여기까지 올라오면 게임 전용 폴더가 없다는 뜻임
    private static final java.util.Set<String> ROOT_DIRS = new java.util.HashSet<>(java.util.Arrays.asList(
            "common", "steamapps", "steamlibrary", "games", "program files", "program files (x86)",
            "ezlator", "download", "downloads"));

    /**
     * Shortcut name for a picked exe: the game's folder name, going up past generic
     * folders (bin, Binaries\Win64 ...). An exe right on a drive or in a container
     * folder keeps its own name. Examples:
     *   D:\Lost Memories\Game.exe                      -> "Lost Memories"
     *   D:\MyGame\MyGame\Binaries\Win64\MyGame.exe  -> "MyGame"
     *   D:\Game.exe                                     -> "Game"
     * 고른 exe의 바로가기 이름: 게임 폴더 이름. 일반적인 폴더(bin, Binaries\Win64 등)는
     * 건너뛰고 위로 올라감. 드라이브 바로 아래나 담는 폴더 안의 exe는 exe 이름을 씀. 예:
     *   D:\Lost Memories\Game.exe                      -> "Lost Memories"
     *   D:\MyGame\MyGame\Binaries\Win64\MyGame.exe  -> "MyGame"
     *   D:\Game.exe                                     -> "Game"
     */
    static String displayNameForExe(String windowsPath, File exeFile) {
        String fallback = FileUtils.getBasename(exeFile.getName());
        if (windowsPath == null) return fallback;
        String[] parts = windowsPath.replace('/', '\\').split("\\\\");
        // parts[0] = "D:", last = exe; walk the folders between, nearest first
        // parts[0] = "D:", 마지막 = exe. 그 사이 폴더를 가까운 것부터 봄
        for (int i = parts.length - 2; i >= 1; i--) {
            String dir = parts[i].trim();
            if (dir.isEmpty()) continue;
            String key = dir.toLowerCase(java.util.Locale.ENGLISH);
            if (ROOT_DIRS.contains(key)) break;
            if (GENERIC_EXE_DIRS.contains(key)) continue;
            // Characters a file name cannot hold are replaced (the name becomes <name>.desktop)
            // 파일 이름에 쓸 수 없는 글자는 바꿈(이름이 <이름>.desktop이 되므로)
            return dir.replaceAll("[\\\\/:*?\"<>|]", "_");
        }
        return fallback;
    }

    // What the add button accepts: a game's exe or an archive holding the game
    // 추가 버튼이 받는 파일: 게임 exe 또는 게임이 든 압축 파일
    private static final String[] GAME_PICK_EXTENSIONS = {".exe", ".zip", ".7z", ".7z.001", ".zip.001"};
    private static final String[] EXE_ONLY_EXTENSIONS = {".exe"};

    // Set while the exe picker is open on a folder that was just unpacked: the
    // archive files are deleted once a shortcut to an exe inside that folder is made
    // 방금 푼 폴더에서 exe 선택기가 열려 있는 동안 채워짐: 그 폴더 안의 exe로
    // 바로가기를 만들면 압축 파일을 지움
    private File pendingUnpackedFolder;
    private List<File> pendingArchiveVolumes;

    // Normal entry: exe or archive, starting at the container's drives
    // 일반 진입: exe 또는 압축 파일, 컨테이너 드라이브 목록에서 시작함
    private void launchExePicker(Container container) {
        pendingUnpackedFolder = null;
        pendingArchiveVolumes = null;
        launchExePicker(container, null, false);
    }

    private void launchExePicker(Container container, @Nullable File startDir, boolean exeOnly) {
        pendingContainer = container;

        // dosdevices links are created by XServerDisplayActivity.setupWineSystemFiles(),
        // so a container that has never been launched has an empty dosdevices folder and
        // the picker opens on nothing. Build them here too - the call is idempotent, it
        // just rewrites the symlinks - with removable volumes merged in first.
        // dosdevices 링크는 XServerDisplayActivity.setupWineSystemFiles()에서 만들어짐.
        // 그래서 한 번도 실행한 적 없는 컨테이너는 dosdevices 폴더가 비어 있고 선택기가
        // 빈 폴더를 염. 여기서도 만들어줌. 심볼릭 링크를 다시 쓸 뿐이라 반복 호출해도
        // 안전하며, 착탈식 볼륨을 먼저 병합함
        container.setAutoDrives(StorageDrives.buildAutoDrives(getContext(), container.getDrives()));
        WineUtils.createDosdevicesSymlinks(container);

        File dosDevicesDir = new File(container.getRootDir(), ".wine/dosdevices");
        File initialDir = startDir != null ? startDir : dosDevicesDir;
        Intent intent = new Intent(getContext(), com.winlator.cmod.saves.CustomFilePickerActivity.class);
        intent.putExtra("initialDirectory", initialDir.getAbsolutePath());
        intent.putExtra("selectFileMode", true);
        // The picker checks extensionFilters (several suffixes) before extensionFilter
        // 선택기는 extensionFilter보다 extensionFilters(여러 확장자)를 먼저 봄
        intent.putExtra("extensionFilters", exeOnly ? EXE_ONLY_EXTENSIONS : GAME_PICK_EXTENSIONS);
        intent.putExtra("pickerTitle", getString(exeOnly ? R.string.add_game_pick_exe_title : R.string.add_game_pick_title));
        startActivityForResult(intent, REQUEST_PICK_EXE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_EXE) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            // Picker closed on a just-unpacked folder: the game stays, the archive too
            // 방금 푼 폴더에서 선택기를 닫음: 게임 폴더도 압축 파일도 그대로 둠
            if (pendingUnpackedFolder != null && getContext() != null) {
                AppUtils.showToast(getContext(), getString(R.string.add_game_archive_kept, pendingUnpackedFolder.getName()));
            }
            pendingUnpackedFolder = null;
            pendingArchiveVolumes = null;
            return;
        }
        String selectedPath = data.getStringExtra("selectedFile");
        if (selectedPath == null || pendingContainer == null) return;
        File picked = new File(selectedPath);
        if (GameArchiveExtractor.isArchive(picked)) {
            startArchiveImport(picked, pendingContainer);
            return;
        }
        boolean ok = addShortcutForExe(picked, pendingContainer);
        // The archive goes only when the shortcut points into the folder it was unpacked to
        // 바로가기가 압축을 푼 폴더 안을 가리킬 때만 압축 파일을 지움
        if (ok && pendingUnpackedFolder != null && pendingArchiveVolumes != null
                && isInside(picked, pendingUnpackedFolder)) {
            deleteArchiveVolumes(pendingArchiveVolumes);
        }
        pendingUnpackedFolder = null;
        pendingArchiveVolumes = null;
    }

    // Shortcut for an exe; false (with a message) when it is outside the container's drives
    // exe의 바로가기를 만듦. 컨테이너 드라이브 밖이면 안내 후 false
    private boolean addShortcutForExe(File exeFile, Container container) {
        String windowsPath = toWindowsPath(exeFile, container);
        if (windowsPath == null) {
            // The picked file is outside every drive this container maps, so there
            // is no Windows path for it. Silently returning here made the whole
            // "add shortcut" flow look like it did nothing.
            // 고른 파일이 이 컨테이너가 매핑한 어떤 드라이브에도 속하지 않아
            // 윈도우 경로를 만들 수 없음. 조용히 return하는 바람에 "바로가기 추가"가
            // 아무 반응도 없는 것처럼 보였음
            Log.w("ShortcutCreation", "매핑된 드라이브 밖의 경로: " + exeFile.getAbsolutePath());
            showDriveNotMappedToast();
            return false;
        }
        // Name from the game's folder, not the exe (RPG Maker games are all "Game")
        // 이름은 exe가 아니라 게임 폴더에서 가져옴(RPG 메이커 게임은 전부 "Game"임)
        String displayName = displayNameForExe(windowsPath, exeFile);
        boolean ok = createDesktopFileFromExePath(windowsPath, container, displayName);
        if (ok) {
            loadShortcutsList(); // 기존에 있는 목록 새로고침 함수 재사용 (실제 이름 다르면 맞춰 교체)
            AppUtils.showToast(getContext(), "바로가기가 추가되었습니다: " + displayName + "\n" + windowsPath);
        }
        return ok;
    }

    private void showDriveNotMappedToast() {
        AppUtils.showToast(getContext(),
                "이 폴더는 컨테이너 드라이브에 연결돼 있지 않습니다.\n"
                        + "컨테이너 설정 > 드라이브에서 이 폴더를 추가한 뒤 다시 시도하세요.");
    }

    private static boolean isInside(File file, File dir) {
        try {
            return file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- Adding a game from an archive (.zip / .7z) / 압축 파일(.zip / .7z)로 게임 추가 ----
    // Flow: read the table of contents -> compare the unpacked size with the free
    // space -> confirm (with "delete the archive" option) -> unpack next to the
    // archive with a progress window -> one game exe: shortcut at once; several:
    // exe picker on the new folder -> archive deleted only after the shortcut is made.
    // 흐름: 목록 읽기 -> 푼 크기와 남은 공간 비교 -> 확인("압축 파일 삭제" 선택 포함)
    // -> 진행 창과 함께 압축 파일 옆에 풀기 -> 게임 exe가 하나면 바로 바로가기, 여럿이면
    // 새 폴더에서 exe 선택기 -> 바로가기를 만든 뒤에만 압축 파일 삭제

    private void startArchiveImport(File archive, Container container) {
        final Activity activity = getActivity();
        if (activity == null) return;
        // The game is unpacked next to the archive, so that folder must be on a drive
        // 게임을 압축 파일 옆에 풀기 때문에 그 폴더가 드라이브에 있어야 함
        if (toWindowsPath(archive, container) == null) {
            Log.w("ShortcutCreation", "매핑된 드라이브 밖의 압축 파일: " + archive.getAbsolutePath());
            showDriveNotMappedToast();
            return;
        }
        preloaderDialog.show(R.string.add_game_archive_reading);
        final int memoryLimitKb = GameArchiveExtractor.defaultMemoryLimitKb();
        Executors.newSingleThreadExecutor().execute(() -> {
            GameArchiveExtractor.Info info = null;
            IOException error = null;
            try {
                info = GameArchiveExtractor.scan(archive, memoryLimitKb);
                Log.i("ShortcutCreation", "archive " + archive.getName() + ": " + info.fileCount + " files, "
                        + info.totalBytes + " bytes, exe " + info.exeCount + ", top " + info.topDir
                        + ", zip names " + info.zipCharset + ", volumes " + info.volumes.size());
            } catch (IOException e) {
                Log.w("ShortcutCreation", "archive scan failed: " + archive, e);
                error = e;
            } catch (RuntimeException e) {
                Log.w("ShortcutCreation", "archive scan failed: " + archive, e);
                error = GameArchiveExtractor.classify(e);
            }
            final GameArchiveExtractor.Info scanned = info;
            final IOException failure = error;
            final long free = archive.getAbsoluteFile().getParentFile().getUsableSpace();
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (!isAdded()) return;
                if (failure != null) {
                    showArchiveError(failure);
                } else if (scanned.exeCount == 0) {
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.add_game_archive_title)
                            .setMessage(R.string.add_game_archive_no_exe)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    confirmArchiveImport(archive, scanned, free, memoryLimitKb, container);
                }
            });
        });
    }

    private void confirmArchiveImport(File archive, GameArchiveExtractor.Info info, long free,
                                      int memoryLimitKb, Container container) {
        final Activity activity = getActivity();
        if (activity == null) return;
        long required = GameArchiveExtractor.requiredBytes(info);
        if (free < required) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.add_game_archive_no_space_title)
                    .setMessage(getString(R.string.add_game_archive_no_space,
                            com.winlator.cmod.core.DownloadProgressDialog.formatBytes(required),
                            com.winlator.cmod.core.DownloadProgressDialog.formatBytes(free)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        File target = GameArchiveExtractor.targetFolderFor(archive, info);
        String targetPath = toWindowsPath(target, container);
        String archiveName = info.volumes.size() > 1
                ? getString(R.string.add_game_archive_parts, archive.getName(), info.volumes.size())
                : archive.getName();
        String message = getString(R.string.add_game_archive_message,
                archiveName,
                targetPath != null ? targetPath : target.getAbsolutePath(),
                com.winlator.cmod.core.DownloadProgressDialog.formatBytes(info.totalBytes),
                info.fileCount,
                com.winlator.cmod.core.DownloadProgressDialog.formatBytes(free));

        // "Delete the archive" option under the message, on by default
        // 안내문 아래의 "압축 파일 삭제" 선택, 기본으로 켜 둠
        android.widget.CheckBox deleteBox = new android.widget.CheckBox(activity);
        deleteBox.setText(R.string.add_game_archive_delete);
        deleteBox.setChecked(true);
        FrameLayout box = new FrameLayout(activity);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(deleteBox);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.add_game_archive_title)
                .setMessage(message)
                .setView(box)
                .setPositiveButton(R.string.add_game_archive_unpack,
                        (d, w) -> runArchiveExtraction(archive, info, memoryLimitKb, container, deleteBox.isChecked()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runArchiveExtraction(File archive, GameArchiveExtractor.Info info, int memoryLimitKb,
                                      Container container, boolean deleteArchive) {
        final Activity activity = getActivity();
        if (activity == null) return;
        final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        final com.winlator.cmod.core.DownloadProgressDialog dialog = new com.winlator.cmod.core.DownloadProgressDialog(activity);
        dialog.show(R.string.add_game_archive_extracting, () -> {
            cancel.set(true);
            dialog.setLabel(activity.getString(R.string.add_game_archive_cancelling));
        });
        // Large games take minutes; keep the screen from sleeping meanwhile
        // 큰 게임은 몇 분 걸림. 그동안 화면이 꺼지지 않게 함
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final long[] lastPost = {0};
        Executors.newSingleThreadExecutor().execute(() -> {
            File folder = null;
            IOException error = null;
            List<File> candidates = null;
            try {
                folder = GameArchiveExtractor.extract(archive, info, memoryLimitKb, cancel,
                        (doneBytes, totalBytes, doneFiles, totalFiles, name) -> {
                            // At most a few UI updates a second, also for thousands of tiny files
                            // 작은 파일이 수천 개여도 화면 갱신은 1초에 몇 번만 함
                            long now = System.nanoTime();
                            if (name != null && now - lastPost[0] < 150_000_000L) return;
                            lastPost[0] = now;
                            activity.runOnUiThread(() -> {
                                if (cancel.get()) return;
                                dialog.setFileProgress(doneBytes, totalBytes);
                                dialog.setOverall(Math.min(doneFiles + 1, totalFiles), doneFiles, totalFiles);
                            });
                        });
                if (folder != null) candidates = GameArchiveExtractor.gameExeCandidates(folder);
            } catch (IOException e) {
                Log.w("ShortcutCreation", "archive extract failed: " + archive, e);
                error = e;
            } catch (RuntimeException e) {
                Log.w("ShortcutCreation", "archive extract failed: " + archive, e);
                error = GameArchiveExtractor.classify(e);
            }
            final File unpacked = folder;
            final IOException failure = error;
            final List<File> exes = candidates;
            activity.runOnUiThread(() -> {
                dialog.close();
                activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (!isAdded()) return;
                if (failure != null) {
                    showArchiveError(failure);
                    return;
                }
                if (unpacked == null) {
                    AppUtils.showToast(activity, getString(R.string.add_game_archive_cancelled));
                    return;
                }
                Log.i("ShortcutCreation", "unpacked " + archive.getName() + " -> " + unpacked
                        + ", game exe candidates " + (exes != null ? exes.size() : 0));
                if (exes != null && exes.size() == 1) {
                    // One game exe: no question needed / 게임 exe가 하나면 물을 필요 없음
                    boolean ok = addShortcutForExe(exes.get(0), container);
                    if (ok && deleteArchive) deleteArchiveVolumes(info.volumes);
                    return;
                }
                // Several (or only helper) exes: let the user choose inside the new folder
                // exe가 여럿(또는 보조 프로그램뿐)이면 새 폴더 안에서 고르게 함
                pendingUnpackedFolder = unpacked;
                pendingArchiveVolumes = deleteArchive ? info.volumes : null;
                AppUtils.showToast(activity, getString(R.string.add_game_archive_pick_exe, unpacked.getName()));
                launchExePicker(container, unpacked, true);
            });
        });
    }

    private void showArchiveError(IOException e) {
        final Context context = getContext();
        if (context == null) return;
        String detail = String.valueOf(e.getMessage());
        String message;
        if (e instanceof GameArchiveExtractor.ArchiveException) {
            switch (((GameArchiveExtractor.ArchiveException) e).problem) {
                case ENCRYPTED: message = getString(R.string.add_game_archive_err_encrypted); break;
                case UNSUPPORTED: message = getString(R.string.add_game_archive_err_unsupported, detail); break;
                case MEMORY: message = getString(R.string.add_game_archive_err_memory); break;
                case MISSING_VOLUME: message = getString(R.string.add_game_archive_err_volume); break;
                case UNSAFE_PATH: message = getString(R.string.add_game_archive_err_unsafe); break;
                default: message = getString(R.string.add_game_archive_err_corrupt, detail); break;
            }
        } else {
            // Plain I/O error while writing (storage full, folder not writable ...)
            // 쓰는 중의 일반 입출력 오류(저장 공간 가득 참, 쓸 수 없는 폴더 등)
            message = getString(R.string.add_game_archive_err_io, detail);
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.add_game_archive_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // Deletes every volume of the archive; says so, or which one could not go
    // 압축 파일의 모든 조각을 지움. 지웠다고 알리거나, 못 지운 파일을 알림
    private void deleteArchiveVolumes(List<File> volumes) {
        StringBuilder failed = new StringBuilder();
        for (File v : volumes) {
            if (v.exists() && !v.delete()) {
                if (failed.length() > 0) failed.append(", ");
                failed.append(v.getName());
            }
        }
        if (getContext() == null) return;
        if (failed.length() == 0) {
            AppUtils.showToast(getContext(), getString(R.string.add_game_archive_deleted));
        } else {
            Log.w("ShortcutCreation", "could not delete archive: " + failed);
            AppUtils.showToast(getContext(), getString(R.string.add_game_archive_delete_failed, failed.toString()));
        }
    }

    public void loadShortcutsList() {

        ArrayList<Shortcut> shortcuts = new ArrayList<>();
        ArrayList<File> quarantined   = new ArrayList<>();

        // ContainerManager can still throw (e.g. I/O permission issues).
        // Keep the whole call in one try/catch so the UI never dies.
        try {
            for (Container c : manager.getContainers()) {
                for (File f : c.getDesktopDir().listFiles((dir, n) -> n.endsWith(".desktop"))) {
                    try {
                        Shortcut s = new Shortcut(c, f);   // may throw
                        // very cheap logical sanity check
                        if (s.name == null || s.name.trim().isEmpty()) {
                            throw new IllegalStateException("empty name");
                        }
                        shortcuts.add(s);

                    } catch (Throwable t) {               // <-- swallow & quarantine
                        Log.e("ShortcutsFragment", "Bad shortcut: " + f.getAbsolutePath(), t);
                        quarantined.add(f);
                    }
                }
            }

        } catch (Throwable fatal) {
            Log.e("ShortcutsFragment", "Fatal error while scanning shortcuts!", fatal);
            Toast.makeText(getContext(),
                    "Couldn’t load shortcuts (see log).", Toast.LENGTH_LONG).show();
        }

        // ---- UI update ----
//        Collections.sort(shortcuts);           // keep existing order logic
        applyLayoutManager();
        // Icon fix-ups run on the full list; the screen shows the filtered one
        // 아이콘 보정은 전체 목록에서, 화면에는 분류된 목록을 보여 줌
        ArrayList<Shortcut> shown = new ArrayList<>();
        for (Shortcut s : shortcuts) if (passesFilter(s)) shown.add(s);
        recyclerView.setAdapter(new ShortcutsAdapter(shown));
        emptyTextView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        backfillMissingIcons(shortcuts);

        // ---- quarantine report ----
        if (!quarantined.isEmpty()) {
            Toast.makeText(getContext(),
                    quarantined.size() + " shortcut(s) ignored (corrupted):",
                    Toast.LENGTH_LONG).show();

            // Move them out of the way so the crash never happens again.
            for (File bad : quarantined) {
                File dst = new File(bad.getParent(), bad.getName() + ".bad");
                Toast.makeText(getContext(), bad.getName() + " renamed to " + dst.getName(), Toast.LENGTH_LONG).show();
                // ignore return value – it’s a best-effort quarantine
                //noinspection ResultOfMethodCallIgnored
                bad.renameTo(dst);
            }
        }
    }


    // .desktop files already tried this app session. Each shortcut gets one
    // extraction attempt per launch of the app, so a failing exe is not
    // re-read on every list refresh (onResume reloads the list)
    // 이번 앱 실행 중에 이미 시도한 .desktop 파일임. 숏컷마다 앱 실행당 한 번만
    // 추출을 시도해서, 실패하는 exe를 목록을 새로 그릴 때마다(onResume마다)
    // 다시 읽지 않게 함
    private static final java.util.Set<String> iconBackfillTried =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    // Shortcuts without an icon (created before icon extraction existed, or
    // whose exe could not be read then) get their icon pulled from the exe in
    // the background; the list redraws once if any succeeded
    // 아이콘이 없는 숏컷(아이콘 추출 기능 이전에 만들었거나, 그때 exe를 못
    // 읽은 경우)은 백그라운드에서 exe의 아이콘을 뽑아 옴. 하나라도 성공하면
    // 목록을 한 번 다시 그림
    private void backfillMissingIcons(List<Shortcut> shortcuts) {
        final List<Shortcut> todo = new ArrayList<>();
        for (Shortcut s : shortcuts) {
            if (s.icon == null && iconBackfillTried.add(s.file.getAbsolutePath())) todo.add(s);
        }
        // Shortcuts of one container that share an icon file (made
        // before the naming fix) get their own icon named after their .desktop
        // 같은 컨테이너에서 아이콘 파일을 같이 쓰는 바로가기(이름 규칙 수정
        // 이전에 만든 것)는 자기 .desktop 이름으로 된 아이콘을 따로 받음
        final List<Shortcut> shared = new ArrayList<>();
        java.util.Map<String, Integer> useCount = new java.util.HashMap<>();
        for (Shortcut s : shortcuts) {
            if (s.getIconName().isEmpty()) continue;
            String key = s.container.id + "/" + s.getIconName();
            useCount.put(key, useCount.getOrDefault(key, 0) + 1);
        }
        for (Shortcut s : shortcuts) {
            String own = FileUtils.getBasename(s.file.getPath());
            if (s.getIconName().isEmpty() || s.getIconName().equals(own)) continue;
            if (useCount.getOrDefault(s.container.id + "/" + s.getIconName(), 0) > 1
                    && iconBackfillTried.add("shared:" + s.file.getAbsolutePath())) shared.add(s);
        }
        if (!shared.isEmpty()) {
            Executors.newSingleThreadExecutor().execute(() -> {
                boolean any = false;
                for (Shortcut s : shared) {
                    try {
                        if (s.extractIconAs(FileUtils.getBasename(s.file.getPath()))) any = true;
                    } catch (Throwable t) {
                        Log.w("ShortcutsFragment", "Icon split failed: " + s.file.getName(), t);
                    }
                }
                if (!any) return;
                Activity a = getActivity();
                if (a != null) a.runOnUiThread(() -> { if (isAdded()) loadShortcutsList(); });
            });
        }
        if (todo.isEmpty()) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean any = false;
            for (Shortcut s : todo) {
                try {
                    if (s.tryExtractIcon()) any = true;
                } catch (Throwable t) {
                    Log.w("ShortcutsFragment", "Icon backfill failed: " + s.file.getName(), t);
                }
            }
            if (!any) return;
            Activity a = getActivity();
            if (a == null) return;
            // Reload from disk instead of notifying the adapter: onResume may
            // have rebuilt the list while extraction ran, so the adapter can
            // hold newer Shortcut objects than the ones updated here (the
            // reported "icon only appears after reopening a window").
            // Already-tried files are skipped, so this cannot loop
            // 어댑터 알림 대신 디스크에서 다시 읽음: 추출하는 동안 onResume이 목록을
            // 새로 만들었으면 어댑터는 여기서 고친 것보다 새 Shortcut 객체를 들고
            // 있음(보고된 "창을 한 번 다시 띄워야 아이콘이 보임" 현상). 이미 시도한
            // 파일은 건너뛰므로 반복되지 않음
            a.runOnUiThread(() -> {
                if (isAdded()) loadShortcutsList();
            });
        });
    }

    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        // Two item layouts share one adapter. The type is derived from the
        // current view mode, so switching modes makes RecyclerView build fresh
        // holders instead of trying to rebind a tile into a list row.
        // 항목 레이아웃 2종을 어댑터 하나로 다룸. 타입을 현재 보기 모드에서
        // 끌어내므로, 모드를 바꾸면 RecyclerView가 타일을 리스트 행에 재사용하려
        // 하지 않고 새 홀더를 만들어 줌
        private static final int TYPE_TILE = 0;
        private static final int TYPE_LIST = 1;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView cover;
            private final ImageView icon;
            private final TextView name;
            private final TextView subtitle;   // list mode only / 리스트 모드에만 있음

            private ViewHolder(View view) {
                super(view);
                this.cover = view.findViewById(R.id.IVCover);
                this.icon = view.findViewById(R.id.IVIcon);
                this.name = view.findViewById(R.id.TVName);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @Override
        public int getItemViewType(int position) {
            return MODE_TILE.equals(viewMode) ? TYPE_TILE : TYPE_LIST;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutId = (viewType == TYPE_TILE)
                    ? R.layout.neon_shortcut_tile_item
                    : R.layout.neon_shortcut_list_item;
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            // Listeners are cleared so a recycled row never fires the previous
            // shortcut's action after being rebound.
            // 재사용된 행이 재바인딩 된 뒤에 이전 숏컷의 동작을 실행하지
            // 않도록 리스너를 비움
            holder.itemView.setOnClickListener(null);
            holder.itemView.setOnLongClickListener(null);
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);

            // Tile picture: custom tile image, else the icon filling the
            // whole tile. The small centred icon is only the last resort
            // when the shortcut has no icon at all
            // 타일 그림: 사용자 지정 타일 이미지, 없으면 아이콘이 타일 전체를
            // 채움. 가운데 작은 아이콘은 숏컷에 아이콘이 아예 없을 때만 씀
            if (bindTilePicture(holder.cover, item)) {
                holder.icon.setVisibility(View.GONE);
            } else {
                holder.icon.setVisibility(View.VISIBLE);
                holder.icon.setImageResource(R.drawable.ic_app_logo);
            }
            // Steam games without a custom tile image show their Steam cover;
            // a tile image set by the user still wins
            // 사용자 지정 타일 이미지가 없는 스팀 게임은 스팀 표지를 보여 줌.
            // 사용자가 정한 타일 이미지가 있으면 그게 우선임
            holder.cover.setTag(item.file.getAbsolutePath());
            final int steamId = parseIntSafe(item.getExtra("steamAppId"));
            if (steamId > 0 && !item.hasCustomTileImage()) {
                Bitmap capsule = com.winlator.cmod.steam.SteamArtwork.cachedCapsule(steamId);
                if (capsule != null) {
                    holder.cover.setImageBitmap(capsule);
                    holder.icon.setVisibility(View.GONE);
                } else {
                    final String tag = item.file.getAbsolutePath();
                    final Context app = holder.itemView.getContext().getApplicationContext();
                    ART_POOL.execute(() -> {
                        Bitmap b = com.winlator.cmod.steam.SteamArtwork.loadCapsule(app, steamId);
                        Activity a = getActivity();
                        if (b == null || a == null) return;
                        a.runOnUiThread(() -> {
                            if (tag.equals(holder.cover.getTag())) {
                                holder.cover.setImageBitmap(b);
                                holder.icon.setVisibility(View.GONE);
                            }
                        });
                    });
                }
            }

            holder.name.setText(item.name);
            if (holder.subtitle != null) holder.subtitle.setText(buildSubtitle(item));

            // Tap opens the detail sheet, long-press keeps the old popup menu.
            // The old build opened the menu on a plain tap, which left no room
            // for a primary action.
            // 탭하면 상세창, 길게 누르면 기존 팝업 메뉴임.
            // 예전에는 그냥 탭하면 메뉴가 떠서 주 동작을 둘 자리가 없었음
            holder.itemView.setOnClickListener(v -> showShortcutDetail(item));
            // Long-press shows a line running round the tile border;
            // the menu opens once the line has gone all the way round
            // 길게 누르면 타일 테두리를 따라 선이 돌고, 한 바퀴를 다 돌면 메뉴가 열림
            holder.itemView.setOnLongClickListener(null);
            holder.itemView.setLongClickable(false);
            attachBorderTrace(holder.itemView, item);

            // The per-row dark-mode recolouring is gone on purpose: the item
            // layouts pull their colours from ?attr/neonText now.
            // 행마다 다크모드 색을 칠하던 코드는 일부러 없앰. 이제 항목
            // 레이아웃이 ?attr/neonText에서 색을 가져옴
        }

        // Border trace: ACTION_DOWN starts a 600 ms line around the border,
        // lifting / moving away / scrolling cancels it, completion opens the
        // menu and swallows the release so the tap does not also open the sheet
        // 테두리 그리기: ACTION_DOWN에서 600ms 동안 테두리를 따라 선을 그림. 손을 떼거나
        // 벗어나거나 스크롤하면 취소되고, 다 그리면 메뉴를 열고 손 뗌을 삼켜서 탭으로
        // 상세창까지 열리지 않게 함
        private void attachBorderTrace(final View itemView, final Shortcut item) {
            final float density = itemView.getResources().getDisplayMetrics().density;
            android.util.TypedValue tv = new android.util.TypedValue();
            itemView.getContext().getTheme().resolveAttribute(R.attr.neonAccent, tv, true);
            final com.winlator.cmod.widget.BorderTraceDrawable trace =
                    new com.winlator.cmod.widget.BorderTraceDrawable(tv.data, 3 * density, 16 * density);
            final int slop = android.view.ViewConfiguration.get(itemView.getContext()).getScaledTouchSlop();
            final android.animation.ValueAnimator[] anim = {null};
            final boolean[] fired = {false};
            final float[] down = new float[2];
            itemView.setForeground(trace);
            itemView.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        fired[0] = false;
                        down[0] = e.getX();
                        down[1] = e.getY();
                        if (anim[0] != null) anim[0].cancel();
                        anim[0] = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(600);
                        anim[0].addUpdateListener(a -> trace.setProgress((float) a.getAnimatedValue()));
                        anim[0].addListener(new android.animation.AnimatorListenerAdapter() {
                            private boolean cancelled;
                            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                            @Override public void onAnimationEnd(android.animation.Animator a) {
                                if (cancelled) return;
                                fired[0] = true;
                                trace.setProgress(0f);
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                                showListItemMenu(v, item);
                            }
                        });
                        anim[0].start();
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX() - down[0]) > slop || Math.abs(e.getY() - down[1]) > slop) cancelTrace();
                        return fired[0];
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        cancelTrace();
                        if (fired[0]) {
                            v.setPressed(false);
                            return true;
                        }
                        return false;
                }
                return false;
            });
            final Runnable cancel = () -> {
                if (anim[0] != null) anim[0].cancel();
                trace.setProgress(0f);
            };
            itemView.setTag(R.id.BTMenu, cancel);
        }

        private void cancelTrace() {
            // Every bound tile keeps its own cancel action in a view tag
            // 바인딩된 타일마다 자기 취소 동작을 뷰 태그에 둠
            if (recyclerView == null) return;
            for (int i = 0; i < recyclerView.getChildCount(); i++) {
                Object c = recyclerView.getChildAt(i).getTag(R.id.BTMenu);
                if (c instanceof Runnable) ((Runnable) c).run();
            }
        }

        private String buildSubtitle(Shortcut item) {
            String container = item.container.getName();
            long last = parseLongSafe(item.getExtra("lastPlayed", "0"));
            if (last <= 0L) return container;
            return container + "  \u00b7  " + DateUtils.getRelativeTimeSpanString(
                    last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.shortcut_popup_menu);
            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.shortcut_run) {
                    runFromShortcut(shortcut);
                }
                else if (itemId == R.id.shortcut_settings) {
                    (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                }
                else if (itemId == R.id.shortcut_multiplayer) {
                    // "Multiplayer settings": which physical controller is which
                    // player. Works outside a game (no WinHandler) and is saved
                    // by the controller manager for the next launch
                    // "다인 설정": 어느 컨트롤러가 몇 번 플레이어인지 정함. 게임 밖에서도
                    // 동작하고(WinHandler 없음) 컨트롤러 관리자가 저장해서 다음 실행에
                    // 쓰임
                    com.winlator.cmod.contentdialog.ControllerAssignmentDialog.show(context);
                }
                else if (itemId == R.id.shortcut_remove) {
                    // Same path as the detail sheet's Remove button
                    // 상세창의 삭제 버튼과 같은 경로임
                    confirmRemoveShortcut(shortcut);
                }
                else if (itemId == R.id.shortcut_clone_to_container) {
                    // Every container except the one the shortcut is already in
                    // 숏컷이 이미 들어 있는 컨테이너를 뺀 나머지 전부
                    ArrayList<Container> containers = new ArrayList<>();
                    for (Container c : new ContainerManager(context).getContainers()) {
                        if (c.id != shortcut.container.id) containers.add(c);
                    }
                    if (containers.isEmpty()) {
                        Toast.makeText(context, R.string.shortcut_clone_no_other_container, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    showContainerSelectionDialog(containers, selectedContainer -> {
                        // Same refusals as moving: an exe on this container's C:
                        // cannot run elsewhere, and an existing same-name shortcut
                        // in the target would be overwritten
                        // 이동과 같은 거부 조건임: 이 컨테이너의 C:에 있는 exe는 다른
                        // 곳에서 실행 안 되고, 대상에 같은 이름이 있으면 덮어써짐
                        int checked = com.winlator.cmod.container.ShortcutMover.check(shortcut, selectedContainer);
                        if (checked == com.winlator.cmod.container.ShortcutMover.C_DRIVE_BLOCKED) {
                            Toast.makeText(context, R.string.shortcut_move_c_drive_blocked, Toast.LENGTH_LONG).show();
                        } else if (checked == com.winlator.cmod.container.ShortcutMover.NAME_EXISTS) {
                            Toast.makeText(context, R.string.shortcut_move_name_exists, Toast.LENGTH_LONG).show();
                        } else if (shortcut.cloneToContainer(selectedContainer)) {
                            Toast.makeText(context, R.string.shortcut_cloned, Toast.LENGTH_SHORT).show();
                            loadShortcutsList();
                        } else {
                            Toast.makeText(context, R.string.shortcut_clone_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                else if (itemId == R.id.shortcut_export_to_frontend) {
                    exportShortcutToFrontend(shortcut);
                }
                else if (itemId == R.id.shortcut_properties) {
                    showShortcutProperties(shortcut);
                }
                else if (itemId == R.id.shortcut_save_export) {
                    exportGameSaves(shortcut);
                }

                // "Set as Favorite" and "Add to home screen" were removed: the
                // favorite was written to prefs but nothing ever read it, and
                // the home-screen entry did not work on device (2026-10-01)
                // "즐겨찾기"와 "홈 화면에 추가"는 지움: 즐겨찾기는 설정에 쓰기만 하고
                // 읽는 곳이 없었고, 홈 화면 추가는 실기기에서 동작하지 않았음(2026-10-01)
                return true;
            });
            listItemMenu.show();
        }

        // Define the listener interface for selecting a container
        public interface OnContainerSelectedListener {
            void onContainerSelected(Container container);
        }

        private void showContainerSelectionDialog(ArrayList<Container> containers, OnContainerSelectedListener listener) {
            // Create an AlertDialog to show the list of containers
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle(R.string.shortcut_select_container);

            // Create an array of container names to display
            String[] containerNames = new String[containers.size()];
            for (int i = 0; i < containers.size(); i++) {
                containerNames[i] = containers.get(i).getName();
            }

            // Set up the list in the dialog
            builder.setItems(containerNames, (dialog, which) -> {
                // Call the listener when a container is selected
                listener.onContainerSelected(containers.get(which));
            });

            // Show the dialog
            builder.show();
        }

        private void runFromShortcut(Shortcut shortcut) {
            // Delegates so that every launch path - popup menu and the START
            // button in the detail sheet - records playtime the same way.
            // 팝업 메뉴든 상세창의 시작 버튼이든, 모든 실행 경로가 똑같이
            // 플레이 시간을 기록하도록 위임함
            ShortcutsFragment.this.launchShortcut(shortcut);
        }

        // Turns the export folder into a short label for the guide (e.g. "Download/.EZlator/Frontend")
        // 내보내기 폴더를 안내문용 짧은 표기로 바꿈(예: "Download/.EZlator/Frontend")
        private String frontendDirDisplayPath(File dir) {
            String full = dir.getAbsolutePath();
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            // Inside internal storage -> path relative to its root, else the full path
            // 내장 저장소 안이면 루트 기준 상대 경로, 아니면 전체 경로를 그대로 씀
            if (full.startsWith(root + "/")) return full.substring(root.length() + 1);
            return full;
        }

        private void exportShortcutToFrontend(Shortcut shortcut) {
            // Check for a custom frontend export path in shared preferences
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            String uriString = sharedPreferences.getString("frontend_export_uri", null);

            File frontendDir;

            if (uriString != null) {
                // If custom URI is set, use it
                Uri folderUri = Uri.parse(uriString);
                DocumentFile pickedDir = DocumentFile.fromTreeUri(getContext(), folderUri);

                if (pickedDir == null || !pickedDir.canWrite()) {
                    Toast.makeText(getContext(), R.string.shortcut_export_cannot_write, Toast.LENGTH_SHORT).show();
                    return;
                }

                // Convert DocumentFile to a File object for further processing
                frontendDir = new File(FileUtils.getFilePathFromUri(getContext(), folderUri));
            } else {
                // Default to Download/.EZlator/Frontend if no custom URI is set
                // 사용자 지정 폴더가 없으면 Download/.EZlator/Frontend를 사용함
                frontendDir = com.winlator.cmod.core.AppDirs.downloads("Frontend");
                if (!frontendDir.exists() && !frontendDir.mkdirs()) {
                    Toast.makeText(getContext(), R.string.shortcut_export_dir_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
            }


            // Check for FRONTEND_INSTRUCTIONS.txt
            File instructionsFile = new File(frontendDir, "FRONTEND_INSTRUCTIONS.txt");
            // Folder shown in the guide = the real export folder, relative to internal storage
            // 안내문에 적는 폴더 = 실제 내보내기 폴더(내장 저장소 기준 상대 경로)
            String frontendDirLabel = frontendDirDisplayPath(frontendDir);
            if (true) {
                try (FileWriter writer = new FileWriter(instructionsFile, false)) {
                    writer.write("Instructions for adding EZlator shortcuts to Frontends:\n\n");
                    writer.write("Daijisho:\n\n");
                    writer.write("1. Open Daijisho\n");
                    writer.write("2. Navigate to the Settings tab.\n");
                    writer.write("3. Navigate to Settings\\Library\n");
                    writer.write("4. Select, Import from Pegasus\n");
                    writer.write("5. Add the metadata.pegasus.txt file located in this directory (" + frontendDirLabel + ")\n");
                    writer.write("6. Set the Sync path to " + frontendDirLabel + "\n");
                    writer.write("7. Start your game!\n\n");
                    writer.write("Beacon:\n\n");
                    writer.write("1. Navigate to Settings\n");
                    writer.write("2. Click the + Icon\n");
                    writer.write("3. Set the following values:\n\n");
                    writer.write("Platform Type: Custom\n");
                    writer.write("Name: Windows (or EZlator, whatever you prefer)\n");
                    writer.write("Short name: windows\n");
                    // Exported guide names this app as the player app
                    // 내보내는 안내문에 실행 앱으로 이 앱(EZlator)을 표기함
                    writer.write("Player app: Select EZlator\n");
                    writer.write("ROMs folder: Use Android FilePicker to select the " + frontendDirLabel + " directory\n");
                    writer.write("Expand Advanced:\n");
                    writer.write("File handling: Default\n");
                    writer.write("Use custom launch: True\n");
                    writer.write("am start command: am start -n " + getContext().getPackageName() + "/com.winlator.cmod.XServerDisplayActivity -e shortcut_path {file_path}\n\n");
                    writer.write("4. Click Save\n");
                    writer.write("5. Scan the folder for your game\n");
                    writer.write("6. Launch your game!\n");
                    writer.flush();
                    Log.d("ShortcutsFragment", "FRONTEND_INSTRUCTIONS.txt created successfully.");
                } catch (IOException e) {
                    Log.e("ShortcutsFragment", "Failed to create FRONTEND_INSTRUCTIONS.txt", e);
                }
            }

            // Check for metadata.pegasus.txt
            File metadataFile = new File(frontendDir, "metadata.pegasus.txt");
            try (FileWriter writer = new FileWriter(metadataFile, false)) {
                writer.write("collection: Windows\n");
                writer.write("shortname: windows\n");
                writer.write("extensions: desktop\n");
                writer.write("launch: am start\n");
                // Component = <applicationId>/<class>; the class keeps the Java namespace
                // 컴포넌트 = <applicationId>/<클래스>; 클래스는 Java 네임스페이스 그대로임
                writer.write("  -n " + getContext().getPackageName() + "/com.winlator.cmod.XServerDisplayActivity\n");
                writer.write("  -e shortcut_path {file.path}\n");
                writer.write("  --activity-clear-task\n");
                writer.write("  --activity-clear-top\n");
                writer.write("  --activity-no-history\n");
                writer.flush();
                Log.d("ShortcutsFragment", "metadata.pegasus.txt created or updated successfully.");
            } catch (IOException e) {
                Log.e("ShortcutsFragment", "Failed to create or update metadata.pegasus.txt", e);
            }

            // Create the export file in the Frontend directory
            File exportFile = new File(frontendDir, shortcut.file.getName());

            boolean fileExists = exportFile.exists();
            boolean containerIdFound = false;

            try {
                List<String> lines = new ArrayList<>();

                // Read the original file or existing file if it exists
                // Same charset fix as FileUtils.readLines()
                // FileUtils.readLines()와 동일한 문자셋 수정
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(shortcut.file), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("container_id=")) {
                            // Replace the existing container_id line
                            lines.add("container_id=" + shortcut.container.id);
                            containerIdFound = true;
                        } else {
                            lines.add(line);
                        }
                    }
                }

                // If no container_id was found, add it
                if (!containerIdFound) {
                    lines.add("container_id=" + shortcut.container.id);
                }

                // Write the contents to the export file
                try (FileWriter writer = new FileWriter(exportFile, false)) {
                    for (String line : lines) {
                        writer.write(line + "\n");
                    }
                    writer.flush();
                }

                Log.d("ShortcutsFragment", "Shortcut exported successfully to " + exportFile.getPath());

                // Determine the toast message
                String message;
                if (fileExists) {
                    message = getString(R.string.shortcut_export_updated, exportFile.getPath());
                } else {
                    message = getString(R.string.shortcut_export_done, exportFile.getPath());
                }

                // Show a toast message to the user
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();

            } catch (IOException e) {
                Log.e("ShortcutsFragment", "Failed to export shortcut", e);
                Toast.makeText(getContext(), R.string.shortcut_export_failed, Toast.LENGTH_LONG).show();
            }
        }

        private void showShortcutProperties(Shortcut shortcut) {
            // Reads the same playtimeSec/playCount extras XServerDisplayActivity
            // writes during play (see its persistPlaytimeToShortcut()), instead
            // of the old separate "playtime_stats" SharedPreferences store this
            // dialog used to keep on its own - that second store just drifted
            // from the real number over time. Note the unit change too: the old
            // store kept milliseconds, playtimeSec is exactly what its name says
            // XServerDisplayActivity가 플레이 중에 쓰는 것과 같은 playtimeSec/
            // playCount extra를 읽음(그쪽의 persistPlaytimeToShortcut() 참고).
            // 예전에 이 다이얼로그가 따로 갖고 있던 "playtime_stats"
            // SharedPreferences는 시간이 지나면서 실제 값과 어긋나기만 했음.
            // 단위도 바뀌었음에 유의: 예전 저장소는 밀리초였고, playtimeSec은
            // 이름 그대로 초 단위임
            long totalPlaytimeSec = parseLongSafe(shortcut.getExtra("playtimeSec", "0"));
            long playCount = parseLongSafe(shortcut.getExtra("playCount", "0"));

            // Convert playtime to human-readable format
            long seconds = totalPlaytimeSec % 60;
            long minutes = (totalPlaytimeSec / 60) % 60;
            long hours = (totalPlaytimeSec / 3600) % 24;
            long days = totalPlaytimeSec / 86400;

            String playtimeFormatted = String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);

            // Create the properties dialog
            ContentDialog dialog = new ContentDialog(getContext(), R.layout.shortcut_properties_dialog);
            dialog.setTitle(R.string.shortcut_properties);

            TextView playCountTextView = dialog.findViewById(R.id.play_count);
            TextView playtimeTextView = dialog.findViewById(R.id.playtime);

            playCountTextView.setText(getString(R.string.shortcut_properties_play_count, playCount));
            playtimeTextView.setText(getString(R.string.shortcut_properties_playtime, playtimeFormatted));

            Button resetPropertiesButton = dialog.findViewById(R.id.reset_properties);
            resetPropertiesButton.setText(R.string.shortcut_properties_reset);

            resetPropertiesButton.setOnClickListener(v -> {
                shortcut.putExtra("playtimeSec", "0");
                shortcut.putExtra("playCount", "0");
                shortcut.saveData();
                Toast.makeText(getContext(), R.string.shortcut_properties_reset_done, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            // Two buttons only - "Reset record" and "Close" under it;
            // the dialog's own Cancel / OK bar is hidden
            // 버튼은 "기록 초기화"와 그 아래 "닫기" 두 개뿐임. 대화상자 자체의
            // 취소 / 확인 줄은 숨김
            View confirm = dialog.findViewById(R.id.BTConfirm);
            if (confirm != null) confirm.setVisibility(View.GONE);
            View cancelBt = dialog.findViewById(R.id.BTCancel);
            if (cancelBt != null) cancelBt.setVisibility(View.GONE);
            dialog.findViewById(R.id.BTCloseProperties).setOnClickListener(v -> dialog.dismiss());

            dialog.show();
        }

    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);

        return new ShortcutInfo.Builder(getActivity(), uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")),
                    context.getString(R.string.shortcut_not_available));
        } catch (Exception e) {}
    }

    public void updateShortcutOnScreen(String shortLabel,
                                       String longLabel,
                                       int    containerId,
                                       String shortcutPath,
                                       Icon   icon,
                                       String uuid) {

        ShortcutManager sm =
                androidx.core.content.ContextCompat.getSystemService(
                        requireContext(), ShortcutManager.class);

        if (sm == null) {                    // ⇦ grace-fully bail out on devices
            Log.w("ShortcutsFragment",       //    that don’t expose ShortcutManager
                    "ShortcutManager not available; cannot update pinned shortcut");
            return;
        }

        for (ShortcutInfo info : sm.getPinnedShortcuts()) {
            if (uuid.equals(info.getId())) {
                sm.updateShortcuts(Collections.singletonList(
                        buildScreenShortCut(shortLabel, longLabel,
                                containerId, shortcutPath, icon, uuid)));
                break;
            }
        }
    }

    private static boolean safeDelete(@Nullable File f) {
        try {
            return f != null && f.exists() && f.delete();
        } catch (Exception e) {
            Log.e("ShortcutsFragment", "Delete failed for: " + (f != null ? f.getAbsolutePath() : "null"), e);
            return false;
        }
    }

    /** Delete a sibling .lnk that matches the .desktop basename. */
    // ==================== Neon shortcuts screen ====================
    // ==================== 네온 숏컷 화면 ====================

    // ==================== Category filter ====================
    // ==================== 분류 필터 ====================
    // Steam = shortcut has a steamAppId; Other = everything else
    // 스팀 = steamAppId가 있는 바로가기, 일반 = 그 밖의 전부
    // Shared pool for Steam artwork loads (a new executor per tile would leave
    // an idle thread behind for every bind)
    // 스팀 아트워크 로딩용 공용 풀(타일마다 새 실행기를 만들면 바인딩할 때마다 쉬는
    // 스레드가 하나씩 남음)
    private static final java.util.concurrent.ExecutorService ART_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3);

    private static final String PREF_FILTER = "shortcut_filter";
    private static final String FILTER_ALL = "all";
    private static final String FILTER_STEAM = "steam";
    private static final String FILTER_OTHER = "other";
    private String filter = FILTER_ALL;
    private View btFilterAll, btFilterSteam, btFilterOther;

    private void setFilter(String f) {
        filter = f;
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(PREF_FILTER, f).apply();
        applyFilterChips();
        loadShortcutsList();
    }

    private void applyFilterChips() {
        // Chips replaced by the View menu; kept for existing callers
        // 칩은 보기 메뉴로 바뀜. 기존 호출부 때문에 남겨 둠
    }

    // Windows-Explorer-style view menu: category, layout, tiles per row.
    // Each group has its own divider; the current choices are ticked
    // 윈도우 탐색기식 보기 메뉴: 분류, 배치, 줄당 타일 수. 그룹마다 구분선이 있고
    // 현재 선택에 표시가 붙음
    private void showViewMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(requireContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.shortcut_view_menu, popup.getMenu());
        androidx.core.view.MenuCompat.setGroupDividerEnabled(popup.getMenu(), true);
        android.view.Menu m = popup.getMenu();
        m.findItem(FILTER_STEAM.equals(filter) ? R.id.view_filter_steam
                : FILTER_OTHER.equals(filter) ? R.id.view_filter_other : R.id.view_filter_all).setChecked(true);
        m.findItem(MODE_LIST.equals(viewMode) ? R.id.view_mode_list : R.id.view_mode_tile).setChecked(true);
        m.findItem(tileColumns(requireContext()) == 3 ? R.id.view_columns_3 : R.id.view_columns_5).setChecked(true);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.view_filter_all) setFilter(FILTER_ALL);
            else if (id == R.id.view_filter_steam) setFilter(FILTER_STEAM);
            else if (id == R.id.view_filter_other) setFilter(FILTER_OTHER);
            else if (id == R.id.view_mode_tile) setViewMode(MODE_TILE);
            else if (id == R.id.view_mode_list) setViewMode(MODE_LIST);
            else if (id == R.id.view_columns_3 || id == R.id.view_columns_5) {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .putString(PREF_TILE_COLUMNS, id == R.id.view_columns_3 ? "3" : "5").apply();
                applyLayoutManager();
            }
            return true;
        });
        popup.show();
    }

    // Tiles per row (3 or 5, default 5), shared with the Steam page. Narrow
    // screens still get fewer: 115dp per column, at least 2
    // 줄당 타일 수(3 또는 5, 기본 5). 스팀 페이지와 함께 씀. 좁은 화면은 여전히 더
    // 적게 들어감: 열당 115dp, 최소 2
    public static final String PREF_TILE_COLUMNS = "tile_columns";

    public static int tileColumns(Context context) {
        String v = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_TILE_COLUMNS, "5");
        return "3".equals(v) ? 3 : 5;
    }

    public static int gridSpan(Context context) {
        int auto = context.getResources().getConfiguration().screenWidthDp / 115;
        return Math.max(2, Math.min(tileColumns(context), auto));
    }

    private boolean passesFilter(Shortcut s) {
        if (FILTER_ALL.equals(filter)) return true;
        boolean steam = !s.getExtra("steamAppId").isEmpty();
        return FILTER_STEAM.equals(filter) == steam;
    }

    private void setViewMode(String mode) {
        if (mode.equals(viewMode)) return;
        viewMode = mode;
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putString(PREF_VIEW_MODE, mode).apply();
        applyViewModeChips();
        applyLayoutManager();
        if (recyclerView != null && recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    // The chip drawable reads the selected state, so flipping that state is
    // all the visual switching this needs.
    // 칩 드로어블이 selected 상태를 읽으므로, 상태만 바꿔 주면 화면 전환은 끝남
    private void applyViewModeChips() {
        if (btViewTile != null) btViewTile.setSelected(MODE_TILE.equals(viewMode));
        if (btViewList != null) btViewList.setSelected(MODE_LIST.equals(viewMode));
    }

    // Column count comes from the screen width instead of being fixed at 3, so
    // a tablet or landscape does not end up with three giant tiles.
    // 열 수를 3으로 고정하지 않고 화면 너비에서 끌어냄. 태블릿이나 가로 모드에서
    // 거대한 타일 3개가 되는 것을 막음
    private void applyLayoutManager() {
        if (recyclerView == null) return;
        if (MODE_TILE.equals(viewMode)) {
            // 115dp per column, clamped 2..5.
            // 360dp portrait -> 3, a 800dp landscape phone -> 6 before the clamp
            // pulls it to 5. The cap exists because past five columns the cover
            // art is too small to recognise at a glance, which is the whole
            // point of tile mode.
            // 열당 115dp 기준이고 2~5로 묶음.
            // 360dp 세로에서 3, 800dp 가로 폰에서는 6이 나오지만 상한이 5로 끌어내림.
            // 상한을 둔 이유는 5열을 넘기면 커버가 너무 작아져 한눈에 알아볼 수 없기
            // 때문임. 그게 타일 모드의 존재 이유임
            int span = gridSpan(requireContext());
            recyclerView.setLayoutManager(new GridLayoutManager(recyclerView.getContext(), span));
        } else {
            recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        }
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    // Hours:minutes. Seconds are dropped on purpose - nobody compares play
    // sessions at second resolution, and the extra digits crowd the stat row.
    // 시:분 표기임. 초는 일부러 버림. 초 단위로 플레이 시간을 비교하는 사람은
    // 없고, 자릿수만 늘어나 정보 줄이 빽빽해짐
    // Three-line config summary for the detail sheet: Wine/Proton version,
    // DXVK+VKD3D (or WineD3D) version, graphics driver. Reads shortcut-level
    // overrides first and falls back to the container's own setting, exactly
    // the same precedence XServerDisplayActivity uses right before launch
    // (see its onCreate around the "if (shortcut != null)" block) - so this
    // always shows the values that will actually be used, not just the
    // container defaults.
    // 상세창용 3줄 구성 요약임: Wine/Proton 버전, DXVK+VKD3D(또는 WineD3D)
    // 버전, 그래픽 드라이버. 숏컷별 재정의를 먼저 읽고 없으면 컨테이너 자체
    // 설정으로 떨어짐. XServerDisplayActivity가 실행 직전에 쓰는 것과 정확히
    // 같은 우선순위임("if (shortcut != null)" 블록 근처) - 그래서 컨테이너
    // 기본값이 아니라 실제로 쓰일 값이 항상 보임
    private static String buildVersionSummary(Shortcut shortcut) {
        Container container = shortcut.container;

        String wineVersion = container.getWineVersion();

        String dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
        String dxLine;
        if ("dxvk-vkd3d".equals(dxwrapper)) {
            String dxwrapperConfigStr = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            KeyValueSet dxCfg = DXVKConfig.parseConfig(dxwrapperConfigStr);
            String dxvkVer = dxCfg.get("version", "?");
            String vkd3dVer = dxCfg.get("vkd3dVersion", "?");
            dxLine = "DXVK " + dxvkVer + " + VKD3D " + vkd3dVer;
        } else {
            dxLine = "WineD3D";
        }

        String graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
        String graphicsDriverConfigStr = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
        HashMap<String, String> gfxCfg = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfigStr);
        String gfxVer = gfxCfg.getOrDefault("version", graphicsDriver);
        String gfxLine = graphicsDriver + " " + gfxVer;

        return wineVersion + "\n" + dxLine + "\n" + gfxLine;
    }

    private static String formatPlaytime(long seconds) {
        if (seconds <= 0L) return "0:00";
        return String.format(Locale.US, "%d:%02d", seconds / 3600L, (seconds % 3600L) / 60L);
    }

    // Single launch entry point. lastPlayed is written before handing off,
    // because once the game activity is on top this fragment gets no
    // further chance to write anything.
    //
    // Playtime itself is no longer tracked here. It used to be: this method
    // stamped a launch time to prefs, and onResume() (see above) diffed
    // wall-clock time against it when the user came back, as a rough stand-in
    // for "how long did that session run". XServerDisplayActivity is what is
    // actually on screen while the game plays, and it now writes the same
    // shortcut's playtimeSec extra directly, continuously and much more
    // accurately (see XServerDisplayActivity.persistPlaytimeToShortcut()).
    // Keeping this fragment's coarse estimate too would have added the same
    // time to the same field twice.
    // 유일한 실행 진입점임. 넘기기 전에 마지막 실행일을 기록함. 게임
    // 액티비티가 위로 올라오면 이 프래그먼트는 더 이상 쓸 기회가 없음
    //
    // 플레이시간은 이제 여기서 재지 않음. 예전엔: 이 메서드가 실행 시각을
    // 설정에 남기고, 위 onResume()이 사용자가 돌아왔을 때 그 시각과
    // 현재 시각의 차이를 재서 "그 세션이 얼마나 돌았는지"의 대략적인
    // 대용으로 씀. 게임이 도는 동안 실제로 화면에 떠 있는 건
    // XServerDisplayActivity고, 이제 거기서 같은 숏컷의 playtimeSec
    // extra에 직접, 계속, 훨씬 정확하게 씀(XServerDisplayActivity.
    // persistPlaytimeToShortcut() 참고). 이 프래그먼트의 거친 추정치까지
    // 남겨 뒀다면 같은 시간을 같은 필드에 두 번 더했을 것임
    void launchShortcut(Shortcut shortcut) {
        Activity activity = getActivity();
        if (activity == null) return;

        // Steam-linked launch notice (once, unless "don't show again"). Steam lets an
        // account PLAY in one place only: starting the game here disconnects a Steam
        // client running elsewhere with "Logged In Elsewhere" (PC log 2026-09-27
        // 21:59:57). The desktop client asks first; the headless agent cannot, so
        // the app tells the user instead.
        // Steam 연동 실행 안내(한 번, "다시 보지 않기" 전까지). Steam은 한 계정이 한
        // 곳에서만 플레이하게 함: 여기서 게임을 시작하면 다른 곳의 Steam 연결이
        // "Logged In Elsewhere"로 끊김(PC 로그 2026-09-27 21:59:57). PC 클라이언트는
        // 먼저 물어보지만 화면 없는 에이전트는 못 물어보므로 앱이 대신 알려줌
        String appId = shortcut.getExtra("steamAppId");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        // Steam refused the saved token (expired / revoked): the game would start
        // without Steam, so say so first and point to the Steam page to sign in again
        // 스팀이 저장된 토큰을 거부함(만료 / 취소): 게임이 스팀 없이 실행되므로 먼저
        // 알리고, 스팀 화면에서 다시 로그인하도록 안내함
        if (!appId.isEmpty() && new com.winlator.cmod.steam.SteamAccountStore(activity).isTokenRejected()) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.steam_launch_notice_title)
                    .setMessage(R.string.steam_token_expired_launch)
                    .setPositiveButton(R.string.steam_launch_anyway, (d, w) -> startShortcut(activity, shortcut))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (!appId.isEmpty() && new com.winlator.cmod.steam.SteamAccountStore(activity).isLoggedIn()
                && !prefs.getBoolean("steam_launch_notice_hidden", false)) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.steam_launch_notice_title)
                    .setMessage(R.string.steam_launch_notice)
                    .setPositiveButton(R.string.steam_launch_notice_ok, (d, w) -> startShortcut(activity, shortcut))
                    .setNeutralButton(R.string.steam_launch_notice_hide, (d, w) -> {
                        prefs.edit().putBoolean("steam_launch_notice_hidden", true).apply();
                        startShortcut(activity, shortcut);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        startShortcut(activity, shortcut);
    }

    private void startShortcut(Activity activity, Shortcut shortcut) {
        shortcut.putExtra("lastPlayed", String.valueOf(System.currentTimeMillis()));
        shortcut.saveData();

        // XR branch removed with XrActivity: always launch the normal display
        // XrActivity와 함께 XR 분기를 제거함: 항상 일반 화면으로 실행함
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", shortcut.container.id);
        intent.putExtra("shortcut_path", shortcut.file.getPath());
        intent.putExtra("shortcut_name", shortcut.name);
        intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
        activity.startActivity(intent);
    }

    // Puts a halo (shadow with no offset) around every TextView under root, in
    // the colour opposite to the text: white halo for dark text (light theme),
    // black halo for light text (dark theme). "skip" keeps its own style (the
    // title already has the neon glow)
    // root 아래의 모든 TextView에 테두리(위치 이동 없는 그림자)를 넣음. 글자와
    // 반대색임: 어두운 글자(라이트 테마)는 흰 테두리, 밝은 글자(다크 테마)는
    // 검은 테두리. skip은 자기 모양을 유지함(제목은 이미 네온 발광이 있음)
    private void applyTextHalo(View root, boolean darkTheme, View skip) {
        if (root == skip) return;
        if (root instanceof TextView) {
            // Kept soft (2dp, ~60%) so it helps on bright art without showing as an outline
            // 밝은 그림에서 도움이 되면서 테두리처럼 보이지 않게 약하게 둠(2dp, 약 60%)
            float radius = 2f * root.getResources().getDisplayMetrics().density;
            ((TextView) root).setShadowLayer(radius, 0f, 0f, darkTheme ? 0x99000000 : 0x99FFFFFF);
            return;
        }
        // Walk down into layouts (stats row, layer button)
        // 레이아웃 안쪽으로 내려감(정보 줄, 레이어 버튼)
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) applyTextHalo(g.getChildAt(i), darkTheme, skip);
        }
    }

    // The detail sheet. Cover art sits behind the content as the background,
    // dimmed by the theme scrim so the text stays legible over any image.
    // 상세창임. 커버 이미지가 내용 뒤에 배경으로 깔리고, 테마의 막이 적당히
    // 어둡게 덮어 어떤 그림 위에서도 글자가 읽힘
    void showShortcutDetail(final Shortcut shortcut) {
        final Context context = getContext();
        if (context == null) return;

        final View view = LayoutInflater.from(context)
                .inflate(R.layout.neon_shortcut_detail_dialog, null);

        // Background: custom cover first, then the tile image, then a faint
        // icon. The icon stays faint because a blown-up icon at full strength
        // dominates the sheet
        // 배경: 사용자 지정 커버 우선, 없으면 타일 이미지, 그것도 없으면 연한
        // 아이콘임. 크게 늘린 아이콘을 진하게 두면 창을 잡아먹어서 연하게 둠
        ImageView cover = view.findViewById(R.id.IVDetailCover);
        Bitmap art = shortcut.getCoverArt();
        if (art == null) art = shortcut.getTileImage();
        if (art != null) {
            cover.setImageBitmap(art);
        } else if (shortcut.icon != null) {
            cover.setImageBitmap(shortcut.icon);
            cover.setAlpha(0.35f);
        }
        // Portrait: the artwork keeps its full width and sits at the
        // top instead of being cropped at the sides
        // 세로 화면: 아트워크 양옆을 자르지 않고 가로 전체를 보여 주며 위쪽에 붙임
        // The portrait fit above (full-width artwork at the top) is only for
        // real artwork - a background cover the user set, or Steam's wide art
        // once it has loaded. Games without artwork (tile image / icon behind
        // the sheet) keep the centre-cropped picture. The veil (~20%) is the
        // same for both
        // 위의 세로 맞춤(가로 전체로 위에 붙인 아트워크)은 실제 아트워크에만 씀 -
        // 사용자가 정한 배경 커버, 또는 불러온 스팀 넓은 아트워크. 아트워크가 없는
        // 게임(창 뒤에 타일 이미지 / 아이콘)은 가운데를 채워 자른 그림을 유지함.
        // 막(약 20%)은 둘 다 같음
        final View scrim = view.findViewById(R.id.VDetailScrim);
        final View scrimGradient = view.findViewById(R.id.VDetailScrimGradient);
        final boolean darkTheme = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("dark_mode", false);
        // Last applied style (real artwork or not), re-applied when the sheet's size
        // changes. Portrait/landscape is taken from the sheet's own size, not from
        // the configuration read once at opening: a sheet opened in portrait and
        // then rotated kept the portrait fit and the artwork stopped halfway down
        // 마지막에 적용한 모양(실제 아트워크인지)을 기억해 창 크기가 바뀌면 다시
        // 적용함. 세로/가로는 열 때 한 번 읽은 설정값이 아니라 창 자신의 크기로
        // 판단함: 세로로 열고 돌리면 세로용 맞춤이 그대로 남아 아트워크가 중간에서
        // 끊겼음
        final boolean[] artworkState = {false};
        final java.util.function.Consumer<Boolean> applyArtStyle = artwork -> {
            artworkState[0] = artwork;
            boolean portrait = view.getWidth() > 0 && view.getHeight() > 0
                    ? view.getHeight() > view.getWidth()
                    : getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            // Real artwork (Steam art or a picked cover) is shown whole, at the top,
            // in both orientations; the area below it is the sheet's background
            // colour. A plain tile image still fills the sheet. ("portrait" is kept
            // for the scrim choice below.)
            // 실제 아트워크(스팀 아트워크나 지정한 커버)는 가로·세로 모두 잘리지 않게
            // 위쪽에 맞춰 보여 주고, 그 아래는 창의 배경색으로 채움. 일반 타일 그림은
            // 지금처럼 창을 가득 채움("portrait"는 아래 막 선택에 그대로 씀)
            cover.setScaleType(artwork ? ImageView.ScaleType.FIT_START : ImageView.ScaleType.CENTER_CROP);
            android.util.TypedValue bg = new android.util.TypedValue();
            context.getTheme().resolveAttribute(R.attr.neonBg, bg, true);
            int bgColor = bg.resourceId != 0 ? androidx.core.content.ContextCompat.getColor(context, bg.resourceId) : bg.data;
            cover.setBackgroundColor(artwork ? bgColor : Color.TRANSPARENT);
            // Same veil for every game (the Steam artwork level): legibility comes
            // from the text-block veil and the text halo set below, not a darker cover
            // 모든 게임에 같은 막(스팀 아트워크 기준)을 씀: 글자 가독성은 커버를 더
            // 어둡게 하는 대신 아래에서 넣는 글자 묶음 막과 글자 테두리로 확보함
            scrim.setBackgroundColor(darkTheme ? 0x33000000 : 0x33FFFFFF);
            scrimGradient.setBackgroundResource(darkTheme ? R.drawable.neon_scrim_bottom_dark : R.drawable.neon_scrim_bottom_light);
        };
        applyArtStyle.accept(shortcut.hasCustomCoverArt());

        // Text legibility over any picture: a soft theme-coloured veil behind the
        // text block, plus a halo around each text in it
        // 어떤 그림 위에서도 글자가 읽히게 함: 글자 묶음 뒤에 테마 색의 옅은 막을
        // 깔고, 그 안의 글자마다 테두리(후광)를 넣음
        View textBlock = view.findViewById(R.id.LLDetailTextBlock);
        textBlock.setBackgroundResource(darkTheme ? R.drawable.neon_text_scrim_dark : R.drawable.neon_text_scrim_light);
        // Half strength so the veil does not read as a band over the artwork
        // 막이 아트워크 위에 띠처럼 보이지 않게 절반 세기로 둠
        if (textBlock.getBackground() != null) textBlock.getBackground().mutate().setAlpha(128);
        applyTextHalo(textBlock, darkTheme, view.findViewById(R.id.TVDetailName));
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyArtStyle.accept(artworkState[0]);
        });

        // Fold / unfold of the part below Start. The starting state
        // comes from Settings (unfolded unless changed)
        // 시작 아래 부분 접기 / 펼치기. 시작 상태는 설정에서 옴(바꾸지 않으면 펼침)
        final View more = view.findViewById(R.id.LLDetailMore);
        final TextView moreToggle = view.findViewById(R.id.TVDetailMoreToggle);
        final boolean[] folded = {PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_DETAIL_FOLDED, true)};
        Runnable applyFold = () -> {
            more.setVisibility(folded[0] ? View.GONE : View.VISIBLE);
            moreToggle.setText(folded[0] ? R.string.detail_unfold : R.string.detail_fold);
        };
        applyFold.run();
        moreToggle.setOnClickListener(v -> {
            folded[0] = !folded[0];
            applyFold.run();
        });

        // Steam games without a custom cover: Steam's wide artwork
        // 사용자 지정 커버가 없는 스팀 게임: 스팀의 넓은 아트워크
        final int wideId = parseIntSafe(shortcut.getExtra("steamAppId"));
        if (wideId > 0 && !shortcut.hasCustomCoverArt()) {
            Bitmap wide = com.winlator.cmod.steam.SteamArtwork.cachedWide(wideId);
            if (wide != null) {
                cover.setImageBitmap(wide);
                cover.setAlpha(1f);
                applyArtStyle.accept(true);
            } else {
                final Context app = context.getApplicationContext();
                ART_POOL.execute(() -> {
                    Bitmap b = com.winlator.cmod.steam.SteamArtwork.loadWide(app, wideId);
                    Activity a = getActivity();
                    if (b == null || a == null) return;
                    a.runOnUiThread(() -> {
                        cover.setImageBitmap(b);
                        cover.setAlpha(1f);
                        applyArtStyle.accept(true);
                    });
                });
            }
        }

        ((TextView) view.findViewById(R.id.TVDetailName)).setText(shortcut.name);
        ((TextView) view.findViewById(R.id.TVDetailContainer)).setText(shortcut.container.getName());
        ((TextView) view.findViewById(R.id.TVDetailVersions)).setText(buildVersionSummary(shortcut));

        ((TextView) view.findViewById(R.id.TVPlaytimeValue))
                .setText(formatPlaytime(parseLongSafe(shortcut.getExtra("playtimeSec", "0"))));

        TextView tvLast = view.findViewById(R.id.TVLastPlayedValue);
        long last = parseLongSafe(shortcut.getExtra("lastPlayed", "0"));
        tvLast.setText(last > 0L
                ? DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                : getString(R.string.neon_never_played));

        // Layer button in place of the old Size stat (that figure was the
        // whole container, not the game, and took a long folder walk)
        // 예전 용량 칸 자리의 레이어 버튼임(그 값은 게임이 아니라 컨테이너 전체
        // 용량이었고, 폴더를 오래 훑어야 나왔음)
        // EZ mode: the layer picker (tier x architecture, see showLayerPicker()).
        // Shows "<tier> · <arch>", or "CUSTOM · <tier> · <arch>" when the game's
        // components no longer match its tier
        // EZ 모드: 레이어 선택(단계 x 아키텍처, showLayerPicker() 참고). "<단계> ·
        // <아키텍처>"를 보여 주고, 게임의 구성요소가 단계와 달라졌으면
        // "CUSTOM · <단계> · <아키텍처>"를 보여 줌
        View layerSwitch = view.findViewById(R.id.LLLayerSwitch);
        ((TextView) view.findViewById(R.id.TVLayerValue)).setText(layerLabel(context, shortcut));
        layerSwitch.setVisibility(isEzMode(context) ? View.VISIBLE : View.GONE);

        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        if (dialog.getWindow() != null) {
            // The layout draws its own rounded card, so the platform dialog
            // background is cleared - otherwise a grey square shows behind it.
            // 레이아웃이 자기 둥근 카드를 그리므로 플랫폼 다이얼로그 배경을 비움.
            // 안 그러면 뒤쪽에 회색 사각형이 비침
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // MATCH_PARENT, not a computed pixel width: the layout's own
            // 24dp side padding (neon_shortcut_detail_dialog.xml) now supplies
            // the margin declaratively, so Android re-measures it correctly on
            // every rotation without any Java-side recalculation.
            // 픽셀로 계산한 너비 대신 MATCH_PARENT를 씀: 이제 레이아웃 자체의
            // 24dp 좌우 여백(neon_shortcut_detail_dialog.xml)이 마진을 선언적으로
            // 제공하므로, 회전할 때마다 Java 쪽 재계산 없이 안드로이드가 알아서
            // 다시 측정함
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        final Button btStart = view.findViewById(R.id.BTStart);
        final boolean[] updateMode = {false};
        btStart.setOnClickListener(v -> {
            if (updateMode[0]) {
                // Update: same download service and panel as the Steam page
                // 업데이트: 스팀 페이지와 같은 다운로드 서비스와 창을 씀
                int id = parseIntSafe(shortcut.getExtra("steamAppId"));
                SteamDownloadState current = SteamDownloadState.read(context);
                if (current.isActive() && current.appId != id) {
                    Toast.makeText(context, getString(R.string.steam_dl_busy, current.name), Toast.LENGTH_SHORT).show();
                    return;
                }
                SteamDownloadState.start(context, id, shortcut.name, true);
                return;
            }
            dialog.dismiss();
            launchShortcut(shortcut);
        });

        // Steam games: show a running download/update of this game in
        // place of Start, and turn Start into "Update" when Steam has a newer build
        // 스팀 게임: 이 게임의 다운로드/업데이트가 진행 중이면 시작 버튼 자리에
        // 보여 주고, 스팀에 새 빌드가 있으면 시작 버튼을 "업데이트"로 바꿈
        final View panelView = view.findViewById(R.id.LLShortcutDlPanel);
        final int steamAppId = parseIntSafe(shortcut.getExtra("steamAppId"));
        if (steamAppId > 0) {
            final SteamDownloadPanel panel = new SteamDownloadPanel(panelView, steamAppId);
            // Achievements button left of Start (Steam games only); hidden together with
            // Start while the download panel takes their place
            // 시작 왼쪽의 도전과제 버튼(스팀 게임 전용). 다운로드 창이 그 자리를 쓰는
            // 동안에는 시작과 함께 숨김
            final Button btAchievements = view.findViewById(R.id.BTAchievements);
            btAchievements.setVisibility(View.VISIBLE);
            btAchievements.setOnClickListener(v -> {
                if (getActivity() != null)
                    com.winlator.cmod.steam.SteamAchievementsDialog.show(getActivity(), steamAppId, shortcut.name);
            });
            panel.setOnRefreshed(() -> {
                int vis = panel.isShowing() ? View.GONE : View.VISIBLE;
                btStart.setVisibility(vis);
                btAchievements.setVisibility(vis);
            });
            panel.setListener(result -> {
                SteamUpdateChecker.forget(steamAppId);
                updateMode[0] = false;
                btStart.setText(R.string.neon_start);
                if (result != null && !result.isEmpty()) Toast.makeText(context, result, Toast.LENGTH_LONG).show();
            });
            panel.start();
            dialog.setOnDismissListener(d -> panel.stop());
            // Result line under Start: checking / up to date / update / unknown
            // 시작 버튼 아래 결과 줄: 확인 중 / 최신 / 업데이트 있음 / 확인 못 함
            final TextView tvUpdate = view.findViewById(R.id.TVSteamUpdateStatus);
            tvUpdate.setVisibility(View.VISIBLE);
            tvUpdate.setText(R.string.steam_update_checking);
            SteamUpdateChecker.check(context, steamAppId, (available, known) -> {
                if (!dialog.isShowing()) return;
                tvUpdate.setText(!known ? R.string.steam_update_unknown
                        : available ? R.string.steam_update_available : R.string.steam_update_latest);
                if (!available) return;
                updateMode[0] = true;
                btStart.setText(R.string.steam_update_game);
            });
        } else {
            panelView.setVisibility(View.GONE);
        }

        view.findViewById(R.id.BTEditShortcut).setOnClickListener(v -> {
            dialog.dismiss();
            new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut).show();
        });

        view.findViewById(R.id.BTRemoveShortcut).setOnClickListener(v -> {
            dialog.dismiss();
            confirmRemoveShortcut(shortcut);
        });

        layerSwitch.setOnClickListener(v -> showLayerPicker(shortcut, dialog, v));

        // Image buttons: tile picture on the grid, background of this sheet
        // 이미지 버튼: 격자의 타일 그림, 이 창의 배경
        view.findViewById(R.id.BTTileImage).setOnClickListener(v ->
                showShortcutImageMenu(shortcut, IMAGE_SLOT_TILE, dialog));
        view.findViewById(R.id.BTBackgroundCover).setOnClickListener(v ->
                showShortcutImageMenu(shortcut, IMAGE_SLOT_COVER, dialog));

        // Desktop GPU toggle (under Edit): shortcut extra "gpuSpoof", read by
        // XServerDisplayActivity.applyDesktopGpuSpoof() at launch
        // 데스크탑 GPU 토글(Edit 아래): 숏컷 extra "gpuSpoof"에 저장하고,
        // 실행 시 XServerDisplayActivity.applyDesktopGpuSpoof()가 읽음
        androidx.appcompat.widget.SwitchCompat swGpu = view.findViewById(R.id.SWDesktopGpu);
        swGpu.setChecked("1".equals(shortcut.getExtra("gpuSpoof", "0")));
        swGpu.setOnCheckedChangeListener((b, checked) -> {
            shortcut.putExtra("gpuSpoof", checked ? "1" : null);
            shortcut.saveData();
        });

        // GStreamer toggle (under Remove): same "gstreamerWorkaround" extra the
        // settings dialog uses, falling back to the container value when unset
        // GStreamer 토글(Remove 아래): 설정창과 같은 "gstreamerWorkaround" extra를
        // 씀. 값이 없으면 컨테이너 설정값을 따름
        androidx.appcompat.widget.SwitchCompat swGst = view.findViewById(R.id.SWGStreamer);
        swGst.setChecked("1".equals(shortcut.getExtra("gstreamerWorkaround",
                shortcut.container.isGstreamerWorkaround() ? "1" : "0")));
        swGst.setOnCheckedChangeListener((b, checked) -> {
            shortcut.putExtra("gstreamerWorkaround", checked ? "1" : "0");
            shortcut.saveData();
        });

        dialog.show();
    }

    // ==================== Layer switch (EZ mode) ====================
    // ==================== 레이어 변경 (EZ 모드) ====================

    // Beginner mode setting (Settings > Theme). On by default: the layer
    // button is meant for people who would not know where container
    // settings live
    // 초보자 모드 설정임(설정 > 테마). 기본값은 켜짐: 레이어 버튼은 컨테이너
    // 설정이 어디 있는지 모르는 사람을 위한 것임
    public static final String PREF_EZ_MODE = "ez_mode";
    // Shortcut file of a game that ended within 30 s (XServerDisplayActivity.recordQuickExit())
    // 30초 안에 끝난 게임의 숏컷 파일(XServerDisplayActivity.recordQuickExit())
    public static final String PREF_LAYER_HINT_PENDING = "layer_hint_pending";
    // Detail sheet starts folded (Settings) / 상세창을 접은 채 시작(설정)
    public static final String PREF_DETAIL_FOLDED = "detail_sheet_folded";

    private static int parseIntSafe(String s) {
        try {
            return s != null && !s.isEmpty() ? Integer.parseInt(s.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isEzMode(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_EZ_MODE, true);
    }

    // Asks first, because the game moves to a different container (a separate
    // Windows environment with its own settings and save location in drive C)
    // 먼저 물어봄. 게임이 다른 컨테이너(설정과 C 드라이브 저장 위치가 따로인 별도
    // Windows 환경)로 옮겨지기 때문임
    // After a game ended within 30 s: offer a tier that is not installed yet,
    // Stable first, then Legacy - each tier is offered only once
    // 게임이 30초 안에 끝난 뒤: 아직 설치하지 않은 단계를 권함. Stable(안정) 다음
    // Legacy(레거시) 순이고, 단계마다 한 번만 권함
    private void maybeRecommendLayer() {
        final Context context = getContext();
        if (context == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String path = prefs.getString(PREF_LAYER_HINT_PENDING, null);
        if (path == null) return;
        prefs.edit().remove(PREF_LAYER_HINT_PENDING).apply();
        Shortcut found = null;
        for (Shortcut sc : manager.loadShortcuts()) {
            if (sc.file != null && sc.file.getPath().equals(path)) { found = sc; break; }
        }
        if (found == null) return;
        final Shortcut shortcut = found;
        final boolean arm64ec = ContainerManager.isArm64EC(shortcut.container);
        for (com.winlator.cmod.container.LayerTiers.Tier tier : new com.winlator.cmod.container.LayerTiers.Tier[]{
                com.winlator.cmod.container.LayerTiers.TIER_STABLE, com.winlator.cmod.container.LayerTiers.TIER_LEGACY}) {
            String shownKey = "layer_hint_shown_" + tier.id;
            if (prefs.getBoolean(shownKey, false)) continue;
            if (com.winlator.cmod.container.LayerTiers.findContainer(manager, tier.id, arm64ec) != null) continue;
            prefs.edit().putBoolean(shownKey, true).apply();
            String label = getString(tier.labelRes);
            new AlertDialog.Builder(context)
                    .setTitle(R.string.layer_hint_title)
                    .setMessage(getString(R.string.layer_hint_message, shortcut.name, label,
                            com.winlator.cmod.container.LayerTiers.describe(tier.arch(arm64ec))))
                    .setPositiveButton(R.string.neon_layer_change_ok, (d, w) -> moveToLayer(shortcut, tier, arm64ec))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
    }

    // Long-press menu "Export saves": packs only this game's save folders
    // (SharedSaves.exportGame()) into Download/.EZlator/Saves
    // 길게 누르기 메뉴 "세이브 추출": 이 게임의 세이브 폴더만
    // (SharedSaves.exportGame()) Download/.EZlator/Saves로 묶음
    private void exportGameSaves(final Shortcut shortcut) {
        final Activity activity = getActivity();
        if (activity == null) return;
        final Context app = activity.getApplicationContext();
        preloaderDialog.show(R.string.save_working);
        Executors.newSingleThreadExecutor().execute(() -> {
            java.io.File out = null;
            try {
                out = com.winlator.cmod.container.SharedSaves.exportGame(app, shortcut);
            } catch (Exception e) {
                android.util.Log.e("ShortcutsFragment", "save export failed", e);
            }
            final java.io.File result = out;
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                Toast.makeText(app, result != null ? app.getString(R.string.save_export_done, result.getName())
                        : app.getString(R.string.save_export_none), Toast.LENGTH_LONG).show();
            });
        });
    }

    // Text of the layer picker button
    // 레이어 선택 버튼의 글자
    private String layerLabel(Context context, Shortcut shortcut) {
        String arch = ContainerManager.isArm64EC(shortcut.container) ? "ARM64EC" : "X86_64";
        String tierId = com.winlator.cmod.container.LayerTiers.tierOf(shortcut.container);
        com.winlator.cmod.container.LayerTiers.Tier tier = tierId != null ? com.winlator.cmod.container.LayerTiers.get(tierId) : null;
        if (tier == null) return arch;
        String text = context.getString(tier.labelRes) + " \u00b7 " + arch;
        return com.winlator.cmod.container.LayerTiers.isCustom(context, shortcut.container, shortcut) ? "CUSTOM \u00b7 " + text : text;
    }

    // One line of the layer picker: a tier header, or a tier + architecture
    // 레이어 선택 목록의 한 줄: 단계 머리줄, 또는 단계 + 아키텍처
    private static final class LayerChoice {
        final String text;
        final com.winlator.cmod.container.LayerTiers.Tier tier;
        final boolean arm64ec;
        final boolean header;
        final Container container;
        final boolean current;

        LayerChoice(String text, com.winlator.cmod.container.LayerTiers.Tier tier, boolean arm64ec,
                    boolean header, Container container, boolean current) {
            this.text = text;
            this.tier = tier;
            this.arm64ec = arm64ec;
            this.header = header;
            this.container = container;
            this.current = current;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    // Layer picker (EZ mode): per tier a header, then ARM64EC and, for non-Steam
    // games, X86_64. Layers without a container are dimmed; picking one asks to
    // create it. Picking the game's own layer while it is CUSTOM resets the
    // game's component settings to the tier's.
    // 레이어 선택(EZ 모드): 단계마다 머리줄, 그다음 ARM64EC와 (스팀이 아닌 게임만)
    // X86_64. 컨테이너가 없는 레이어는 흐리게 표시하고, 고르면 만들지 물음. CUSTOM
    // 상태에서 게임 자신의 레이어를 고르면 게임의 구성요소 설정을 단계 값으로 되돌림
    private void showLayerPicker(final Shortcut shortcut, final Dialog detailDialog, final View anchor) {
        final Context context = getContext();
        if (context == null) return;
        boolean steamGame = !shortcut.getExtra("steamAppId").isEmpty();
        final boolean custom = com.winlator.cmod.container.LayerTiers.isCustom(context, shortcut.container, shortcut);
        ContainerManager cm = new ContainerManager(context);
        final ArrayList<LayerChoice> rows = new ArrayList<>();
        for (com.winlator.cmod.container.LayerTiers.Tier tier : com.winlator.cmod.container.LayerTiers.ALL) {
            String name = getString(tier.labelRes);
            rows.add(new LayerChoice("------ " + name + " ------", tier, false, true, null, false));
            for (boolean arm : steamGame ? new boolean[]{true} : new boolean[]{true, false}) {
                Container c = com.winlator.cmod.container.LayerTiers.findContainer(cm, tier.id, arm);
                boolean current = c != null && c.id == shortcut.container.id;
                String arch = arm ? "ARM64EC" : "X86_64";
                String text = current ? arch + (custom ? " " + getString(R.string.tier_revert) : "  \u2713") : arch;
                rows.add(new LayerChoice(text, tier, arm, false, c, current));
            }
        }

        android.widget.ArrayAdapter<LayerChoice> adapter = new android.widget.ArrayAdapter<LayerChoice>(
                context, android.R.layout.simple_list_item_1, rows) {
            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return !getItem(position).header;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                LayerChoice row = getItem(position);
                if (v instanceof TextView) {
                    ((TextView) v).setTypeface(null, row.header ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    ((TextView) v).setGravity(android.view.Gravity.CENTER);
                }
                v.setAlpha(row.header ? 0.85f : row.container == null ? 0.4f : 1f);
                return v;
            }
        };

        // Drop-down list right under the layer button, in the app's card style
        // 레이어 버튼 바로 아래에 펼쳐지는 드롭다운 목록. 앱 카드 모양을 씀
        float dp = getResources().getDisplayMetrics().density;
        final android.widget.ListPopupWindow picker = new android.widget.ListPopupWindow(context);
        picker.setAnchorView(anchor);
        picker.setAdapter(adapter);
        picker.setModal(true);
        picker.setWidth(Math.max(anchor.getWidth(), (int) (220 * dp)));
        picker.setBackgroundDrawable(androidx.core.content.ContextCompat.getDrawable(context, R.drawable.neon_card_bg));
        picker.setOnItemClickListener((parent, v, which, id) -> {
            LayerChoice row = rows.get(which);
            if (row.header) return;
            picker.dismiss();
            if (row.current) {
                if (custom) {
                    // Back to the tier: drop the game's own component builds
                    // 단계로 되돌림: 게임의 개별 구성요소 빌드를 지움
                    for (String key : com.winlator.cmod.container.LayerTiers.COMPONENT_EXTRAS) shortcut.putExtra(key, null);
                    shortcut.saveData();
                    detailDialog.dismiss();
                    loadShortcutsList();
                }
                return;
            }
            String target = getString(row.tier.labelRes) + " \u00b7 " + (row.arm64ec ? "ARM64EC" : "X86_64");
            new AlertDialog.Builder(context)
                    .setTitle(R.string.neon_layer_change)
                    .setMessage(row.container != null
                            ? getString(R.string.neon_layer_change_message, target)
                            : getString(R.string.tier_create_message, getString(row.tier.labelRes),
                            com.winlator.cmod.container.LayerTiers.describe(row.tier.arch(row.arm64ec))))
                    .setPositiveButton(R.string.neon_layer_change_ok, (d2, w2) -> {
                        detailDialog.dismiss();
                        moveToLayer(shortcut, row.tier, row.arm64ec);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        picker.show();
    }

    // Moves the game to the tier + architecture container, creating it first when
    // missing (downloads only what is missing). Same move rules as the settings
    // dialog's container picker (ShortcutMover)
    // 게임을 해당 단계 + 아키텍처 컨테이너로 옮김. 없으면 먼저 만듦(없는 것만 받음).
    // 옮기는 규칙은 설정창의 컨테이너 선택과 같음(ShortcutMover)
    private void moveToLayer(final Shortcut shortcut, final com.winlator.cmod.container.LayerTiers.Tier tier, final boolean arm64ec) {
        final Activity activity = getActivity();
        if (activity == null) return;
        final Context appContext = activity.getApplicationContext();
        preloaderDialog.show(R.string.neon_layer_switching);
        Executors.newSingleThreadExecutor().execute(() -> {
            Container target = com.winlator.cmod.container.LayerTiers.findContainer(new ContainerManager(appContext), tier.id, arm64ec);
            if (target == null) {
                com.winlator.cmod.container.LayerTiers.createTier(appContext, tier, new boolean[]{arm64ec},
                        message -> activity.runOnUiThread(() -> preloaderDialog.setMessage(message)));
                target = com.winlator.cmod.container.LayerTiers.findContainer(new ContainerManager(appContext), tier.id, arm64ec);
            }
            com.winlator.cmod.container.ShortcutMover.Result result = target != null
                    ? com.winlator.cmod.container.ShortcutMover.move(shortcut, target)
                    : null;
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (!isAdded()) return;
                int msg;
                if (result == null) {
                    msg = R.string.tier_failed;
                } else if (result.code == com.winlator.cmod.container.ShortcutMover.C_DRIVE_BLOCKED) {
                    msg = R.string.shortcut_move_c_drive_blocked;
                } else if (result.code == com.winlator.cmod.container.ShortcutMover.NAME_EXISTS) {
                    msg = R.string.shortcut_move_name_exists;
                } else if (result.code == com.winlator.cmod.container.ShortcutMover.FAILED) {
                    msg = R.string.shortcut_move_failed;
                } else {
                    msg = 0;
                }
                if (msg != 0) {
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show();
                    return;
                }
                manager = new ContainerManager(getContext());
                loadShortcutsList();
                Toast.makeText(appContext, getString(R.string.neon_layer_switched,
                        getString(tier.labelRes) + " \u00b7 " + (arm64ec ? "ARM64EC" : "X86_64")), Toast.LENGTH_SHORT).show();
            });
        });
    }

    // Same removal behaviour as the popup menu entry, kept in one place so the
    // two paths cannot drift apart.
    // 팝업 메뉴의 삭제와 동일한 동작임. 두 경로가 갈라지지 않도록 한곳에 둠
    private void confirmRemoveShortcut(final Shortcut shortcut) {
        final Context context = getContext();
        if (context == null) return;

        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_shortcut, () -> {
            // Image copies go with the shortcut
            // 이미지 사본도 숏컷과 함께 지움
            shortcut.deleteCustomImages();
            boolean desktopDeleted = safeDelete(shortcut.file);
            boolean iconDeleted = safeDelete(shortcut.iconFile);
            boolean lnkDeleted = deletePairedLnkForShortcut(shortcut);

            if (desktopDeleted) {
                disableShortcutOnScreen(requireContext(), shortcut);
                loadShortcutsList();
            }

            Toast.makeText(context,
                    desktopDeleted ? R.string.shortcut_removed : R.string.shortcut_remove_failed,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private boolean deletePairedLnkForShortcut(Shortcut shortcut) {
        if (shortcut == null || shortcut.file == null) return false;
        File dir = shortcut.file.getParentFile();
        if (dir == null) return false;

        String base = FileUtils.getBasename(shortcut.file.getName()); // strips extension
        File lnk = new File(dir, base + ".lnk");
        boolean deleted = safeDelete(lnk);
        if (deleted) {
            Log.d("ShortcutsFragment", "Paired .lnk removed: " + lnk.getAbsolutePath());
        } else if (lnk.exists()) {
            Log.w("ShortcutsFragment", "Paired .lnk exists but could not be removed: " + lnk.getAbsolutePath());
        } else {
            Log.d("ShortcutsFragment", "No paired .lnk found for " + base);
        }
        return deleted;
    }
}
