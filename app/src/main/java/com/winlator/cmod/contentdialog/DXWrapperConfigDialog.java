package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.DXVKConfig;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.VKD3DVersionItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Dialog for configuring VKD3D-Proton (Direct3D 12 -> Vulkan translation).
// Also exposes the DXVK version applied alongside VKD3D, since real Wine/Proton
// setups always run DXVK (D3D9-11) and VKD3D (D3D12) together, not one or the other.
// VKD3D-Proton(Direct3D 12 -> Vulkan 변환) 설정 화면.
// 실제 Wine/Proton 환경에서는 DXVK(D3D9~11)와 VKD3D(D3D12)가 항상 같이 동작하기 때문에,
// VKD3D와 함께 적용될 DXVK 버전도 이 화면에서 같이 고를 수 있게 함
public class DXWrapperConfigDialog extends ContentDialog {
    // VKD3D's config extends DXVK's default config, so a "version" (DXVK) key
    // already exists here even before this dialog had a spinner for it.
    // VKD3D 설정은 DXVK 기본 설정을 포함하는 구조라, 이 화면에 스피너가 생기기 전부터
    // 이미 "version"(DXVK 버전) 키 자체는 내부적으로 존재하고 있었음
    public static final String DEFAULT_CONFIG = DXVKConfig.DEFAULT_CONFIG +
            ",vkd3dVersion=" + DefaultVersion.VKD3D + ",vkd3dLevel=12_1";
    public static final String[] VKD3D_FEATURE_LEVEL = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};
    private final Context context;
    private List<String> dxvkVersions;

    private final boolean isArm64EC;

    public DXWrapperConfigDialog(View anchor, boolean isArm64EC) {
        super(anchor.getContext(), R.layout.vkd3d_config_dialog);
        context = anchor.getContext();
        this.isArm64EC = isArm64EC;
        setIcon(R.drawable.icon_settings);
        setTitle("DX Wrapper " + context.getString(R.string.configuration));

        final Spinner sDxvkVersion = findViewById(R.id.SDxvkVersion);
        final Spinner sVkd3dVersion = findViewById(R.id.SVKD3D_Version);
        final Spinner sFeatureLevel = findViewById(R.id.SFeatureLevel);

        // Merge locally-installed profiles with the remote catalog (contents.json)
        // 로컬에 이미 설치된 항목과 원격 카탈로그(contents.json)의 항목을 합쳐서 보여줌
        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        loadDxvkVersionSpinner(contentsManager, sDxvkVersion);
        loadVkd3dVersionSpinner(contentsManager, sVkd3dVersion);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, VKD3D_FEATURE_LEVEL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sFeatureLevel.setAdapter(adapter);

        // Retrieve and apply previously saved config values
        // 이전에 저장된 설정값을 불러와서 화면에 반영
        KeyValueSet config = parseConfig(anchor.getTag());
        // Use setSpinnerSelectionFromValue, not ...FromIdentifier: the identifier
        // variant lowercases the spinner text before comparing, so a saved value like
        // "Dxvk-3.0.2-arm64ec-gplasync-0" never matched and the dialog silently reset
        // to the first entry every time it was opened.
        // ...FromIdentifier가 아니라 setSpinnerSelectionFromValue를 써야 함.
        // Identifier 쪽은 비교 전에 스피너 텍스트를 소문자로 바꾸기 때문에
        // "Dxvk-3.0.2-arm64ec-gplasync-0" 같은 저장값이 절대 매칭되지 않아,
        // 화면을 열 때마다 조용히 첫 항목으로 초기화되고 있었음
        AppUtils.setSpinnerSelectionFromValue(sDxvkVersion, config.get("version"));
        setSpinnerSelectionByIdentifier(sVkd3dVersion, config.get("vkd3dVersion"));
        AppUtils.setSpinnerSelectionFromIdentifier(sFeatureLevel, config.get("vkd3dLevel"));

        // Save selection on confirmation
        // 확인 버튼을 누르면 선택값 저장
        setOnConfirmCallback(() -> {
            // Both spinners are empty until DXVK / VKD3D have actually been downloaded,
            // and getSelectedItem() returns null in that state. Keep whatever was saved
            // before instead of crashing on confirm.
            // DXVK / VKD3D를 아직 받지 않았으면 두 스피너가 비어 있고, 그 상태에서
            // getSelectedItem()은 null을 반환함. 확인 시 크래시하는 대신 기존에
            // 저장돼 있던 값을 그대로 유지함
            Object dxvkItem = sDxvkVersion.getSelectedItem();
            if (dxvkItem != null) config.put("version", dxvkItem.toString());

            Object vkd3dItem = sVkd3dVersion.getSelectedItem();
            if (vkd3dItem instanceof VKD3DVersionItem)
                config.put("vkd3dVersion", ((VKD3DVersionItem) vkd3dItem).getIdentifier());

            Object levelItem = sFeatureLevel.getSelectedItem();
            if (levelItem != null) config.put("vkd3dLevel", levelItem.toString());

            anchor.setTag(config.toString());
        });
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
    }

    // Populate the DXVK version spinner (the version applied together with VKD3D).
    // Same logic as the old DXVKConfigDialog's loadDxvkVersionSpinner(), duplicated
    // here because this is a separate dialog class (that dialog's UI was dead code
    // and has been removed; see core/DXVKConfig.java for what was kept from it).
    // DXVK 버전 스피너 채우기(VKD3D와 함께 적용될 버전).
    // 예전 DXVKConfigDialog의 loadDxvkVersionSpinner()와 같은 로직인데, 별도
    // 다이얼로그 클래스라 여기 그대로 복제해서 씀 (그 다이얼로그 UI는 죽은
    // 코드라 삭제됐고, 거기서 남긴 부분은 core/DXVKConfig.java 참고함)
    private void loadDxvkVersionSpinner(ContentsManager manager, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> itemList = new ArrayList<>();
        // Only show entries matching the current Wine architecture.
        // 현재 Wine 아키텍처와 일치하는 항목만 보여줌
        for (String item : originalItems) {
            boolean itemIsArm64EC = item.contains("arm64ec");
            if (itemIsArm64EC == isArm64EC) itemList.add(item);
        }

        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            String version = entryName.substring(firstDashIndex + 1);
            boolean itemIsArm64EC = version.contains("arm64ec");
            if (itemIsArm64EC == isArm64EC) itemList.add(version);
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        dxvkVersions = itemList;
    }

    // Populate the VKD3D-Proton version spinner
    // VKD3D-Proton 버전 스피너 채우기
    private void loadVkd3dVersionSpinner(ContentsManager manager, Spinner spinner) {
        List<VKD3DVersionItem> itemList = new ArrayList<>();

        // Add predefined (bundled) versions — filtered by current Wine architecture
        // 미리 정의된(번들된) 버전 추가 — 현재 Wine 아키텍처에 맞는 것만
        String[] originalItems = context.getResources().getStringArray(R.array.vkd3d_version_entries);
        for (String version : originalItems) {
            boolean itemIsArm64EC = version.contains("arm64ec");
            if (itemIsArm64EC == isArm64EC) itemList.add(new VKD3DVersionItem(version, 0));
        }

        // Add installed / catalog content profiles — same filtering
        // 설치됐거나 카탈로그에 등록된 항목 추가 — 동일하게 필터링
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            String displayName = profile.verName;
            boolean itemIsArm64EC = displayName.toLowerCase().contains("arm64ec");
            if (itemIsArm64EC == isArm64EC) {
                int versionCode = profile.verCode;
                itemList.add(new VKD3DVersionItem(displayName, versionCode));
            }
        }

        ArrayAdapter<VKD3DVersionItem> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList);
        spinner.setAdapter(adapter);
    }

    // Find and select the spinner entry matching a saved identifier
    // 저장된 식별자와 일치하는 스피너 항목을 찾아서 선택
    private void setSpinnerSelectionByIdentifier(Spinner spinner, String identifier) {
        for (int i = 0; i < spinner.getCount(); i++) {
            VKD3DVersionItem item = (VKD3DVersionItem) spinner.getItemAtPosition(i);
            if (item.getIdentifier().equals(identifier)) {
                spinner.setSelection(i);
                break;
            }
        }
    }
}