package com.winlator.cmod.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import com.winlator.cmod.R;

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE) helper, shared by the Steam screen
 * and the settings switch. Steam games are written outside the app folder
 * (debug: Download/SteamLibrary, release: /storage/emulated/0/EZlator), so the
 * download fails silently without this permission.
 * "모든 파일 접근"(MANAGE_EXTERNAL_STORAGE) 도우미임. Steam 화면과 설정 스위치가
 * 같이 씀. Steam 게임은 앱 폴더 밖(디버그: Download/SteamLibrary, 릴리즈:
 * /storage/emulated/0/EZlator)에 저장되므로 이 권한이 없으면 다운로드가 조용히
 * 실패함
 */
public final class AllFilesAccess {
    private AllFilesAccess() {}

    // Below Android 11 the permission does not exist; storage access comes from
    // the WRITE_EXTERNAL_STORAGE request in MainActivity, so it counts as granted
    // 안드로이드 11 미만에는 이 권한 자체가 없음. 저장소 접근은 MainActivity의
    // WRITE_EXTERNAL_STORAGE 요청으로 처리되므로 허용된 것으로 봄
    public static boolean isGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    // Opens the system screen for this app. The app cannot flip the permission
    // itself in either direction, so both "on" and "off" go through this screen.
    // Falls back to the general list if a device lacks the per-app screen.
    // 이 앱의 시스템 권한 화면을 엶. 켜기든 끄기든 앱이 직접 바꿀 수 없으므로
    // 둘 다 이 화면을 거침. 앱별 화면이 없는 기기는 전체 목록 화면으로 대체함
    public static void openSystemScreen(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            context.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // "Turn on" notice: Continue opens the system screen, Back runs onBack.
    // Not cancelable by tapping outside, so the user must pick one of the two.
    // "켜기" 안내창임: 계속하기는 시스템 화면을 열고, 뒤로가기는 onBack을 실행함.
    // 바깥을 눌러 닫을 수 없게 해서 둘 중 하나를 반드시 고르게 함
    public static AlertDialog showEnableDialog(Activity activity, Runnable onBack) {
        return new AlertDialog.Builder(activity)
                .setTitle(R.string.all_files_access_title)
                .setMessage(R.string.all_files_access_enable_message)
                .setCancelable(false)
                .setPositiveButton(R.string.all_files_access_continue, (d, w) -> openSystemScreen(activity))
                .setNegativeButton(R.string.all_files_access_back, (d, w) -> {
                    if (onBack != null) onBack.run();
                })
                .show();
    }

    // "Turn off" notice: explains what stops working, then Continue opens the
    // system screen where the user switches it off. Back runs onBack.
    // "끄기" 안내창임: 꺼지면 안 되는 기능을 알려준 뒤, 계속하기는 사용자가 직접
    // 끌 시스템 화면을 엶. 뒤로가기는 onBack을 실행함
    public static AlertDialog showDisableDialog(Activity activity, Runnable onBack) {
        return new AlertDialog.Builder(activity)
                .setTitle(R.string.all_files_access_title)
                .setMessage(R.string.all_files_access_disable_message)
                .setCancelable(false)
                .setPositiveButton(R.string.all_files_access_continue, (d, w) -> openSystemScreen(activity))
                .setNegativeButton(R.string.all_files_access_back, (d, w) -> {
                    if (onBack != null) onBack.run();
                })
                .show();
    }
}
