package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.container.Container;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

public abstract class WineStartMenuCreator {
    // Start menu entry of the in-guest file manager and its replacement (Wine's explorer.exe)
    // 시작 메뉴의 게스트 파일 관리자 항목과 그 대체(Wine 자체 explorer.exe)
    private static final String GUEST_FILE_MANAGER = "C:/windows/wfm.exe";
    private static final String FALLBACK_FILE_MANAGER = "C:/windows/system32/explorer.exe";
    private static final String FALLBACK_FILE_MANAGER_ARGS = "C:\\";

    private static int parseShowCommand(String value) {
        if (value.equals("SW_SHOWMAXIMIZED")) {
            return MSLink.SW_SHOWMAXIMIZED;
        }
        else if (value.equals("SW_SHOWMINNOACTIVE")) {
            return MSLink.SW_SHOWMINNOACTIVE;
        }
        else return MSLink.SW_SHOWNORMAL;
    }

    private static void createMenuEntry(JSONObject item, File currentDir, boolean useGuestFileManager) throws JSONException {
        if (item.has("children")) {
            currentDir = new File(currentDir, item.getString("name"));
            currentDir.mkdirs();

            JSONArray children = item.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) createMenuEntry(children.getJSONObject(i), currentDir, useGuestFileManager);
        }
        else {
            File outputFile = new File(currentDir, item.getString("name")+".lnk");
            MSLink.Options options = new MSLink.Options();
            options.targetPath = item.getString("path");
            options.cmdArgs = item.optString("cmdArgs");
            options.iconLocation = item.optString("iconLocation", options.targetPath);
            // Without the guest file manager the entry opens explorer.exe at C:\ and keeps the wfm.exe icon
            // 게스트 파일 관리자를 안 쓰면 이 항목은 C:\에서 explorer.exe를 열고, 아이콘은 wfm.exe 것을 유지함
            if (!useGuestFileManager && options.targetPath.equalsIgnoreCase(GUEST_FILE_MANAGER)) {
                options.targetPath = FALLBACK_FILE_MANAGER;
                options.cmdArgs = FALLBACK_FILE_MANAGER_ARGS;
            }
            options.iconIndex = item.optInt("iconIndex", 0);
            if (item.has("showCommand")) options.showCommand = parseShowCommand(item.getString("showCommand"));
            MSLink.createFile(options, outputFile);
        }
    }

    private static void removeMenuEntry(JSONObject item, File currentDir) throws JSONException {
        if (item.has("children")) {
            currentDir = new File(currentDir, item.getString("name"));

            JSONArray children = item.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) removeMenuEntry(children.getJSONObject(i), currentDir);

            if (FileUtils.isEmpty(currentDir)) currentDir.delete();
        }
        else (new File(currentDir, item.getString("name")+".lnk")).delete();
    }

    private static void removeOldMenu(File containerStartMenuFile, File startMenuDir) throws JSONException {
        if (!containerStartMenuFile.isFile()) return;
        JSONArray data = new JSONArray(FileUtils.readString(containerStartMenuFile));
        for (int i = 0; i < data.length(); i++) removeMenuEntry(data.getJSONObject(i), startMenuDir);
    }

    // Original entry point: menu as written in wine_startmenu.json
    // 기존 진입점: wine_startmenu.json 그대로의 메뉴
    public static void create(Context context, Container container) {
        create(context, container, true);
    }

    // useGuestFileManager = false points "Computer" (wfm.exe) at explorer.exe instead.
    // The menu is rebuilt on every launch, so a container follows its current Wine build.
    // useGuestFileManager = false면 "Computer"(wfm.exe)를 explorer.exe로 바꿈.
    // 메뉴는 실행할 때마다 다시 만들어지므로 컨테이너는 현재 Wine 빌드를 따름
    public static void create(Context context, Container container, boolean useGuestFileManager) {
        try {
            File startMenuDir = container.getStartMenuDir();
            File containerStartMenuFile = new File(container.getRootDir(), ".startmenu");
            removeOldMenu(containerStartMenuFile, startMenuDir);

            JSONArray data = new JSONArray(FileUtils.readString(context, "wine_startmenu.json"));
            FileUtils.writeString(containerStartMenuFile, data.toString());
            for (int i = 0; i < data.length(); i++) createMenuEntry(data.getJSONObject(i), startMenuDir, useGuestFileManager);
        }
        catch (JSONException e) {}
    }
}
