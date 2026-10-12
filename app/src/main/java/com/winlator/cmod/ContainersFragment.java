package com.winlator.cmod;

import static com.winlator.cmod.core.AppUtils.showToast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.view.ViewCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.LayerTiers;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.StorageInfoDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class ContainersFragment extends Fragment {
    private static final int REQUEST_CODE_IMPORT_CONTAINER = 1070;
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private LinearLayout tierRow;
    private View tierPanel;
    private View listFrame;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        // Containers made before tier names get the tier mark and name once
        // 단계 이름 이전에 만든 컨테이너에 단계 표시와 이름을 한 번 붙임
        com.winlator.cmod.container.LayerTiers.adoptUntagged(getContext(), manager);
        loadContainersList();
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.containers);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View frameLayout = inflater.inflate(R.layout.containers_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        tierRow = frameLayout.findViewById(R.id.LLTierRow);
        tierPanel = frameLayout.findViewById(R.id.SVTierPanel);
        listFrame = frameLayout.findViewById(R.id.FLContainerList);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        // No divider: each row is a neon card with its own margins now.
        // 구분선 없음: 이제 각 행이 자기 여백을 가진 네온 카드임
        return frameLayout;
    }

    private void loadContainersList() {
        ArrayList<Container> containers = manager.getContainers();
        recyclerView.setAdapter(new ContainersAdapter(containers));
        emptyTextView.setVisibility(containers.isEmpty() ? View.VISIBLE : View.GONE);
        refreshTierRow();
    }

    // EZ mode shows only the tier buttons; EZ mode off shows only the container
    // list. The tier panel is sized to the screen: three cards side by side on a
    // wide screen (600dp and up), stacked on a narrow one, at most 1100dp wide
    // and centred - it does not stretch to the bottom of the screen
    // EZ 모드는 단계 버튼만, EZ 모드를 끄면 컨테이너 목록만 보여 줌. 단계 판은 화면에
    // 맞춤: 넓은 화면(600dp 이상)에서는 카드 3개를 가로로, 좁은 화면에서는 세로로
    // 쌓고, 너비는 최대 1100dp로 가운데에 둠 - 화면 아래까지 늘어나지 않음
    private boolean isEzMode() {
        return getContext() != null && ShortcutsFragment.isEzMode(getContext());
    }

    // Rotation keeps this fragment, so the panel is laid out again for the new width
    // 화면을 돌려도 이 화면은 그대로 남으므로, 새 너비에 맞춰 판을 다시 배치함
    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (tierRow != null) tierRow.post(this::refreshTierRow);
    }

    // Rebuilds the three tier buttons from the current containers
    // 현재 컨테이너 상태로 단계 버튼 3개를 다시 만듦
    private void refreshTierRow() {
        if (tierRow == null || getContext() == null) return;
        boolean ez = isEzMode();
        tierPanel.setVisibility(ez ? View.VISIBLE : View.GONE);
        listFrame.setVisibility(ez ? View.GONE : View.VISIBLE);
        if (!ez) return;

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float widthDp = dm.widthPixels / dm.density;
        boolean wide = widthDp >= 600;
        tierRow.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        tierRow.setBaselineAligned(false);
        android.widget.FrameLayout.LayoutParams rowLp = (android.widget.FrameLayout.LayoutParams) tierRow.getLayoutParams();
        rowLp.width = (int) Math.min(dm.widthPixels * 0.92f, 1100 * dm.density);
        tierRow.setLayoutParams(rowLp);

        tierRow.removeAllViews();
        for (LayerTiers.Tier tier : LayerTiers.ALL) {
            View card = createTierCard(tier);
            if (wide) {
                // Side by side: every card as tall as the tallest one (the middle
                // card is usually the longest), with the button at the bottom
                // 가로 배치: 모든 카드를 가장 긴 카드(보통 가운데 카드) 높이에 맞추고,
                // 버튼은 아래에 둠
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                card.setLayoutParams(lp);
            } else {
                // Stacked: full width instead of an equal share of the row
                // 세로로 쌓을 때는 가로 몫을 나누지 않고 전체 너비를 씀
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.weight = 0;
                card.setLayoutParams(lp);
            }
            tierRow.addView(card);
        }
    }

    // One tier button (layout/tier_card.xml): name, state per architecture, the
    // components each architecture uses, and an Install button that becomes
    // "Installed" (disabled) once both containers exist
    // 단계 버튼 하나(layout/tier_card.xml): 이름, 아키텍처별 상태, 아키텍처마다 쓰는
    // 구성요소, 두 컨테이너가 다 생기면 "설치됨"(누를 수 없음)으로 바뀌는 설치하기 버튼
    private View createTierCard(LayerTiers.Tier tier) {
        View card = LayoutInflater.from(getContext()).inflate(R.layout.tier_card, tierRow, false);
        boolean[] have = LayerTiers.existing(manager, tier);
        boolean ready = have[0] && have[1];

        ((TextView) card.findViewById(R.id.TVTierTitle)).setText(tier.labelRes);
        ((TextView) card.findViewById(R.id.TVTierState)).setText(
                "ARM64EC " + (have[0] ? "\u2713" : "\u2013") + "  \u00b7  X86_64 " + (have[1] ? "\u2713" : "\u2013"));
        ((TextView) card.findViewById(R.id.TVTierDesc)).setText(
                LayerTiers.describe(tier.arm64ec) + "\n" + LayerTiers.describe(tier.x86_64));

        android.widget.Button install = card.findViewById(R.id.BTTierInstall);
        install.setText(ready ? R.string.tier_installed : R.string.tier_install);
        install.setEnabled(!ready);
        install.setAlpha(ready ? 0.5f : 1f);
        if (!ready) install.setOnClickListener(v -> confirmCreateTier(tier));
        return card;
    }

    // Asks, then downloads the tier's components and creates its missing
    // containers on a worker thread, with progress in the preloader dialog
    // 확인을 받은 뒤 작업 스레드에서 단계의 구성요소를 받고 빠진 컨테이너를 만듦.
    // 진행 상황은 진행창에 표시함
    private void confirmCreateTier(LayerTiers.Tier tier) {
        String label = getString(tier.labelRes);
        new AlertDialog.Builder(getContext())
                .setTitle(getString(R.string.tier_create_title, label))
                .setMessage(getString(R.string.tier_create_message, label,
                        LayerTiers.describe(tier.arm64ec) + "\n" + LayerTiers.describe(tier.x86_64)))
                .setPositiveButton(R.string.tier_create_ok, (d, w) -> runCreateTier(tier))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runCreateTier(LayerTiers.Tier tier) {
        Activity activity = getActivity();
        if (activity == null) return;
        Context app = activity.getApplicationContext();
        boolean[] archs = LayerTiers.missingArchs(manager, tier);
        preloaderDialog.showOnUiThread(R.string.tier_preparing);
        Executors.newSingleThreadExecutor().execute(() -> {
            int created = 0;
            try {
                created = LayerTiers.createTier(app, tier, archs,
                        message -> activity.runOnUiThread(() -> preloaderDialog.setMessage(message)));
            } catch (Exception e) {
                Log.e("ContainersFragment", "tier creation failed: " + tier.id, e);
            }
            final int count = created;
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                showToast(app, count > 0 ? app.getString(R.string.tier_done, count) : app.getString(R.string.tier_failed));
                if (isAdded()) {
                    manager = new ContainerManager(getContext());
                    loadContainersList();
                }
            });
        });
    }



    @Override
    public void onResume() {
        super.onResume();
        // re-create the menu so the icon always matches current favorite
        requireActivity().invalidateOptionsMenu();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.containers_menu, menu);
        // Adding a container by hand is an EZ-mode-off action (tier buttons cover EZ mode)
        // 컨테이너 직접 추가는 EZ 모드를 껐을 때의 기능임(EZ 모드는 단계 버튼이 대신함)
        MenuItem add = menu.findItem(R.id.containers_menu_add);
        if (add != null) add.setVisible(!isEzMode());
        // Only the add action is left in this menu. The favorite star and the
        // Big Picture toggle were removed, so nothing else needs tinting here.
        // 이 메뉴에는 추가 동작만 남음. 즐겨찾기 별표와 빅픽처 전환은 제거했으므로
        // 여기서 따로 색을 입힐 항목이 없음
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.containers_menu_add:
                if (!ImageFs.find(getContext()).isValid()) return false;
                FragmentManager fragmentManager = getParentFragmentManager();
                fragmentManager.beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                        .addToBackStack(null)
                        .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                        .commit();
                return true;


            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }


    // Show confirmation dialog before importing the selected container
    private void showImportConfirmationDialog(Uri uri, File importDir) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Confirm Import");
        builder.setMessage("You selected: " + importDir.getPath() + ". Proceed to import the container?");
        builder.setPositiveButton("Import", (dialog, which) -> {
            importContainer(uri); // Proceed with the import
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_CONTAINER && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    // Get the directory path directly from the Uri using FileUtils
                    File importDir = FileUtils.getFileFromUri(getContext(), uri);
                    if (importDir == null || !importDir.isDirectory()) {
                        AppUtils.showToast(getContext(), "Invalid container directory.");
                        return;
                    }
                    // Show confirmation dialog before importing
                    showImportConfirmationDialog(uri, importDir);
                }
            }
        }
    }

    private void importContainer(Uri uri) {
        if (uri == null) return;

        // Get the directory path directly from the Uri using FileUtils
        File importDir = FileUtils.getFileFromUri(getContext(), uri);
        if (importDir == null || !importDir.isDirectory()) {
            AppUtils.showToast(getContext(), "Invalid container directory.");
            return;
        }

        preloaderDialog.show(R.string.importing_container);

        // Run the import operation on a background thread
        new Thread(() -> {
            try {
                // Now use the import directory directly for importing the container
                manager.importContainer(importDir, () -> {
                    // This callback runs when the import operation completes
                    getActivity().runOnUiThread(() -> {
                        // Load containers and close preloader dialog on the UI thread
                        loadContainersList();
                        AppUtils.showToast(getContext(), "Container imported successfully.");
                        preloaderDialog.close(); // Move this inside the callback
                        // The export left Visual C++ 2015-2022 DLLs out: when the imported
                        // container has it on and this device has none kept, say where
                        // they will come from (the newest container is the imported one)
                        // 내보낼 때 Visual C++ 2015~2022 DLL을 뺐음: 가져온 컨테이너가 켜 둔
                        // 상태인데 이 기기에 보관본이 없으면 어디서 생기는지 안내함(가장 마지막
                        // 컨테이너가 가져온 것임)
                        java.util.ArrayList<Container> all = manager.getContainers();
                        if (!all.isEmpty()) {
                            com.winlator.cmod.core.VCRedistInstaller.notifyIfMissingAfterImport(
                                    getActivity(), all.get(all.size() - 1).getWinComponents());
                        }
                    });
                });
            } catch (Exception e) {
                getActivity().runOnUiThread(() -> {
                    preloaderDialog.close(); // Ensure dialog closes on error
                    AppUtils.showToast(getContext(), "Error importing container: " + e.getMessage());
                });
            }
        }).start();
    }


    private boolean copyDocumentFileToDirectory(DocumentFile sourceDir, File targetDir) {
        if (!sourceDir.isDirectory()) return false;

        for (DocumentFile file : sourceDir.listFiles()) {
            File targetFile = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                if (!targetFile.mkdirs()) return false;
                if (!copyDocumentFileToDirectory(file, targetFile)) return false;
            } else {
                try (InputStream in = getContext().getContentResolver().openInputStream(file.getUri());
                     OutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private class ContainersAdapter extends RecyclerView.Adapter<ContainersAdapter.ViewHolder> {
        private final List<Container> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton; // Changed to ImageButton
            private final ImageView menuButton; // Changed to ImageButton
            private final ImageView imageView;
            private final TextView title;


            private ViewHolder(View view) {
                super(view);
                this.runButton = view.findViewById(R.id.BTRun); // Find by correct ID
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ContainersAdapter(List<Container> data) {
            this.data = data;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.container_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.runButton.setOnClickListener(null); // Remove listeners
            holder.menuButton.setOnClickListener(null); // Remove listeners
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Container item = data.get(position); // Use 'item' instead of undefined 'container'
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(item.getName());

            holder.runButton.setOnClickListener(view -> runContainer(item)); // Correct item reference

            holder.menuButton.setOnClickListener(view -> showListItemMenu(view, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void runContainer(Container container) {
            // Controllers are auto-detected and hot-plugged at runtime, so blocking the
            // launch to warn about their absence just adds a tap. Launch either way.
            // 컨트롤러는 실행 중에 자동 감지·연결되므로, 없다고 실행을 막고 경고하는 건
            // 탭만 한 번 더 늘릴 뿐임. 있든 없든 그냥 실행함
            proceedWithLaunch(container);
        }
        private void proceedWithLaunch(Container container) {
            final Context context = getContext();

            File box64File = new File(context.getFilesDir(), "imagefs/usr/bin/box64");
            if (box64File.exists()) {
                box64File.delete();
                Log.i("ContainersFragment", "Deleted existing box64 to ensure a clean launch.");
            }

            // XR branch removed with XrActivity: always launch the normal display
            // XrActivity와 함께 XR 분기를 제거함: 항상 일반 화면으로 실행함
            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            requireActivity().startActivity(intent);
        }

        private void showListItemMenu(View anchorView, Container container) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            listItemMenu.inflate(R.menu.container_popup_menu);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                switch (menuItem.getItemId()) {
                    case R.id.container_edit:
                        FragmentManager fragmentManager = getParentFragmentManager();
                        fragmentManager.beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment(container.id))
                                .commit();
                        break;
                    case R.id.container_duplicate:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_duplicate_this_container, () -> {
                            preloaderDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            preloaderDialog.show(R.string.removing_container);
                            for (Shortcut shortcut : manager.loadShortcuts()) {
                                if (shortcut.container == container)
                                    ShortcutsFragment.disableShortcutOnScreen(context, shortcut);
                            }
                            manager.removeContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_info:
                        (new StorageInfoDialog(getActivity(), container)).show();
                        break;
                    case R.id.container_reconfigure:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_reconfigure_wine, () -> {
                            new File(container.getRootDir(), ".wine/.update-timestamp").delete();
                        });
                        break;
                    case R.id.container_export:
                        exportContainer(container);
                        break;
                    case R.id.container_manage_saves:
                        // Saves is no longer a top-level drawer screen; it is
                        // reached per-container from here, pre-filtered to
                        // just this container's saves via SavesFragment's
                        // ARG_CONTAINER_ID argument.
                        // Saves는 더 이상 최상위 드로어 화면이 아니고, 여기서
                        // 컨테이너별로 들어감. SavesFragment의
                        // ARG_CONTAINER_ID 인자로 이 컨테이너 것만 걸러서 보여줌
                        SavesFragment savesFragment = new SavesFragment();
                        Bundle savesArgs = new Bundle();
                        savesArgs.putInt(SavesFragment.ARG_CONTAINER_ID, container.id);
                        savesFragment.setArguments(savesArgs);
                        getParentFragmentManager().beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, savesFragment)
                                .commit();
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        private void exportContainer(Container container) {
            File backupDir = com.winlator.cmod.core.AppDirs.downloads("Backups/Containers");
            preloaderDialog.show(R.string.exporting_container);

            manager.exportContainer(container, () -> {
                preloaderDialog.close(); // Ensure the dialog is closed after operation
                showToast("Container exported successfully to " + backupDir.getPath());
            });
        }

        private void showToast(String message) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

}