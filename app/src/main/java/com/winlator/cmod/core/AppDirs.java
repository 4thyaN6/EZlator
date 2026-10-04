package com.winlator.cmod.core;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;

// The app's own folder in Download, like GameNative (Download/GameNative/...) and
// Bannerlator (Download/bannerlator/...). Logs, exported profiles, rc files,
// frontend exports, container backups, save exports and save backups all go under
// Download/.EZlator/<sub>. The leading dot hides the folder in file managers
// (Files by Google shows it only with "Show hidden files"), so Download is not
// cluttered for people who use other apps too; a .nomedia file keeps its contents
// out of the gallery. It used to be Download/EZlator (moved once, see root()) and
// before that Download/Winlator, which every other Winlator-based app on the
// device writes to as well. Files already in Download/Winlator are left where
// they are.
// 다운로드 폴더 안의 앱 전용 폴더임. GameNative(Download/GameNative/...),
// Bannerlator(Download/bannerlator/...)와 같은 방식. 로그, 내보낸 프로필, rc 파일,
// 프런트엔드 내보내기, 컨테이너 백업, 세이브 내보내기와 세이브 백업이 모두
// Download/.EZlator/<하위>에 감. 이름 앞의 점 때문에 파일 관리자에서 숨김 폴더가 됨
// (Files by Google은 "숨겨진 파일 표시"를 켜야 보임). 그래서 다른 앱도 쓰는 사람의
// 다운로드 폴더가 지저분해지지 않음. .nomedia 파일로 갤러리에도 안 잡히게 함.
// 예전에는 Download/EZlator였고(한 번 옮김, root() 참고), 그 전에는
// Download/Winlator였는데 같은 기기의 다른 Winlator 계열 앱도 모두 그곳에 씀.
// Download/Winlator에 이미 있는 파일은 그대로 둠
public final class AppDirs {
    private static final String TAG = "AppDirs";
    public static final String DOWNLOAD_DIR = ".EZlator";
    // Visible folder name used before; its contents are moved once
    // 예전에 쓰던 보이는 폴더 이름. 안의 내용을 한 번 옮김
    private static final String OLD_DOWNLOAD_DIR = "EZlator";
    private static boolean prepared = false;

    private AppDirs() {}

    // Download/.EZlator. On the first call of a process: an old Download/EZlator is
    // renamed to it (only when .EZlator does not exist yet, so nothing is merged or
    // overwritten; when the rename fails the old folder stays as it was), then the
    // folder and its .nomedia file are made
    // Download/.EZlator를 돌려줌. 프로세스에서 처음 부를 때: 예전 Download/EZlator가
    // 있으면 이 이름으로 바꿈(.EZlator가 아직 없을 때만. 그래서 합치거나 덮어쓰는
    // 일이 없음. 이름 바꾸기가 실패하면 예전 폴더는 그대로 남음). 그다음 폴더와
    // .nomedia 파일을 만듦
    public static synchronized File root() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, DOWNLOAD_DIR);
        if (!prepared) {
            prepared = true;
            File old = new File(downloads, OLD_DOWNLOAD_DIR);
            if (old.isDirectory() && !dir.exists()) {
                if (old.renameTo(dir)) Log.i(TAG, "moved " + old + " -> " + dir);
                else Log.w(TAG, "could not move " + old + ", left in place");
            }
            if (dir.isDirectory() || dir.mkdirs()) {
                File noMedia = new File(dir, ".nomedia");
                try {
                    if (!noMedia.exists()) noMedia.createNewFile();
                } catch (IOException e) {
                    Log.w(TAG, "could not create " + noMedia, e);
                }
            }
        }
        return dir;
    }

    public static File downloads(String sub) {
        return new File(root(), sub);
    }
}
