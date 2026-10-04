package com.winlator.cmod.saves;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.FileUtils;

import java.io.File;

public class CustomFilePickerActivity extends AppCompatActivity {

    private File currentDirectory;
    private RecyclerView recyclerView;
    private FileAdapter fileAdapter;
    private Button confirmButton;
    private Button upButton;
    private boolean selectFileMode; // true = picking a single file (e.g. .exe), false = picking a folder (기존 동작)
    private String extensionFilter; // e.g. ".exe" — null이면 필터 없음
    // Several accepted extensions at once (e.g. images: .png .jpg ...). Used
    // instead of extensionFilter when present
    // 여러 확장자를 한꺼번에 받음(예: 이미지 .png .jpg ...). 있으면
    // extensionFilter 대신 씀
    private String[] extensionFilters;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Same isDarkMode/theme pattern as MainActivity and SettingsFragment:
        // Theme.App.Neon.Light/Dark instead of the old AppTheme/AppTheme.Dark.
        // The manual per-view colour overrides below it are gone - the neon
        // layout (activity_file_picker.xml) now gets its colours from the
        // theme's ?attr/neon* values, the same as every other screen, so
        // there is nothing left for Java to override.
        // MainActivity, SettingsFragment와 같은 isDarkMode/테마 패턴임:
        // 옛 AppTheme/AppTheme.Dark 대신 Theme.App.Neon.Light/Dark를 씀.
        // 아래에 있던 뷰별 수동 색상 오버라이드는 없앰 - 네온 레이아웃
        // (activity_file_picker.xml)이 이제 다른 화면과 똑같이 테마의
        // ?attr/neon* 값에서 색을 가져오므로, Java가 덮어쓸 게 남지 않음
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isDarkMode = sharedPreferences.getBoolean("dark_mode", false);
        setTheme(isDarkMode ? R.style.Theme_App_Neon_Dark : R.style.Theme_App_Neon_Light);

        setContentView(R.layout.activity_file_picker);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        confirmButton = findViewById(R.id.confirmButton);
        upButton = findViewById(R.id.upButton);
        // The layout already had this button but nothing was ever bound to it
        // 레이아웃에는 이 버튼이 있었는데 아무것도 연결돼 있지 않았음
        Button storageRootButton = findViewById(R.id.storageRootButton);
        TextView pickerTitle = findViewById(R.id.TVPickerTitle);

        selectFileMode = getIntent().getBooleanExtra("selectFileMode", false);
        extensionFilter = getIntent().getStringExtra("extensionFilter");
        extensionFilters = getIntent().getStringArrayExtra("extensionFilters");

        // Optional "external" button: hands the choice back to the caller,
        // which then opens the system document picker (other apps, cloud, any
        // folder). Shown only when the caller passes a label for it
        // 선택 사항인 "외부" 버튼임: 선택을 호출한 쪽에 돌려주고, 호출한 쪽이
        // 시스템 문서 선택기(다른 앱, 클라우드, 아무 폴더)를 엶. 호출한 쪽이
        // 버튼 이름을 넘겼을 때만 보임
        Button externalButton = findViewById(R.id.externalPickerButton);
        String externalLabel = getIntent().getStringExtra("externalPickerLabel");
        if (externalLabel != null) {
            externalButton.setText(externalLabel);
            externalButton.setVisibility(android.view.View.VISIBLE);
            externalButton.setOnClickListener(v -> {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("useExternalPicker", true);
                setResult(Activity.RESULT_OK, resultIntent);
                finish();
            });
        }
        if (selectFileMode) {
            pickerTitle.setText(getIntent().getStringExtra("pickerTitle") != null
                    ? getIntent().getStringExtra("pickerTitle") : pickerTitle.getText());
            // 파일 선택 모드에서는 폴더 확인 버튼이 아니라 "이 폴더에서 취소"용으로만 남겨둠
            confirmButton.setEnabled(false);
        }

        String initialDirectoryPath = getIntent().getStringExtra("initialDirectory");
        // No boundary any more: pinning it to the container's dosdevices folder made
        // it impossible to reach internal storage or the SD card, which is exactly
        // where the game folders live.
        // 경계를 두지 않음. 컨테이너의 dosdevices 폴더로 고정해 두니 내부 저장소나
        // SD카드로 나갈 수가 없었는데, 정작 게임 폴더는 거기에 있음
        currentDirectory = new File(initialDirectoryPath != null ? initialDirectoryPath
                : Environment.getExternalStorageDirectory().getAbsolutePath());
        boolean isEditing = getIntent().getBooleanExtra("isEditing", false);
        if (isEditing) {
            String editingPath = getIntent().getStringExtra("editingPath");
            if (editingPath != null) currentDirectory = new File(editingPath);
        }

        loadFiles(currentDirectory);

        confirmButton.setOnClickListener(view -> {
            // 폴더 선택 모드(기존 동작)에서만 사용
            Intent resultIntent = new Intent();
            resultIntent.putExtra("selectedDirectory", currentDirectory.getAbsolutePath());
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        });

        upButton.setOnClickListener(view -> {
            File parentDirectory = currentDirectory.getParentFile();
            // Free navigation upward; loadFiles() already disables the button at "/"
            // 위로 자유롭게 이동. "/"에서는 loadFiles()가 알아서 버튼을 비활성화함
            if (parentDirectory != null) {
                currentDirectory = parentDirectory;
                loadFiles(currentDirectory);
                if (!selectFileMode) confirmButton.setEnabled(false);
            }
        });

        // Jump straight to internal storage or the SD card instead of walking up
        // one level at a time from deep inside the container
        // 컨테이너 깊은 곳에서 한 단계씩 올라가는 대신 내부 저장소/SD카드로 바로 이동
        storageRootButton.setOnClickListener(view -> showStorageRootPicker());
    }

    private void loadFiles(File directory) {
        File[] rawFiles = directory.listFiles();
        if (rawFiles == null) rawFiles = new File[0];

        // 파일 선택 모드일 땐 폴더 + (필터에 맞는) 파일만 보여줌
        File[] files;
        if (selectFileMode && (extensionFilter != null || extensionFilters != null)) {
            java.util.List<File> filtered = new java.util.ArrayList<>();
            for (File f : rawFiles) {
                if (f.isDirectory() || matchesFilter(f.getName())) {
                    filtered.add(f);
                }
            }
            files = filtered.toArray(new File[0]);
        } else {
            files = rawFiles;
        }

        fileAdapter = new FileAdapter(files, this::onFileClicked);
        recyclerView.setAdapter(fileAdapter);
        upButton.setEnabled(directory.getParentFile() != null);
    }

    // True when the file name ends with one of the accepted extensions
    // 파일 이름이 받는 확장자 중 하나로 끝나면 true
    private boolean matchesFilter(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ENGLISH);
        if (extensionFilters != null) {
            for (String ext : extensionFilters) {
                if (lower.endsWith(ext.toLowerCase(java.util.Locale.ENGLISH))) return true;
            }
            return false;
        }
        return lower.endsWith(extensionFilter.toLowerCase(java.util.Locale.ENGLISH));
    }

    private void onFileClicked(File file) {
        if (file.isDirectory()) {
            currentDirectory = file;
            loadFiles(currentDirectory);
            if (!selectFileMode) confirmButton.setEnabled(true);
        } else if (selectFileMode) {
            // 파일(.exe 등)을 탭하면 바로 결과 반환
            Intent resultIntent = new Intent();
            resultIntent.putExtra("selectedFile", file.getAbsolutePath());
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        }
    }
    private void showStorageRootPicker() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<File> roots = new java.util.ArrayList<>();
        labels.add("내장 저장소");
        roots.add(Environment.getExternalStorageDirectory());

        File sdCard = FileUtils.findSDCardPath(this);
        if (sdCard != null) {
            labels.add("SD카드");
            roots.add(sdCard);
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("저장소 선택")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    currentDirectory = roots.get(which);
                    loadFiles(currentDirectory);
                })
                .show();
    }
}