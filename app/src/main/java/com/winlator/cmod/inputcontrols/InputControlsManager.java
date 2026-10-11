package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.JsonReader;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

public class InputControlsManager {
    // Log tag used for profile load diagnostics
    // 프로필 로드 진단 로그용 태그임
    private static final String TAG = "InputControlsManager";

    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!profilesLoaded) loadProfiles(ignoreTemplates);
        return profiles;
    }

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        if (FileUtils.isEmpty(profilesDir)) {
            FileUtils.copy(context, "inputcontrols/profiles", profilesDir);
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        int newVersion = AppUtils.getVersionCode(context);
        int oldVersion = preferences.getInt("inputcontrols_app_version", 0);
        if (oldVersion == newVersion) return;
        preferences.edit().putInt("inputcontrols_app_version", newVersion).apply();

        File[] files = profilesDir.listFiles();
        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            if (assetFiles == null) return;
            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/"+assetFile;

                // One unreadable asset must not abort the remaining assets
                // 읽을 수 없는 asset 하나가 나머지 처리를 막지 않게 asset 단위로 예외 처리함
                ControlsProfile originProfile;
                try {
                    originProfile = loadProfile(context, assetManager.open(assetPath));
                }
                catch (IOException e) {
                    Log.w(TAG, "skip unreadable asset profile: "+assetPath, e);
                    continue;
                }

                // Asset that cannot be parsed as a profile is skipped (null guard)
                // 프로필로 해석되지 않는 asset은 건너뜀(null 방어)
                if (originProfile == null) {
                    Log.w(TAG, "skip invalid asset profile: "+assetPath);
                    continue;
                }

                File targetFile = null;
                for (File file : files) {
                    ControlsProfile targetProfile = loadProfile(context, file);
                    if (targetProfile == null) continue;
                    if (originProfile.id == targetProfile.id && originProfile.getName().equals(targetProfile.getName())) {
                        targetFile = file;
                        break;
                    }
                }

                if (targetFile != null) {
                    FileUtils.copy(context, assetPath, targetFile);
                }
            }
        }
        catch (IOException e) {}
    }

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);

                // Skip files that are not valid profiles (empty, broken JSON, directory, ...)
                // 유효한 프로필이 아닌 파일(빈 파일, 깨진 JSON, 폴더 등)은 건너뜀
                if (profile == null) {
                    Log.w(TAG, "skip invalid profile file: "+file.getName());
                    continue;
                }

                if (!(ignoreTemplates && profile.isTemplate())) profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        ControlsProfile profile = new ControlsProfile(context, ++maxProfileId);
        profile.setName(name);
        profile.save();
        profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = ++maxProfileId;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)));
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            FileUtils.writeString(newFile, data.toString());
        }
        catch (JSONException e) {}

        ControlsProfile profile = loadProfile(context, newFile);

        // Do not put a null profile into the list
        // null 프로필은 목록에 넣지 않음
        if (profile != null) profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) profiles.remove(profile);
    }

    public ControlsProfile importProfile(JSONObject data) {
        try {
            if (!data.has("id") || !data.has("name")) return null;
            int newId = ++maxProfileId;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            data.put("id", newId);
            FileUtils.writeString(newFile, data.toString());
            ControlsProfile newProfile = loadProfile(context, newFile);

            // Imported data that cannot be read back is discarded
            // 다시 읽을 수 없는 가져오기 데이터는 파일을 지우고 실패 처리함
            if (newProfile == null) {
                newFile.delete();
                return null;
            }

            int foundIndex = -1;
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile.getName().equals(newProfile.getName())) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex != -1) {
                profiles.set(foundIndex, newProfile);
            }
            else profiles.add(newProfile);
            return newProfile;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        File destination = new File(com.winlator.cmod.core.AppDirs.downloads("profiles"), profile.getName()+".icp");
        destination.getParentFile().mkdirs();
        FileUtils.copy(ControlsProfile.getProfileFile(context, profile.id), destination);
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        }
        catch (FileNotFoundException e) {
            Log.w(TAG, "profile file not found or not a file: "+file.getName());
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;
            int fieldsRead = 0;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                    fieldsRead++;
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                    fieldsRead++;
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float) reader.nextDouble();
                    fieldsRead++;
                }
                else {
                    if (fieldsRead == 3) break;
                    reader.skipValue();
                }
            }

            // A profile without a name is treated as invalid (getName() is dereferenced elsewhere)
            // 이름이 없는 프로필은 다른 곳에서 getName()을 바로 쓰므로 유효하지 않은 것으로 처리함
            if (profileName == null) {
                Log.w(TAG, "profile has no name field, id="+profileId);
                return null;
            }

            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);

            // Keep the default cursor speed when the field is missing (NaN means not read)
            // cursorSpeed 항목이 없으면(NaN) 기본값을 유지함
            if (!Float.isNaN(cursorSpeed)) profile.setCursorSpeed(cursorSpeed);
            return profile;
        }
        catch (IOException | RuntimeException e) {
            // Broken JSON, wrong value type, empty file, non-object root, ...
            // 깨진 JSON, 값 형식 오류, 빈 파일, 최상위가 객체가 아닌 경우 등
            Log.w(TAG, "failed to parse profile: "+e);
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
        return null;
    }
}
