package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ContentInfoDialog;
import com.winlator.cmod.contentdialog.ContentUntrustedDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.contents.DriverCatalog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GpuInfo;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class ContentsFragment extends Fragment {
    private RecyclerView recyclerView;
    // TextView, not View: loadContentList() now calls emptyText.setText()
    // to swap in the Mali-specific message, which View doesn't expose.
    // View가 아니라 TextView임: loadContentList()가 이제 Mali 전용 문구로
    // 바꿔 끼우려고 emptyText.setText()를 호출하는데, View에는 이게 없음
    private TextView emptyText;
    private ContentsManager manager;
    private ContentProfile.ContentType currentContentType = ContentProfile.ContentType.CONTENT_TYPE_WINE;
    private TabLayout tlContentType;
    // GraphicTools-only sub-tabs: 0 = StevenMXZ (default), 1 = The412Banner
    // GraphicTools 전용 하위 탭: 0 = StevenMXZ(기본), 1 = The412Banner
    private TabLayout tlDriverSource;
    private String currentDriverProvider = ContentProfile.PROVIDER_STEVENMXZ;
    // DXVK/VKD3D-only sub-tabs: 0 = ARM64EC (default), 1 = X86_64
    // DXVK/VKD3D 전용 하위 탭: 0 = ARM64EC(기본), 1 = X86_64
    private TabLayout tlArch;
    private boolean currentArchArm64EC = true;
    // Provider of the driver currently being downloaded, recorded after install
    // 지금 다운로드 중인 드라이버의 배포처. 설치가 끝나면 기록함
    private String pendingDriverProvider = null;

    private boolean isDarkMode;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        manager = new ContentsManager(getContext());
        // Load the bundled catalog immediately so the screen isn't empty
        // while the live catalog downloads in onResume().
        // onResume()에서 실시간 카탈로그를 받아오는 동안 화면이 비어있지 않도록
        // 일단 번들된 카탈로그로 먼저 채워둠
        manager.setRemoteProfiles(FileUtils.readString(getActivity(), ContentsManager.REMOTE_PROFILES));
        manager.syncContents();

        // Initialize isDarkMode based on shared preferences or theme
        isDarkMode = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("dark_mode", false);
    }

    @Override
    public void onDestroy() {                              // ← 그대로, 손 안 댐
        FileUtils.clear(getContext().getCacheDir());
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        final android.content.Context appContext = requireContext().getApplicationContext();

        new Thread(() -> {
            // Live The412Banner catalog is MERGED into the bundled one (it used to
            // replace it, which removed the bundled StevenMXZ drivers). Its
            // GraphicTools entries default to the The412Banner sub-tab.
            // 실시간 The412Banner 카탈로그를 번들 카탈로그에 병합함(예전엔 교체해서
            // 번들의 StevenMXZ 드라이버가 사라졌음). 여기서 온 GraphicTools 항목은
            // 기본적으로 The412Banner 하위 탭으로 감
            File tempCatalog = new File(appContext.getCacheDir(), "contents_live.json");
            if (Downloader.downloadFile(
                    "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/contents.json",
                    tempCatalog)) {
                manager.mergeRemoteProfilesJson(FileUtils.readString(tempCatalog),
                        ContentProfile.PROVIDER_THE412BANNER);
            }

            // StevenMXZ releases straight from GitHub (Qualcomm builds excluded),
            // so new Turnip versions appear without an app update
            // StevenMXZ 릴리즈를 GitHub에서 바로 가져옴(퀄컴 빌드 제외).
            // 앱 업데이트 없이 새 Turnip 버전이 목록에 뜨게 하기 위함
            manager.mergeRemoteProfiles(DriverCatalog.fetchStevenMxz(appContext));
            // The412Banner Turnip builds come from its Banners-Turnip releases
            // The412Banner Turnip 빌드는 Banners-Turnip 릴리즈에서 가져옴
            manager.mergeRemoteProfiles(DriverCatalog.fetchThe412Banner(appContext));

            // Fragment may be gone by now (user left the screen)
            // 그 사이 사용자가 화면을 나갔을 수 있음
            android.app.Activity activity = getActivity();
            if (activity != null && isAdded()) activity.runOnUiThread(() -> {
                if (isAdded()) loadContentList();
            });
        }).start();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Same name as the drawer entry ("Components & Drivers")
        // 드로어 항목과 같은 이름("구성요소 및 드라이버")을 씀
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.tools);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup) inflater.inflate(R.layout.contents_fragment, container, false);

        tlContentType = layout.findViewById(R.id.TLContentType);
        tlDriverSource = layout.findViewById(R.id.TLDriverSource);
        setupDriverSourceTabs(tlDriverSource);
        tlArch = layout.findViewById(R.id.TLArch);
        setupArchTabs(tlArch);
        setupContentTypeTabs(tlContentType);

        emptyText = layout.findViewById(R.id.TVEmptyText);

        View btInstallContent = layout.findViewById(R.id.BTInstallContent);
        btInstallContent.setOnClickListener(v -> {
            ContentDialog.confirm(getContext(), getString(R.string.do_you_want_to_install_content), () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
            });
        });

        recyclerView = layout.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        loadContentList();

        return layout;
    }

    // One tab per ContentType, in enum order, labelled by its toString().
    // A single listener (not two, like the old spinner had) drives the list -
    // the old code had a second, dead one left over inside
    // updateContentTypeSpinner() from before this was refactored once already.
    // ContentType마다 탭 하나씩, enum 순서대로, toString()으로 라벨을 붙임.
    // 리스너를 하나만 둠(스피너 시절엔 두 개였음) - 예전 코드는 한 번
    // 리팩터된 뒤에도 updateContentTypeSpinner() 안에 죽은 리스너 하나가
    // 남아 있었음
    private void setupContentTypeTabs(TabLayout tabLayout) {
        tabLayout.removeAllTabs();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            tabLayout.addTab(tabLayout.newTab().setText(type.toString()));
        }

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                ContentProfile.ContentType[] values = ContentProfile.ContentType.values();
                int pos = tab.getPosition();
                currentContentType = (pos >= 0 && pos < values.length)
                        ? values[pos] : ContentProfile.ContentType.CONTENT_TYPE_WINE;
                loadContentList();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    // Sub-tabs for GraphicTools. StevenMXZ is added first so it is the default
    // selection; the listener only re-filters the current list.
    // GraphicTools용 하위 탭. StevenMXZ를 먼저 추가해서 기본 선택이 되게 함.
    // 리스너는 현재 목록을 다시 거르기만 함
    private void setupDriverSourceTabs(TabLayout tabLayout) {
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText(R.string.driver_source_stevenmxz));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.driver_source_the412banner));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentDriverProvider = tab.getPosition() == 1
                        ? ContentProfile.PROVIDER_THE412BANNER : ContentProfile.PROVIDER_STEVENMXZ;
                loadContentList();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    // Sub-tabs for DXVK and VKD3D. ARM64EC is added first so it is the default
    // selection; the listener only re-filters the current list.
    // DXVK와 VKD3D용 하위 탭. ARM64EC를 먼저 추가해서 기본 선택이 되게 함.
    // 리스너는 현재 목록을 다시 거르기만 함
    private void setupArchTabs(TabLayout tabLayout) {
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText(R.string.content_arch_arm64ec));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.content_arch_x86_64));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentArchArm64EC = tab.getPosition() == 0;
                loadContentList();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    // Same rule as DXWrapperConfigDialog and ContainerManager.pickVersionKey():
    // a build is ARM64EC when its name contains "arm64ec", otherwise X86_64.
    // Using one rule everywhere keeps this screen and the container settings
    // showing the same builds under the same architecture.
    // DXWrapperConfigDialog, ContainerManager.pickVersionKey()와 같은 규칙임:
    // 이름에 "arm64ec"가 있으면 ARM64EC, 없으면 X86_64 빌드임. 규칙을 하나로
    // 통일해야 이 화면과 컨테이너 설정이 같은 빌드를 같은 아키텍처로 보여줌
    private static boolean isArm64ECBuild(ContentProfile p) {
        return p.verName != null && p.verName.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec");
    }

    // True when a GraphicTools entry belongs on the selected sub-tab.
    // Remote entries with no known provider go to the default (StevenMXZ) tab;
    // installed drivers with no recorded provider (manual installs) show on both.
    // GraphicTools 항목이 선택된 하위 탭에 속하면 true임.
    // 배포처를 모르는 원격 항목은 기본(StevenMXZ) 탭으로 보냄. 배포처 기록이 없는
    // 설치 드라이버(파일로 직접 설치)는 양쪽에 다 보임
    private boolean belongsToCurrentDriverSource(ContentProfile p) {
        if (p.remoteUrl == null) {
            String recorded = manager.getDriverProvider(p.verName);
            return recorded == null || recorded.equals(currentDriverProvider);
        }
        String provider = p.provider != null ? p.provider : ContentProfile.PROVIDER_STEVENMXZ;
        return provider.equals(currentDriverProvider);
    }

    // Selects the tab for a given type, e.g. after a successful install.
    // Replaces AppUtils.setSpinnerSelectionFromValue(), which no longer
    // applies now that there is no Spinner.
    // 주어진 타입의 탭을 선택함. 예: 설치 성공 직후에 씀.
    // 이제 Spinner가 없으므로 더는 맞지 않는
    // AppUtils.setSpinnerSelectionFromValue()를 대체함
    private void selectContentTypeTab(ContentProfile.ContentType type) {
        ContentProfile.ContentType[] values = ContentProfile.ContentType.values();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == type) {
                TabLayout.Tab tab = tlContentType.getTabAt(i);
                if (tab != null) tab.select();
                break;
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
            preloaderDialog.showOnUiThread(R.string.installing_content);
            try {
                ContentsManager.OnInstallFinishedCallback callback = new ContentsManager.OnInstallFinishedCallback() {
                    private boolean isExtracting = true;

                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                        int msgId = switch (reason) {
                            case ERROR_BADTAR -> R.string.file_cannot_be_recognied;
                            case ERROR_NOPROFILE -> R.string.profile_not_found_in_content;
                            case ERROR_BADPROFILE -> R.string.profile_cannot_be_recognized;
                            case ERROR_EXIST -> R.string.content_already_exist;
                            case ERROR_MISSINGFILES -> R.string.content_is_incomplete;
                            case ERROR_UNTRUSTPROFILE -> R.string.content_cannot_be_trusted;
                            default -> R.string.unable_to_install_content;
                        };
                        requireActivity().runOnUiThread(() -> ContentDialog.alert(getContext(), getString(R.string.install_failed) + ": " + getString(msgId), preloaderDialog::closeOnUiThread));
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        if (isExtracting) {
                            ContentsManager.OnInstallFinishedCallback callback1 = this;
                            requireActivity().runOnUiThread(() -> {
                                ContentInfoDialog dialog = new ContentInfoDialog(getContext(), profile);
                                ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string._continue);
                                dialog.setOnConfirmCallback(() -> {
                                    isExtracting = false;
                                    List<ContentProfile.ContentFile> untrustedFiles = manager.getUnTrustedContentFiles(profile);
                                    if (!untrustedFiles.isEmpty()) {
                                        ContentUntrustedDialog untrustedDialog = new ContentUntrustedDialog(getContext(), untrustedFiles);
                                        untrustedDialog.setOnCancelCallback(preloaderDialog::closeOnUiThread);
                                        untrustedDialog.setOnConfirmCallback(() -> manager.finishInstallContent(profile, callback1));
                                        untrustedDialog.show();
                                    } else manager.finishInstallContent(profile, callback1);
                                });
                                dialog.setOnCancelCallback(preloaderDialog::closeOnUiThread);
                                dialog.show();
                            });

                        } else {
                            preloaderDialog.closeOnUiThread();
                            requireActivity().runOnUiThread(() -> {
                                ContentDialog.alert(getContext(), R.string.content_installed_success, null);
                                manager.syncContents();
                                boolean flashAfter = currentContentType == profile.type;
                                currentContentType = profile.type;
                                // Show the architecture sub-tab the new DXVK/VKD3D belongs
                                // to, otherwise it could be hidden under the other tab
                                // 새로 설치한 DXVK/VKD3D가 속한 아키텍처 하위 탭을 보여줌.
                                // 안 그러면 다른 탭 아래에 가려질 수 있음
                                if (tlArch != null) {
                                    TabLayout.Tab archTab = tlArch.getTabAt(isArm64ECBuild(profile) ? 0 : 1);
                                    if (archTab != null) archTab.select();
                                }
                                selectContentTypeTab(currentContentType);
                                if (flashAfter) loadContentList();
                            });
                        }
                    }
                };
                Executors.newSingleThreadExecutor().execute(() -> {
                    // Graphic Tools packages are AdrenoTools driver ZIPs
                    // (meta.json + libvulkan_*.so), not .wcp tar archives, so
                    // extraContentFile() would fail them with ERROR_BADTAR.
                    // Graphic Tools 패키지는 .wcp tar가 아니라 AdrenoTools
                    // 드라이버 ZIP(meta.json + libvulkan_*.so)이라
                    // extraContentFile()로 보내면 ERROR_BADTAR로 실패함
                    if (currentContentType == ContentProfile.ContentType.CONTENT_TYPE_GRAPHIC_TOOLS) {

                        // The download path at line 360 builds the Uri with
                        // Uri.parse(absolutePath), which has no scheme, and
                        // openInputStream() rejects that. Re-wrap it as file://
                        // 360번째 줄의 다운로드 경로가 Uri.parse(절대경로)로
                        // Uri를 만드는데 스킴이 없어서 openInputStream()이
                        // 거부함. file:// 형태로 다시 감싸 줌
                        Uri src = data.getData();
                        if (src.getScheme() == null) src = Uri.fromFile(new File(src.getPath()));

                        String installed = new AdrenotoolsManager(getContext()).installDriver(src);
                        preloaderDialog.closeOnUiThread();
                        requireActivity().runOnUiThread(() -> {
                            if (installed == null || installed.isEmpty()) {
                                ContentDialog.alert(getContext(), getString(R.string.install_failed), null);
                            } else {
                                ContentDialog.alert(getContext(), R.string.content_installed_success, null);
                                // Remember the source so the driver lists under its sub-tab
                                // 하위 탭에 맞게 나오도록 배포처를 기록함
                                manager.setDriverProvider(installed, pendingDriverProvider);
                                pendingDriverProvider = null;
                                // Newest downloaded driver becomes the automatic default
                                // 가장 최근에 받은 드라이버를 자동 기본값으로 씀
                                com.winlator.cmod.core.DriverAutoSelect.onDriverInstalled(getContext(), installed);
                                // Without this, a newly installed driver never
                                // shows as installed until the user leaves and
                                // reopens the Contents screen (syncContents()
                                // rebuilds profilesMap, which is what the
                                // getInstalledProfiles() bridge above feeds into)
                                // 이게 없으면 새로 설치한 드라이버가 사용자가
                                // Contents 화면을 나갔다가 다시 들어오기 전까지
                                // 설치됨으로 안 뜸(syncContents()가 profilesMap을
                                // 다시 만드는데, 위 getInstalledProfiles() 다리가
                                // 바로 여기로 연결됨)
                                manager.syncContents();
                                loadContentList();
                            }
                        });
                    } else {
                        manager.extraContentFile(data.getData(), callback);
                    }
                });
            } catch (Exception e) {
                preloaderDialog.closeOnUiThread();
                AppUtils.showToast(getContext(), R.string.unable_to_import_profile);
            }
        }
    }

    private void loadContentList() {
        List<ContentProfile> profiles = manager.getProfiles(currentContentType);

        // Graphic Tools only: hide drivers that cannot run on this GPU.
        // Entries with no gpuVendor are always kept, and an unrecognised local
        // GPU shows everything rather than an empty screen.
        // Graphic Tools에서만 적용함. 이 GPU에서 못 도는 드라이버를 숨김.
        // gpuVendor가 없는 항목은 항상 남기고, 로컬 GPU를 못 알아본 경우엔
        // 빈 화면 대신 전부 보여 줌
        boolean isGraphicTools = currentContentType == ContentProfile.ContentType.CONTENT_TYPE_GRAPHIC_TOOLS;
        // Sub-tabs are only meaningful on GraphicTools
        // 하위 탭은 GraphicTools에서만 의미가 있음
        if (tlDriverSource != null) tlDriverSource.setVisibility(isGraphicTools ? View.VISIBLE : View.GONE);
        // Architecture sub-tabs are only meaningful on DXVK and VKD3D
        // 아키텍처 하위 탭은 DXVK와 VKD3D에서만 의미가 있음
        boolean isDxWrapper = currentContentType == ContentProfile.ContentType.CONTENT_TYPE_DXVK
                || currentContentType == ContentProfile.ContentType.CONTENT_TYPE_VKD3D;
        if (tlArch != null) tlArch.setVisibility(isDxWrapper ? View.VISIBLE : View.GONE);
        if (profiles == null) profiles = new ArrayList<>();

        if (isDxWrapper) {
            List<ContentProfile> filtered = new ArrayList<>();
            for (ContentProfile p : profiles) {
                if (isArm64ECBuild(p) == currentArchArm64EC) filtered.add(p);
            }
            profiles = filtered;
        }

        if (isGraphicTools) {
            List<ContentProfile> filtered = new ArrayList<>();
            for (ContentProfile p : profiles) {
                if (GpuInfo.matches(p.gpuVendor, p.gpuMatch, p.manufacturer)
                        && belongsToCurrentDriverSource(p)) filtered.add(p);
            }
            profiles = filtered;
        }

        if (profiles.isEmpty()) {
            // Mali gets its own message instead of the generic empty-list
            // text. Unlike Adreno/Xclipse, Mali's driver is baked into the
            // firmware by ARM with no app-level swap interface, so an empty
            // catalog here isn't "nothing uploaded yet" - it's "this device
            // can never use this feature", which needs to be said plainly
            // rather than left looking like a bug or an oversight.
            // Mali는 일반 빈 목록 문구 대신 전용 안내를 보여줌. Adreno/Xclipse와
            // 달리 Mali 드라이버는 ARM이 펌웨어에 박아 넣어서 앱 단에서 바꿀
            // 방법이 없음. 그래서 카탈로그가 비어 있는 게 "아직 안 올라옴"이
            // 아니라 "이 기기는 원천적으로 이 기능을 못 씀"이라는 뜻이라,
            // 버그나 실수처럼 보이지 않게 분명히 말해 줘야 함
            boolean isMaliGraphicTools = currentContentType == ContentProfile.ContentType.CONTENT_TYPE_GRAPHIC_TOOLS
                    && GpuInfo.VENDOR_MALI.equals(GpuInfo.getVendor());
            emptyText.setText(isMaliGraphicTools
                    ? R.string.graphic_tools_unsupported_mali
                    : R.string.no_items_to_display_contents);
            emptyText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setAdapter(new ContentItemAdapter(profiles));
        }
    }

    private class ContentItemAdapter extends RecyclerView.Adapter<ContentItemAdapter.ViewHolder> {
        private final List<ContentProfile> data;

        private static class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView ivIcon;
            private final TextView tvVersionName;
            private final TextView tvVersionCode;
            private final ImageButton ibMenu;
            private final ImageButton ibDownload;
            private final ProgressBar progressBar;
            private final TextView tvStatus;

            public ViewHolder(@NonNull View view) {
                super(view);

                ivIcon = view.findViewById(R.id.IVIcon);
                tvVersionName = view.findViewById(R.id.TVVersionName);
                tvVersionCode = view.findViewById(R.id.TVVersionCode);
                ibMenu = view.findViewById(R.id.BTMenu);
                ibDownload = view.findViewById(R.id.BTDownload);
                progressBar = view.findViewById(R.id.Progress);
                tvStatus = view.findViewById(R.id.TVStatus);
            }
        }

        public ContentItemAdapter(List<ContentProfile> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ContentItemAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.content_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.ibMenu.setOnClickListener(null);
            super.onViewRecycled(holder);
        }

        @SuppressLint("StringFormatInvalid")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final ContentProfile profile = data.get(position);

            int iconId = switch (profile.type) {
                case CONTENT_TYPE_WINE -> R.drawable.icon_wine;
                case CONTENT_TYPE_PROTON -> R.drawable.icon_wine;
                case CONTENT_TYPE_GRAPHIC_TOOLS -> R.drawable.ic_adreno_gpu_drivers;
                default -> R.drawable.icon_settings;
            };
            holder.ivIcon.setImageDrawable(getContext().getDrawable(iconId));

            holder.tvVersionName.setText(getContext().getString(R.string.version) + ": " + profile.verName);
            holder.tvVersionCode.setText(getContext().getString(R.string.version_code) + ": " + profile.verCode);
            holder.ibMenu.setVisibility(profile.remoteUrl == null ? View.VISIBLE : View.GONE);
            // Download state at a glance: remoteUrl == null means the entry is installed
            // on this device. Installed rows get the active card and a filled chip, catalog
            // rows the plain card and an outlined chip
            // 설치 상태를 한눈에 보이게 함: remoteUrl == null이면 이 기기에 설치된 항목임.
            // 설치된 줄은 강조 카드와 채운 칩, 카탈로그 줄은 기본 카드와 테두리 칩
            boolean installed = profile.remoteUrl == null;
            holder.tvStatus.setText(installed ? R.string.content_status_installed : R.string.content_status_available);
            holder.tvStatus.setSelected(installed);
            holder.itemView.setBackgroundResource(installed ? R.drawable.neon_card_bg_active : R.drawable.neon_card_bg);
            holder.ibMenu.setOnClickListener(v -> {
                PopupMenu selectionMenu = new PopupMenu(getContext(), holder.ibMenu);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    selectionMenu.setForceShowIcon(true);
                selectionMenu.inflate(R.menu.content_popup_menu);
                selectionMenu.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.content_info) {
                        new ContentInfoDialog(getContext(), profile).show();
                    } else if (itemId == R.id.remove_content) {
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_content, () -> {
                            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                                ContainerManager containerManager = new ContainerManager(getContext());
                                for (Container container : containerManager.getContainers()) {
                                    if (container.getWineVersion().equals(ContentsManager.getEntryName(profile))) {
                                        ContentDialog.alert(getContext(), String.format(getString(R.string.unable_to_remove_content_since_container_using), container.getName()), null);
                                        return;
                                    }
                                }
                            }
                            manager.removeContent(profile);
                            loadContentList();
                        });
                    }
                    return true;
                });
                selectionMenu.show();
            });
            holder.ibDownload.setVisibility((profile.remoteUrl != null) && (holder.progressBar.getVisibility() == View.GONE) ? View.VISIBLE : View.GONE);
            holder.ibDownload.setOnClickListener(v -> {
                // Provider is recorded once the GraphicTools install succeeds
                // GraphicTools 설치가 성공하면 이 배포처를 기록함
                pendingDriverProvider = profile.provider;
                holder.ibDownload.setVisibility(View.GONE);
                holder.progressBar.setVisibility(View.VISIBLE);

                Intent intent = new Intent();
                intent.setData(Uri.parse(profile.remoteUrl));
                new Thread(() -> {
                    long timestamp = System.currentTimeMillis();
                    File output = new File(getContext().getCacheDir(), "temp_" + timestamp);
                    if (Downloader.downloadFile(profile.remoteUrl, output)) {
                        intent.setData(Uri.parse(output.getAbsolutePath()));
                    }
                    getActivity().runOnUiThread(() -> {
                        holder.progressBar.setVisibility(View.GONE);
                        holder.ibDownload.setVisibility(View.VISIBLE);
                        onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE, Activity.RESULT_OK, intent);
                    });
                }).start();
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}
