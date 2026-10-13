package com.winlator.cmod.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.winlator.cmod.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * "Check for updates" in the About window. Runs only when the user taps it:
 * no check at start, no background check, no notification. Reads the list of
 * GitHub Releases of the EZlator repository (nothing is sent but the request
 * itself) and opens the release page in the browser when a newer one exists.
 * 정보 창의 "업데이트 확인". 사용자가 누를 때만 동작함: 시작할 때 확인하지 않고,
 * 뒤에서 확인하지 않으며, 알림도 없음. EZlator 저장소의 GitHub Releases 목록을
 * 읽고(요청 자체 외에는 아무것도 보내지 않음), 더 새 버전이 있으면 브라우저로 그
 * 릴리즈 페이지를 엶
 */
public final class AppUpdateChecker {
    private static final String TAG = "AppUpdateChecker";
    private static final String RELEASES_API = "https://api.github.com/repos/4thyaN6/EZlator/releases?per_page=20";

    private AppUpdateChecker() {}

    public static void check(Activity activity) {
        final String currentName;
        final long versionCode;
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            currentName = info.versionName;
            versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean includeBeta = ReleaseVersion.isBetaBuild(versionCode);
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            ReleaseVersion.Release newer = null;
            boolean failed = false;
            try {
                newer = ReleaseVersion.pickNewer(fetchReleases(), currentName, includeBeta);
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
                failed = true;
            }
            final ReleaseVersion.Release found = newer;
            final boolean error = failed;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (error) {
                    Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                } else if (found == null) {
                    Toast.makeText(activity, activity.getString(R.string.update_latest, ReleaseVersion.shortName(currentName)), Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.update_check)
                            .setMessage(activity.getString(R.string.update_available,
                                    ReleaseVersion.shortName(found.tag), ReleaseVersion.shortName(currentName)))
                            .setPositiveButton(R.string.update_open_page, (d, w) -> {
                                try {
                                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(found.url)));
                                } catch (Exception e) {
                                    Toast.makeText(activity, found.url, Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
        });
    }

    private static List<ReleaseVersion.Release> fetchReleases() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        // GitHub rejects API calls without a User-Agent
        // GitHub API는 User-Agent가 없으면 요청을 거부함
        c.setRequestProperty("User-Agent", "EZlator");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            List<ReleaseVersion.Release> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                list.add(new ReleaseVersion.Release(r.optString("tag_name"), r.optString("html_url"),
                        r.optBoolean("prerelease"), r.optBoolean("draft")));
            }
            return list;
        } finally {
            c.disconnect();
        }
    }
}
