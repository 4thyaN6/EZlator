package com.winlator.cmod.xenvironment;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PackagePathFixer;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import androidx.preference.PreferenceManager;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 24;

    // imagefs is no longer bundled as an APK asset - it's downloaded on first
    // run instead, to keep the distributed app size small. Wine/Proton is
    // handled the same way, through RECOMMENDED_CONTENTS in MainActivity.
    // imagefs는 더 이상 APK에 번들되지 않고 첫 실행 시 다운로드됨 - 배포하는
    // 앱 용량을 작게 유지하기 위함. Wine/Proton도 같은 방식으로, MainActivity의
    // RECOMMENDED_CONTENTS를 통해 처리됨
    private static final String IMAGEFS_REMOTE_URL =
            "https://github.com/The412Banner/winlator-contents/releases/download/base-assets-v1/imagefs.tar.zst";

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(imgVersion) <= 5) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    // NOTE: installWineFromAssets() was removed here. Wine/Proton now comes
    // exclusively through ContentsManager (see RECOMMENDED_CONTENTS in
    // MainActivity.java), which WineInfo.fromIdentifier() already resolves
    // correctly - no bundled "/opt/<version>" asset extraction needed anymore.
    // 참고: installWineFromAssets()는 여기서 삭제됨. Wine/Proton은 이제
    // ContentsManager(전부 MainActivity.java의 RECOMMENDED_CONTENTS)를 통해서만
    // 들어오고, WineInfo.fromIdentifier()가 이미 이걸 정상적으로 처리함 -
    // 더 이상 번들된 "/opt/<버전>" asset 압축 해제가 필요 없음

    // Downloads imagefs from a remote URL, then extracts it. Shared by both
    // installFromAssets() overloads below so the download/extract logic lives
    // in exactly one place.
    // 원격 URL에서 imagefs를 다운로드한 뒤 압축을 품. 아래 installFromAssets()
    // 두 오버로드가 공통으로 이 함수를 쓰게 해서, 다운로드/압축해제 로직이
    // 한 곳에만 존재하게 함
    private static boolean downloadAndExtractImageFs(final MainActivity activity, File rootDir, DownloadProgressDialog dialog) {
        // Step 1: download imagefs into a temp file in the app's cache dir
        // 1단계: imagefs를 앱 캐시 폴더 안 임시 파일로 다운로드
        File tempFile = new File(activity.getCacheDir(), "imagefs_download.tmp");
        // Show the imagefs size and bytes received while it downloads, the
        // same per-file readout as the first-run recommended downloads.
        // Posted at most every 100 ms; the last update always goes through.
        // imagefs를 받는 동안 전체 용량과 받은 양을 보여줌. 최초 실행 추천
        // 다운로드와 같은 파일별 표시임. 최대 100ms마다 한 번만 갱신하고,
        // 마지막 갱신은 항상 통과시킴
        final long[] lastPost = {0L};
        boolean downloaded = Downloader.downloadFile(IMAGEFS_REMOTE_URL, tempFile, (done, total) -> {
            long now = System.currentTimeMillis();
            boolean finished = total > 0 && done >= total;
            if (!finished && now - lastPost[0] < 100) return;
            lastPost[0] = now;
            activity.runOnUiThread(() -> dialog.setFileProgress(done, total));
        });

        if (!downloaded) {
            Log.e("ImageFsInstaller", "imagefs download failed from " + IMAGEFS_REMOTE_URL);
            return false;
        }

        // Step 2: extract the downloaded archive into the root filesystem,
        // reporting progress back to the dialog as it goes.
        // 2단계: 다운로드한 압축 파일을 루트 파일시스템에 풀면서,
        // 진행률을 다이얼로그에 계속 보고함
        // Download done - the bar is reused for extraction progress below.
        // 다운로드 끝 - 아래에서 막대를 압축 해제 진행률용으로 다시 씀
        activity.runOnUiThread(() -> {
            dialog.setProgress(0);
            dialog.setFileStatus("압축 해제 중…");
        });

        final byte compressionRatio = 24;
        final long contentLength = (long)(tempFile.length() * (100.0f / compressionRatio));
        AtomicLong totalSizeRef = new AtomicLong();

        boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, tempFile, rootDir, (file, size) -> {
            if (size > 0) {
                long totalSize = totalSizeRef.addAndGet(size);
                final int progress = (int)(((float)totalSize / contentLength) * 100);
                activity.runOnUiThread(() -> dialog.setProgress(progress));
            }
            return file;
        });

        tempFile.delete();

        // The shared imagefs was packaged for com.winlator.cmod. Rewrite any
        // leftover text references to that package and log binaries that still
        // carry it, so a renamed build fails loudly in logcat instead of silently.
        // 공용 imagefs는 com.winlator.cmod 기준으로 만들어진 것임. 남아 있는 텍스트
        // 참조는 현재 패키지로 고치고, 바이너리에 박힌 건 logcat에 경고로 남겨서
        // 이름을 바꾼 빌드가 조용히 실패하지 않게 함
        if (success) PackagePathFixer.fix(activity, rootDir);
        return success;
    }

    public static void installFromAssets(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);

            boolean success = downloadAndExtractImageFs(activity, rootDir, dialog);

            if (success) {
                imageFs.createImgVersionFile(LATEST_VERSION);
                resetContainerImgVersions(activity);
                installGuestLibs(activity);   // libevshim.so 설치
                Log.d("ImageFsInstaller", "imagefs install succeeded (downloaded)");
            }
            else {
                AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                Log.e("ImageFsInstaller", "imagefs install failed (success=false)");
            }

            dialog.closeOnUiThread();
        });
    }

    public static void installFromAssets(final MainActivity activity, final Runnable onCompletion) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);

            boolean success = downloadAndExtractImageFs(activity, rootDir, dialog);

            if (success) {
                imageFs.createImgVersionFile(LATEST_VERSION);
                resetContainerImgVersions(activity);
                installGuestLibs(activity);
                Log.d("ImageFsInstaller", "imagefs install succeeded (downloaded, onCompletion variant)");
            }
            else {
                AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                Log.e("ImageFsInstaller", "imagefs install failed (onCompletion variant, success=false)");
            }

            dialog.closeOnUiThread();
            if (onCompletion != null) {
                activity.runOnUiThread(onCompletion);
            }
        });
    }

    public static void installIfNeeded(final MainActivity activity) {
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) installFromAssets(activity);
        else refreshGuestLibsIfApkChanged(activity);
    }

    /**
     * BUG FIX: libevshim.so / libSDL2 are copied from the APK into imagefs/usr/lib
     * only when imagefs is installed. After an APK update the games kept loading
     * the OLD copies (LD_PRELOAD points at imagefs), so native fixes such as the
     * evshim pread() fix never reached a device that already had imagefs.
     * The copy now also runs once whenever the installed APK changes.
     * 버그 수정: libevshim.so/libSDL2는 imagefs를 설치할 때만 APK에서
     * imagefs/usr/lib로 복사됐음. APK를 업데이트해도 게임은 계속 옛 복사본을
     * 불러와서(LD_PRELOAD가 imagefs를 가리킴), evshim pread() 수정 같은 네이티브
     * 수정이 imagefs가 이미 있는 기기에는 전혀 적용되지 않았음.
     * 이제 설치된 APK가 바뀔 때마다 한 번씩 복사함
     */
    private static void refreshGuestLibsIfApkChanged(Context ctx) {
        try {
            long stamp = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
            android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            if (prefs.getLong("guest_libs_apk_stamp", 0L) == stamp) return;
            installGuestLibs(ctx);
            prefs.edit().putLong("guest_libs_apk_stamp", stamp).apply();
            Log.i("ImageFsInstaller", "Guest libs refreshed for APK update " + stamp);
        } catch (Exception e) {
            Log.w("ImageFsInstaller", "Guest lib refresh failed", e);
        }
    }

    public static void installIfNeeded(final MainActivity activity, final Runnable onAllInstalled) {
        ImageFs imageFs = ImageFs.find(activity);
        if (imageFs.isValid() && imageFs.getVersion() >= LATEST_VERSION) refreshGuestLibsIfApkChanged(activity);

        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) {
            // Merged "required" + "recommended" prompt into a single dialog with
            // two choices, instead of two separate dialogs shown back-to-back.
            // "필수"와 "추천"을 따로 두 번 물어보는 대신, 하나의 창에서
            // 두 가지 선택지로 한 번만 물어보게 합침
            new AlertDialog.Builder(activity)
                    .setTitle("구성요소 다운로드 필요")
                    .setMessage("EZlator를 사용하려면 최초 1회 필수 구성요소(약 280MB)를 받아야 합니다.\n\n" +
                            "추가로 최신 그래픽 호환성 구성요소(DXVK/VKD3D/FEXCore/Box64, 약 20MB)와 " +
                            "기기에 맞는 그래픽 드라이버(감지된 경우, 약 5~15MB)도 함께 받으시겠습니까?\n\n" +
                            // Android gives apps no uninstall hook, so shared-storage
                            // folders cannot be removed automatically - users are told up front
                            // 안드로이드는 앱 삭제 시점에 코드를 실행할 수단을 주지 않아서
                            // 공용 저장소 폴더를 자동으로 지울 수 없음 - 미리 알려줌
                            "※ 앱을 삭제해도 내장·외장 메모리의 EZlator 폴더와 다운로드 폴더에 저장한 " +
                            "게임·백업·로그 파일은 지워지지 않습니다. 필요 없으면 파일 앱에서 직접 삭제하세요.")
                    .setCancelable(false)
                    .setPositiveButton("필수만 받기", (dialog, which) -> {
                        installFromAssets(activity, () -> {
                            PreferenceManager.getDefaultSharedPreferences(activity)
                                    .edit().putBoolean("recommended_contents_installed", true).apply();
                            if (onAllInstalled != null) onAllInstalled.run();
                        });
                    })
                    .setNegativeButton("전부 받기", (dialog, which) -> {
                        installFromAssets(activity, () -> {
                            activity.installRecommendedContentsInBackground();
                        });
                    })
                    .show();
        }
        else if (onAllInstalled != null) {
            onAllInstalled.run();
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) {
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }

    private static void installGuestLibs(Context ctx) {
        // libevshim.so and its SDL2 dependencies are compiled natively via CMake
        // and already ship inside the APK's own native library folder - there's
        // no separate assets/evshim.tzst archive. Copy them into imagefs/usr/lib
        // so wine (running inside imagefs) can find them via LD_LIBRARY_PATH.
        // libevshim.so와 SDL2 관련 라이브러리는 CMake가 네이티브로 컴파일해서
        // 이미 APK 자체의 네이티브 라이브러리 폴더 안에 들어있음 - 별도의
        // assets/evshim.tzst 파일은 존재하지 않음. wine이 LD_LIBRARY_PATH로
        // 찾을 수 있게 imagefs/usr/lib로 복사해줌
        String nativeLibDir = ctx.getApplicationInfo().nativeLibraryDir;
        File destDir = new File(ctx.getFilesDir(), "imagefs/usr/lib");
        if (!destDir.exists()) destDir.mkdirs();

        // Only libSDL2.so actually exists in the APK - the Android SDL2 build emits
        // that single name. libevshim.so, however, is linked against the SONAME
        // "libSDL2-2.0.so.0", so dlopen() fails at runtime with
        //   "dlopen failed: library libSDL2-2.0.so.0 not found"
        // which in turn kills Wine's HID bus (err:hid:sdl_bus_init) and leaves the
        // guest with no gamepad devices at all. Copy libSDL2.so under the alias
        // names as well so the SONAME resolves.
        // APK에는 libSDL2.so 하나만 존재함 - 안드로이드 SDL2 빌드가 그 이름만 만듦.
        // 그런데 libevshim.so는 SONAME "libSDL2-2.0.so.0"으로 링크돼 있어서
        // 실행 중 dlopen이 "library libSDL2-2.0.so.0 not found"로 실패하고,
        // 그 여파로 Wine의 HID 버스(err:hid:sdl_bus_init)가 죽어 게스트에
        // 게임패드 장치가 하나도 안 생김. libSDL2.so를 별칭 이름으로도 복사해서
        // SONAME이 해결되게 함
        File sdlSrc = new File(nativeLibDir, "libSDL2.so");

        File evshimSrc = new File(nativeLibDir, "libevshim.so");
        if (evshimSrc.exists()) {
            try {
                File dst = new File(destDir, "libevshim.so");
                FileUtils.copy(evshimSrc, dst);
                chmod(dst);
            } catch (Exception e) {
                Log.e("ImageFsInstaller", "Failed to copy libevshim.so", e);
            }
        } else {
            Log.e("ImageFsInstaller", "libevshim.so missing from APK - controllers will not work");
        }

        // ROOT CAUSE of the dead pads: the APK's libSDL2 is the Android port
        // (needs a Java VM / SDLActivity). Copying it over imagefs's own SDL made
        // SDL_Init fail in every Wine process ("did you include SDL_main.h"), and
        // forcing it on crashed Wine. GameNative and WinNative never replace the
        // imagefs SDL - they use it as-is; WinNative only adds the
        // libSDL2-2.0.so.0 -> libSDL2-2.0.so name link. Same here now.
        // A device where the old code already overwrote the files needs
        // "Reinstall system files" once to get the original SDL back.
        // 패드가 죽은 근본 원인: APK의 libSDL2는 안드로이드용(자바 VM/SDLActivity
        // 필요)임. 이걸 imagefs 자체 SDL 위에 덮어써서 모든 Wine 프로세스에서
        // SDL_Init이 실패했고("did you include SDL_main.h"), 억지로 통과시키면 Wine이
        // 죽었음. GameNative와 WinNative는 imagefs SDL을 절대 바꾸지 않고 그대로 씀.
        // WinNative는 libSDL2-2.0.so.0 -> libSDL2-2.0.so 이름 링크만 추가함. 여기도
        // 똑같이 함. 예전 코드가 이미 덮어쓴 기기는 "시스템 파일 재설치"를 한 번
        // 해야 원래 SDL이 돌아옴
        File sdlSo = new File(destDir, "libSDL2-2.0.so");
        File sdlSoLink = new File(destDir, "libSDL2-2.0.so.0");
        if (sdlSo.exists() && !sdlSoLink.exists()) {
            FileUtils.symlink(sdlSo.getName(), sdlSoLink.getPath());
            Log.d("ImageFsInstaller", "Created SDL link: " + sdlSoLink.getPath());
        }
        if (sdlSrc.exists() && sdlSo.exists() && sdlSo.length() == sdlSrc.length()) {
            Log.e("ImageFsInstaller", "imagefs libSDL2 is the APK's Android build (overwritten by an older version) - "
                    + "reinstall system files to restore controller support");
        }
    }

    private static void chmod(File f) { if (f.exists()) FileUtils.chmod(f, 0755); }

}