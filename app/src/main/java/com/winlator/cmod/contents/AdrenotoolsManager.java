package com.winlator.cmod.contents;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {
    
    private File adrenotoolsContentDir;
    private Context mContext;
    
    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "imagefs/contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }
        
    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (JSONException e) {
        }
        return libraryName;
    }
    
    public String getDriverName(String adrenoToolsDriverId) {
        String driverName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverName = jsonObject.getString("name");
        }
        catch (JSONException e) {
        }
        return driverName;
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (JSONException e) {
        }
        return driverVersion;
    }

    private void reloadContainers(String adrenoToolsDriverId) {
        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            Log.d("AdrenotoolsManager", "Checking if container driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
                config.put("version", DefaultVersion.WRAPPER);
                container.setGraphicsDriverConfig(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                container.saveData();
            }     
        }
        for (Shortcut shortcut : containerManager.loadShortcuts()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));
            Log.d("AdrenotoolsManager", "Checking if shortcut driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").contains(getDriverName(adrenoToolsDriverId))) {
                Log.d("AdrenotoolsManager", "Found a match for shortcut " + shortcut.name);
                config.put("version", DefaultVersion.WRAPPER);
                shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
                shortcut.saveData();
            }
        }
    }
    
    public void removeDriver(String adrenoToolsDriverId) {
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();

        // listFiles() can return null (I/O error, permission issue, or the
        // directory not existing despite the constructor's mkdirs() call) -
        // looping over that directly threw an NPE here, which aborted
        // ContentsManager.syncContents() partway through the GraphicTools
        // type (last in the enum, see its "bug #7 fix" comment) and silently
        // skipped adding the remote/downloadable entries for it too, not
        // just the installed one - the whole tab looked empty either way.
        // listFiles()는 null을 반환할 수 있음(I/O 오류, 권한 문제, 혹은
        // 생성자의 mkdirs() 호출에도 불구하고 디렉터리가 없는 경우 등) -
        // 이걸 그대로 순회하면 여기서 NPE가 나서 ContentsManager.
        // syncContents()가 GraphicTools 타입(enum 마지막, 그쪽 "bug #7 fix"
        // 주석 참고) 처리 도중 중단되고, 설치된 항목뿐 아니라 다운로드
        // 가능한 원격 항목까지 조용히 안 채워짐 - 어느 쪽이든 탭 전체가
        // 비어 보이게 됨
        File[] files = adrenotoolsContentDir.listFiles();
        if (files == null) return driversList;

        for (File f : files) {
            boolean fromResources = isFromResources("graphics_driver/adrenotools-" + f.getName() + ".tzst");
            if (!fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }

    // Bridges installed AdrenoTools/ExynosTools drivers (stored under
    // imagefs/contents/adrenotools/<id>/meta.json, in this class's own
    // format) into ContentsManager's ContentProfile world, so
    // ContentsManager.syncContents() can list them as installed. See the
    // "bug #7 fix" comment there for why this bridge exists.
    // 설치된 AdrenoTools/ExynosTools 드라이버(imagefs/contents/adrenotools/
    // <id>/meta.json에, 이 클래스만의 형식으로 저장됨)를 ContentsManager의
    // ContentProfile 체계로 옮겨서 ContentsManager.syncContents()가 설치된
    // 것으로 나열할 수 있게 이어주는 다리 역할임. 이게 왜 필요한지는
    // ContentsManager의 "bug #7 fix" 주석 참고
    public ArrayList<ContentProfile> getInstalledProfiles() {
        ArrayList<ContentProfile> profileList = new ArrayList<>();
        for (String driverId : enumarateInstalledDrivers()) {
            String driverName = getDriverName(driverId);
            if (driverName.isEmpty()) continue;

            ContentProfile profile = new ContentProfile();
            profile.type = ContentProfile.ContentType.CONTENT_TYPE_GRAPHIC_TOOLS;
            // Not distinguished from exynostools here: both install through
            // this same class into the same directory, and subType only
            // matters for filtering *remote* catalog entries by GPU before
            // download (see ContentsFragment.loadContentList()) - an
            // already-installed driver has already passed that check once
            // 여기서는 exynostools와 구분하지 않음: 둘 다 이 클래스를 통해
            // 같은 디렉터리에 설치되고, subType은 다운로드 전에 원격 카탈로그
            // 항목을 GPU로 걸러낼 때만 의미가 있음(ContentsFragment.
            // loadContentList() 참고) - 이미 설치된 드라이버는 그 검사를
            // 한 번 통과한 상태임
            profile.subType = ContentProfile.SUBTYPE_ADRENOTOOLS;
            profile.verName = driverName;
            profile.verCode = 0;
            profile.desc = getDriverVersion(driverId);
            profile.fileList = new ArrayList<>();
            profile.remoteUrl = null;
            profileList.add(profile);
        }
        return profileList;
    }
    
    private boolean isFromResources(String driver) {
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;
        
        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }
        
        return isFromResources;
    }
        
    private boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            FileUtils.delete(dst);

        return hasExtracted;
    }
    
    // Looks one level down from root for a folder containing meta.json.
    // Bounded to a single level on purpose: a driver zip wraps everything in
    // at most one extra folder in practice, and searching deeper risks
    // matching something unrelated inside a real driver's own subfolders
    // (e.g. a "docs/" folder that happens to ship its own meta.json-named file).
    // root 바로 한 단계 아래에서 meta.json이 든 폴더를 찾음. 일부러 한 단계로
    // 제한함: 실제로 드라이버 zip이 감싸는 건 최대 한 겹뿐이고, 더 깊이
    // 찾으면 진짜 드라이버 자체의 하위 폴더(예: 우연히 같은 이름의 파일을
    // 담은 "docs/" 폴더) 안의 무관한 걸 잘못 찾을 위험이 있음
    private static File findNestedMetaJsonDir(File root) {
        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory() && new File(child, "meta.json").exists()) return child;
        }
        return null;
    }

    // Moves every entry of src up into dst, then removes the now-empty src.
    // Plain File.renameTo() per entry rather than a recursive copy: everything
    // is already on the same filesystem (both under tmpDir), so a rename is
    // just a directory-entry update, not an actual data copy.
    // src의 모든 항목을 dst로 끌어올리고, 비워진 src는 지움. 항목마다 재귀
    // 복사가 아니라 그냥 File.renameTo()를 씀: 어차피 같은 파일시스템
    // 안이라(둘 다 tmpDir 밑) rename은 실제 데이터 복사가 아니라 디렉터리
    // 항목 갱신일 뿐임
    private static void flattenInto(File src, File dst) {
        File[] children = src.listFiles();
        if (children != null) {
            for (File child : children) {
                child.renameTo(new File(dst, child.getName()));
            }
        }
        src.delete();
    }

    public String installDriver(Uri driverUri) {
        File tmpDir = new File(adrenotoolsContentDir, "tmp");
        // File.delete() only removes an empty directory, so a tmpDir left
        // over from a previous failed install (with files still in it) would
        // silently fail to delete here, and mkdirs() below would then be a
        // no-op that mixes old and new files together
        // File.delete()는 빈 디렉터리만 지울 수 있어서, 이전에 실패한 설치가
        // 남긴 tmpDir(파일이 남아있는 상태)은 여기서 조용히 삭제에 실패하고,
        // 아래 mkdirs()도 그냥 아무 일도 안 해서 예전 파일과 새 파일이 섞임
        if (tmpDir.exists()) FileUtils.delete(tmpDir);
        tmpDir.mkdirs();
        ZipInputStream zis;
        InputStream is;
        String name = "";
        
        try {
            is = mContext.getContentResolver().openInputStream(driverUri);
            zis = new ZipInputStream(is);
            ZipEntry entry = zis.getNextEntry();
            while (entry != null) {
                File dstFile = new File(tmpDir, entry.getName());
                if (entry.isDirectory()) {
                    // A directory entry has no bytes to copy, just create it
                    // 디렉터리 엔트리는 복사할 바이트가 없으니 폴더만 만들면 됨
                    dstFile.mkdirs();
                } else {
                    // The zip can have entries inside a subfolder (e.g.
                    // "lib/libname.so") without a preceding directory entry
                    // for "lib/", so dstFile's parent may not exist yet;
                    // Files.copy() then throws NoSuchFileException
                    // zip 안에 "lib/" 같은 디렉터리 엔트리 없이 바로
                    // "lib/libname.so" 같은 하위 폴더 파일 엔트리만 있을 수
                    // 있어서, dstFile의 부모 폴더가 아직 없을 수 있음;
                    // 이 상태로 Files.copy()를 부르면 NoSuchFileException 발생
                    File parent = dstFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    Files.copy(zis, dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                entry = zis.getNextEntry();
            }
            zis.close();

            // GitHub release zips very often wrap everything in one top-level
            // folder (e.g. "Turnip_v26.3.0-R5-Oneui/meta.json" instead of
            // "meta.json" at the zip root). Without this, that whole category
            // of driver packages silently "installed" (no crash, no error
            // shown) but never actually produced a usable driver - meta.json
            // was never found, so the install was just discarded.
            // GitHub 릴리스 zip은 흔히 전체를 최상위 폴더 하나로 감싸서
            // 배포함(예: 최상위에 바로 "meta.json"이 아니라
            // "Turnip_v26.3.0-R5-Oneui/meta.json"). 이게 없으면 이런 종류의
            // 드라이버 패키지는 조용히 "설치됨"으로 넘어가지만(충돌도 에러도
            // 없음) 실제로는 쓸 수 있는 드라이버가 전혀 안 만들어짐 -
            // meta.json을 못 찾아서 설치 결과가 그냥 버려지고 있었음
            if (!new File(tmpDir, "meta.json").exists()) {
                File nested = findNestedMetaJsonDir(tmpDir);
                if (nested != null) flattenInto(nested, tmpDir);
            }

            if (new File(tmpDir, "meta.json").exists()) {
                name = getDriverName(tmpDir.getName());
                File dst = new File(adrenotoolsContentDir, name);
                if (!dst.exists() && !name.equals(""))
                    tmpDir.renameTo(dst);
                else {
                    name = "";
                    FileUtils.delete(tmpDir);
                }
            }
            else {
                Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
                FileUtils.delete(tmpDir);
            }
        }
        catch (IOException e) {
            Log.d("AdrenotoolsManager", "Failed to install driver, a valid driver has not been selected");
            FileUtils.delete(tmpDir);
        }
        
        return name;
    }
    
    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        if (extractDriverFromResources(adrenotoolsDriverId) || enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
            String driverPath = adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
            if (!getLibraryName(adrenotoolsDriverId).equals("")) {
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", getLibraryName(adrenotoolsDriverId));
                if (adrenotoolsDriverId.contains("v762") && GPUInformation.getVersion().contains("512.530")) {
                    Log.d("AdrenotoolsManager", "Patching v762 driver for stock v530");
                    FileUtils.writeToBinaryFile(driverPath + "notadreno_utils.so", 0x2680, 3);
                } else if (adrenotoolsDriverId.contains("v762") && GPUInformation.getVersion().contains("512.502")) {
                    Log.d("AdrenotoolsManager", "Patching v762 driver for stock v502");
                    FileUtils.writeToBinaryFile(driverPath + "notadreno_utils.so", 0x2680, 2);
                }
            }
        }
    }
 }
