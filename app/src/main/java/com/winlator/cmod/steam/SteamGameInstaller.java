package com.winlator.cmod.steam;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StorageDrives;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;

/**
 * Phase 3: download an owned Steam game into the EZlator Steam library, write
 * the Steam install record (appmanifest_<appid>.acf) and add a Main-screen
 * shortcut that launches through the SteamLite agent.
 * 3단계: 보유한 Steam 게임을 EZlator Steam 라이브러리로 받고, Steam 설치 기록
 * (appmanifest_<appid>.acf)을 쓴 뒤, SteamLite 에이전트로 실행되는 메인 화면
 * 바로가기를 만듦
 *
 * Library layout (Steam's own): <EZlator>/SteamLibrary/steamapps/{common/<dir>, appmanifest_*.acf}
 * Each container sees it through links in its C:\Program Files (x86)\Steam\steamapps,
 * so Steam treats the game as installed in its default library (see linkLibraryInto()).
 * 라이브러리 구조(Steam 규격): <EZlator>/SteamLibrary/steamapps/{common/<폴더>, appmanifest_*.acf}
 * 각 컨테이너는 자기 C:\Program Files (x86)\Steam\steamapps의 링크로 이걸 보므로,
 * Steam은 게임이 기본 라이브러리에 설치된 것으로 인식함(linkLibraryInto() 참고)
 */
public final class SteamGameInstaller {
    private static final String TAG = "SteamGameInstaller";
    // All download diagnostics use this tag / 다운로드 진단 로그는 전부 이 태그를 씀
    public static final String DLTAG = "SteamDownload";
    public static final String CONTAINER_STEAM_COMMON_WIN = SteamLiteManager.STEAM_DIR_WIN + "\\steamapps\\common";

    public interface Progress {
        void onStatus(String text);
        void onPercent(int percent);
        // Game folder being written ("Stop" deletes it)
        // 기록 중인 게임 폴더임("정지"가 이 폴더를 지움)
        default void onInstallDir(File dir) {}
    }

    private SteamGameInstaller() {}

    /** Release: <internal EZlator>/SteamLibrary. Debug: Download/SteamLibrary (no EZlator folder in debug).
     *  릴리즈: <내장 EZlator>/SteamLibrary. 디버그: Download/SteamLibrary(디버그엔 EZlator 폴더를 안 만듦) */
    public static File getLibraryDir(Context context) {
        File base = StorageDrives.isReleaseBuild(context) ? StorageDrives.getInternalGamesDir()
                : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(base, "SteamLibrary/steamapps");
    }

    // Setting "install Steam games on the SD card" (off = internal storage first)
    // 설정 "스팀 게임을 SD카드에 설치"(꺼짐 = 내장 메모리 우선)
    public static final String PREF_LIBRARY_SD = "steam_library_sd";

    /**
     * Second library on the SD card: <card EZlator>/SteamLibrary/steamapps
     * (E:). null without a card, and in debug builds (no EZlator folders there).
     * SD카드의 두 번째 라이브러리: <카드 EZlator>/SteamLibrary/steamapps(E:).
     * 카드가 없으면 null, 디버그 빌드에서도 null(EZlator 폴더를 만들지 않음)
     */
    public static File getSdLibraryDir(Context context) {
        File sd = StorageDrives.getSdGamesDir(context);
        return sd != null ? new File(sd, "SteamLibrary/steamapps") : null;
    }

    /** Every library: internal first, then the SD card's / 모든 라이브러리: 내장 먼저, 다음 SD카드 */
    public static List<File> getLibraryDirs(Context context) {
        List<File> list = new ArrayList<>();
        list.add(getLibraryDir(context));
        File sd = getSdLibraryDir(context);
        if (sd != null) list.add(sd);
        return list;
    }

    /** Library holding this game (its appmanifest), or null / 이 게임(appmanifest)이 있는 라이브러리, 없으면 null */
    public static File libraryOf(Context context, int appId) {
        for (File lib : getLibraryDirs(context)) {
            if (new File(lib, "appmanifest_" + appId + ".acf").isFile()) return lib;
        }
        return null;
    }

    /** The game's appmanifest wherever it is (internal path when not installed)
     *  게임의 appmanifest. 어느 라이브러리에 있든 찾음(설치 안 됐으면 내장 경로) */
    public static File acfOf(Context context, int appId) {
        File lib = libraryOf(context, appId);
        return new File(lib != null ? lib : getLibraryDir(context), "appmanifest_" + appId + ".acf");
    }

    // Library for a new download: internal storage, or the SD card when the user
    // chose it in Settings (see SteamLibraryPicker). Room is checked before the
    // download starts (SteamSpaceCheck), not here
    // 새 다운로드를 넣을 라이브러리: 내장 메모리, 또는 사용자가 설정에서 고르면 SD카드
    // (SteamLibraryPicker 참고). 공간은 여기가 아니라 다운로드 시작 전에 확인함
    // (SteamSpaceCheck)
    public static File chooseLibrary(Context context) {
        File sd = getSdLibraryDir(context);
        boolean sdChosen = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_LIBRARY_SD, false);
        return SteamLibraryPicker.preferred(sdChosen, sd != null) == SteamLibraryPicker.SD ? sd : getLibraryDir(context);
    }

    // The other library (internal <-> SD card), or null without a card
    // 다른 쪽 라이브러리(내장 <-> SD카드). 카드가 없으면 null
    public static File otherLibrary(Context context, File library) {
        File internal = getLibraryDir(context);
        File sd = getSdLibraryDir(context);
        if (sd == null) return null;
        return samePath(library, internal) ? sd : internal;
    }

    public static boolean isSdLibrary(Context context, File library) {
        File sd = getSdLibraryDir(context);
        return sd != null && samePath(library, sd);
    }

    private static boolean samePath(File a, File b) {
        return a != null && b != null && a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    // Library holding an unfinished download of this game (its folder without
    // an appmanifest), so a resumed download keeps going in the same place
    // 이 게임의 끝나지 않은 다운로드(appmanifest 없이 폴더만 있음)가 있는 라이브러리.
    // 이어 받기가 같은 곳에서 계속되게 함
    static File partialLibrary(Context context, String installDir) {
        if (installDir == null || installDir.isEmpty()) return null;
        for (File lib : getLibraryDirs(context)) {
            if (new File(lib, "common/" + installDir).isDirectory()) return lib;
        }
        return null;
    }

    public static long usableSpace(File dir) {
        return usable(dir);
    }

    // Free space of the nearest existing folder / 가장 가까운 기존 폴더의 남은 공간
    private static long usable(File dir) {
        File d = dir;
        while (d != null && !d.exists()) d = d.getParentFile();
        return d != null ? d.getUsableSpace() : 0;
    }

    /**
     * Blocking: download + appmanifest + shortcut. Returns the shortcut name.
     * 블로킹: 다운로드 + appmanifest + 바로가기. 바로가기 이름을 돌려줌
     */
    // Thrown when the download is stopped or paused from the UI
    // 화면에서 다운로드를 정지하거나 일시정지하면 던져짐
    public static final class CancelledException extends Exception {
        public CancelledException() { super("cancelled"); }
    }

    public static String install(Context context, SteamSession session, int appId, Progress progress) throws Exception {
        return install(context, session, appId, progress, new java.util.concurrent.atomic.AtomicBoolean(false));
    }

    // cancel=true ends the download at the next check (about once a second).
    // Files already written stay, so running install() again resumes: the
    // downloader re-checks existing files and fetches only what is missing
    // cancel=true면 다음 확인 때(약 1초마다) 다운로드를 끝냄. 이미 쓴 파일은 남으므로
    // install()을 다시 부르면 이어 받음: 다운로더가 기존 파일을 다시 확인하고 빠진
    // 것만 받음
    public static String install(Context context, SteamSession session, int appId, Progress progress,
                                 java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
        return install(context, session, appId, progress, cancel, null);
    }

    // requestedLibrary: the library picked before the download (SteamSpaceCheck);
    // null = the Settings choice
    // requestedLibrary: 다운로드 전에 고른 라이브러리(SteamSpaceCheck). null = 설정의 선택
    public static String install(Context context, SteamSession session, int appId, Progress progress,
                                 java.util.concurrent.atomic.AtomicBoolean cancel, File requestedLibrary) throws Exception {
        long steamId = new SteamAccountStore(context).getSteamId64();
        SteamSession.AppDetails app = session.getAppDetails(appId);
        if (app.installDir.isEmpty()) throw new IllegalStateException("App has no install folder");
        if (app.exePath.isEmpty()) throw new IllegalStateException("No Windows executable in app info");

        // An installed game (update) or an unfinished download stays in its
        // library; a new one goes to the library picked before the download
        // 설치된 게임(업데이트)이나 끝나지 않은 다운로드는 원래 라이브러리에 남음. 새 게임은
        // 다운로드 전에 고른 라이브러리로 감
        File library = libraryOf(context, appId);
        if (library == null) library = partialLibrary(context, app.installDir);
        if (library == null) library = requestedLibrary != null ? requestedLibrary : chooseLibrary(context);
        Log.i(DLTAG, "library for app " + appId + ": " + library.getAbsolutePath());
        File common = new File(library, "common");
        common.mkdirs();
        Log.i(DLTAG, "app=" + appId + " name=" + app.name + " installdir=" + app.installDir
                + " exe=" + app.exePath + " buildid=" + app.buildId + " depots=" + app.depotIds
                + " -> " + common.getAbsolutePath() + " (exists=" + common.isDirectory() + ")");
        progress.onStatus(app.name);
        progress.onInstallDir(new File(common, app.installDir));

        // Diagnostics: the downloader logs through JavaSteam's LogManager, which
        // prints nowhere on Android by default - mirrored to logcat "SteamDownload"
        // 진단용: 다운로더는 JavaSteam의 LogManager로 로그를 남기는데, 안드로이드에선
        // 기본적으로 어디에도 안 찍힘 - 로그캣 "SteamDownload" 태그로 옮김
        in.dragonbra.javasteam.util.log.LogListener logBridge = new in.dragonbra.javasteam.util.log.LogListener() {
            @Override public void onLog(Class<?> clazz, String message, Throwable t) {
                Log.i(DLTAG, clazz.getSimpleName() + ": " + message, t);
            }
            @Override public void onError(Class<?> clazz, String message, Throwable t) {
                Log.e(DLTAG, clazz.getSimpleName() + ": " + message, t);
            }
        };
        in.dragonbra.javasteam.util.log.LogManager.addListener(logBridge);
        // Full Bouncy Castle only for the download (manifest SHA-1), see SteamCrypto
        // 완전판 Bouncy Castle은 다운로드(매니페스트 SHA-1) 구간에만 씀. SteamCrypto 참고
        SteamCrypto.ensure();

        // androidEmulation=true -> Windows depots; debug=true so LogManager gets details
        // androidEmulation=true -> Windows용 depot. debug=true라야 LogManager에 상세 로그가 옴
        // debug=true (verbose downloader log) only in debug builds / 상세 로그는 디버그 빌드만
        boolean verbose = !StorageDrives.isReleaseBuild(context);
        DepotDownloader dl = new DepotDownloader(session.getClient(), session.getLicenses(),
                verbose, false, 8, 8, 4, true);
        // The 7th argument (file writers) 1 -> 4. Games with
        // thousands of small files (reported: BlazBlue Entropy Effect) were
        // written one file at a time, so the download ran in bursts with gaps.
        // Order checked against JavaSteam 1.8.0: ..., maxDownloads,
        // maxDecompress, maxFileWrites, androidEmulation - recheck when the
        // library is updated
        // 7번째 인자(파일 쓰기 동시 수)를 1 -> 4로 바꿈. 작은 파일이 수천 개인
        // 게임(보고: BlazBlue Entropy Effect)은 파일을 하나씩 써서 다운로드가 끊겼다
        // 이어졌다 했음. JavaSteam 1.8.0 기준 순서 확인함: ..., maxDownloads,
        // maxDecompress, maxFileWrites, androidEmulation - 라이브러리를 올리면 다시 확인할 것
        final Throwable[] failure = {null};
        // Download speed log: every 10 s one line with the data received in that
        // time (compressed, as sent over the network), the overall percent and how
        // many files were prepared. Bytes are summed per depot because the library
        // reports a running total for each depot
        // 다운로드 속도 로그: 10초마다 한 줄로, 그동안 받은 데이터(네트워크로 오는
        // 압축 크기), 전체 퍼센트, 준비한 파일 수를 남김. 라이브러리가 depot별 누적값을
        // 알려 주므로 depot마다 따로 더함
        final java.util.Map<Integer, Long> depotBytes = new java.util.concurrent.ConcurrentHashMap<>();
        final long[] speedWindow = {System.currentTimeMillis(), 0L};
        final java.util.concurrent.atomic.AtomicInteger allocated = new java.util.concurrent.atomic.AtomicInteger();
        dl.addListener(new IDownloadListener() {
            @Override public void onItemAdded(DownloadItem item) {}
            @Override public void onDownloadStarted(DownloadItem item) {}
            @Override public void onDownloadCompleted(DownloadItem item) {}
            @Override public void onDownloadFailed(DownloadItem item, Throwable error) {
                failure[0] = error;
                Log.e(DLTAG, "download failed", error);
            }
            @Override public void onStatusUpdate(String message) {
                // "Allocating file" comes once per file (tens of thousands for some
                // games), so only every 1000th is logged; the screen still gets each one
                // "Allocating file"은 파일마다 옴(게임에 따라 수만 번). 그래서 1000번째마다만
                // 로그에 남김. 화면에는 매번 넘김
                if (message != null && message.startsWith("Allocating file")) {
                    int n = allocated.incrementAndGet();
                    if (n % 1000 == 1) Log.i(DLTAG, "status: " + message + " (files prepared: " + n + ")");
                } else {
                    Log.i(DLTAG, "status: " + message);
                }
                progress.onStatus(message);
            }
            @Override public void onFileCompleted(int depotId, String fileName, float pct) {}
            @Override public void onChunkCompleted(int depotId, float pct, long compressed, long uncompressed) {
                int percent = (int) (pct * 100);
                depotBytes.put(depotId, compressed);
                long now = System.currentTimeMillis();
                synchronized (speedWindow) {
                    long elapsed = now - speedWindow[0];
                    if (elapsed >= 10000) {
                        long total = 0;
                        for (long b : depotBytes.values()) total += b;
                        long received = total - speedWindow[1];
                        Log.i(DLTAG, String.format(java.util.Locale.ROOT,
                                "speed: %.2f MB/s over %ds, depot %d at %d%%, received %d MB total, files prepared %d",
                                received / 1048576.0 / (elapsed / 1000.0), elapsed / 1000,
                                depotId, percent, total / 1048576, allocated.get()));
                        speedWindow[0] = now;
                        speedWindow[1] = total;
                    }
                }
                progress.onPercent(percent);
            }
            @Override public void onDepotCompleted(int depotId, long compressed, long uncompressed) {}
        });
        try {
            // Installs into common/<installdir> / common/<installdir> 아래에 설치됨
            // BUG FIX: os="windows" is passed explicitly. The downloader's own OS
            // detection (Util.getSteamOS) checks IS_OS_LINUX BEFORE its Android
            // branch, and Android reports os.name "Linux" - so androidEmulation was
            // never reached and only LINUX depots were fetched (logcat 2026-09-27:
            // 1 depot, no TeamfightManager2.exe -> Steam "missing executable").
            // 버그 수정: os="windows"를 명시해서 넘김. 다운로더 자체 OS 판별
            // (Util.getSteamOS)이 안드로이드 분기보다 IS_OS_LINUX를 먼저 보는데,
            // 안드로이드는 os.name이 "Linux"라서 androidEmulation 분기에 도달하지 못했고
            // 리눅스용 depot만 받아졌음(로그캣 2026-09-27: depot 1개,
            // TeamfightManager2.exe 없음 -> Steam "missing executable")
            dl.add(new AppItem(appId, true, common.getAbsolutePath(), null, null, false, "windows"));
            dl.finishAdding();
            // BUG FIX: a failed item was reported through onDownloadFailed() but the
            // completion future never finished, so the screen sat on the last
            // status forever. Now the wait also ends as soon as a failure arrives.
            // 버그 수정: 실패한 항목은 onDownloadFailed()로 알려지는데 완료 future가
            // 끝나지 않아서, 화면이 마지막 상태에서 영원히 멈춰 있었음. 이제 실패가
            // 오면 기다림도 바로 끝남
            java.util.concurrent.CompletableFuture<Void> done = dl.getCompletion();
            while (!done.isDone() && failure[0] == null && !cancel.get()) {
                try { done.get(1, java.util.concurrent.TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException ignored) {}
            }
            // BUG FIX: isDone() is also true for a future that ended with an
            // exception or was cancelled. If that happened right after a get()
            // timeout, the loop ended without the error and the download was
            // treated as a success. The final state is read here once more.
            // 버그 수정: isDone()은 예외로 끝났거나 취소된 future에도 true임. get()
            // 시간 초과 직후에 그렇게 끝나면 오류 없이 반복문을 나와서 성공으로 처리됐음.
            // 여기서 마지막 상태를 한 번 더 읽음
            if (failure[0] == null && !cancel.get() && done.isDone()) {
                try {
                    done.getNow(null);
                } catch (java.util.concurrent.CancellationException | java.util.concurrent.CompletionException e) {
                    failure[0] = e.getCause() != null ? e.getCause() : e;
                    Log.e(DLTAG, "download future ended abnormally", failure[0]);
                }
            }
            Log.i(DLTAG, failure[0] == null ? "download finished" : "download stopped by failure");
        } finally {
            dl.close();
            SteamCrypto.restore();
            in.dragonbra.javasteam.util.log.LogManager.removeListener(logBridge);
        }
        if (cancel.get()) throw new CancelledException();
        if (failure[0] != null) throw new IllegalStateException(failure[0].getMessage(), failure[0]);

        // Verification before the install record is written. Reported: a
        // download ended "complete" while almost every file was only its size
        // filled with zeros (NieR:Automata, OS Error 193 on launch). Every
        // Windows program file must start with "MZ", so .exe / .dll files are
        // checked; a broken download fails here instead of being "installed",
        // and the downloader's depot record is cleared so the next download
        // re-checks every file instead of skipping them as already installed
        // 설치 기록을 쓰기 전 검증임. 보고: 거의 모든 파일이 크기만 잡히고 0으로
        // 채워졌는데 다운로드가 "완료"로 끝났음(NieR:Automata, 실행 시 OS Error 193).
        // Windows 실행 파일은 반드시 "MZ"로 시작하므로 .exe / .dll을 확인함. 손상된
        // 다운로드는 "설치됨"이 되지 않고 여기서 실패하며, 다운로더의 depot 기록을
        // 지워서 다음 다운로드가 "이미 설치됨"으로 건너뛰지 않고 모든 파일을 다시 확인하게 함
        progress.onStatus(context.getString(R.string.steam_manage_verifying));
        File gameDir = new File(common, app.installDir);
        List<String> broken = verifyInstall(gameDir, app.exePath);
        if (!broken.isEmpty()) {
            Log.e(DLTAG, "verification failed: " + broken.size() + " broken file(s), first: "
                    + broken.subList(0, Math.min(20, broken.size())));
            forgetDepotRecords(library, gameDir, app.depotIds);
            throw new IllegalStateException(context.getString(R.string.steam_manage_verify_failed, broken.size()));
        }
        Log.i(DLTAG, "verification passed: " + gameDir.getAbsolutePath());

        writeAppManifest(library, app, steamId);
        writeSharedAppManifests(session, library, app, steamId);
        // CHANGED: was firstContainer(), which took whatever container came first
        // (x86_64 included, where Steam games had sound problems). Steam games now
        // always go to an ARM64EC container, and one is created if none exists.
        // 변경: 예전엔 firstContainer()로 맨 앞 컨테이너를 그냥 썼음(x86_64 포함,
        // 거기서 Steam 게임 사운드 문제가 있었음). 이제 Steam 게임은 항상 ARM64EC
        // 컨테이너로 가고, 없으면 새로 만듦
        Container container = arm64ecContainer(context, progress);
        if (container == null) throw new IllegalStateException(context.getString(R.string.steam_no_arm64ec_wine));
        linkLibraryInto(context, container);
        return createShortcut(context, container, app);
    }

    /**
     * Steam's install record. StateFlags 4 = fully installed; InstalledDepots from
     * the downloader's own record (common/.DepotDownloader/depot.config).
     * Steam 설치 기록임. StateFlags 4 = 설치 완료. InstalledDepots는 다운로더 자체
     * 기록(common/.DepotDownloader/depot.config)에서 가져옴
     */
    static void writeAppManifest(File library, SteamSession.AppDetails app, long steamId) {
        StringBuilder depots = new StringBuilder();
        try {
            File cfg = new File(library, "common/.DepotDownloader/depot.config");
            if (cfg.isFile()) {
                JSONObject ids = new JSONObject(new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8))
                        .optJSONObject("installedManifestIDs");
                if (ids != null) {
                    for (Iterator<String> it = ids.keys(); it.hasNext(); ) {
                        String depot = it.next();
                        int depotId = Integer.parseInt(depot);
                        // Shared depots belong to their owner app's manifest (SharedDepots)
                        // 공유 depot은 소유 앱의 manifest에 속함(SharedDepots로 표시)
                        if (!app.depotIds.contains(depotId) || app.sharedDepots.containsKey(depotId)) continue;
                        depots.append("\t\t\"").append(depot).append("\"\n\t\t{\n\t\t\t\"manifest\"\t\t\"")
                                .append(ids.get(depot)).append("\"\n\t\t\t\"size\"\t\t\"0\"\n\t\t}\n");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "depot.config read failed", e);
        }
        String acf = "\"AppState\"\n{\n"
                + "\t\"appid\"\t\t\"" + app.appId + "\"\n"
                + "\t\"Universe\"\t\t\"1\"\n"
                + "\t\"name\"\t\t\"" + app.name.replace("\"", "") + "\"\n"
                + "\t\"StateFlags\"\t\t\"4\"\n"
                + "\t\"installdir\"\t\t\"" + app.installDir + "\"\n"
                + "\t\"LastUpdated\"\t\t\"" + (System.currentTimeMillis() / 1000) + "\"\n"
                + "\t\"SizeOnDisk\"\t\t\"0\"\n"
                + "\t\"buildid\"\t\t\"" + app.buildId + "\"\n"
                + "\t\"LastOwner\"\t\t\"" + steamId + "\"\n"
                + "\t\"AutoUpdateBehavior\"\t\t\"1\"\n"
                + "\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"\n"
                + "\t\"ScheduledAutoUpdate\"\t\t\"0\"\n"
                + "\t\"InstalledDepots\"\n\t{\n" + depots + "\t}\n"
                + sharedBlock(app)
                + "}\n";
        FileUtils.writeString(new File(library, "appmanifest_" + app.appId + ".acf"), acf);
    }

    /**
     * Registers the shared apps a game borrows depots from (almost always
     * 228980 "Steamworks Common Redistributables").
     * Logcat 2026-09-27 21:24: Steam marked 228980 "Update Required, Update Queued",
     * set SteamGameId/SteamClientLaunch for 3009300 and then exited with code 3
     * without ever creating the game process - the launch was blocked on that
     * uninstalled shared app. An appmanifest for it with the current public build,
     * StateFlags 4 and the depots the game uses tells Steam it is installed.
     * 게임이 depot을 빌려 쓰는 공유 앱(거의 항상 228980 "Steamworks Common
     * Redistributables")을 등록함.
     * 로그캣 2026-09-27 21:24: Steam이 228980을 "Update Required, Update Queued"로
     * 표시하고, 3009300용 SteamGameId/SteamClientLaunch를 설정한 뒤 게임 프로세스를
     * 만들지 않고 코드 3으로 종료했음 - 설치 안 된 공유 앱 때문에 실행이 막힌 것임.
     * 현재 public 빌드, StateFlags 4, 게임이 쓰는 depot을 적은 appmanifest를 만들어
     * Steam이 설치된 것으로 보게 함
     */
    static void writeSharedAppManifests(SteamSession session, File library, SteamSession.AppDetails game, long steamId) {
        java.util.Set<Integer> owners = new java.util.HashSet<>(game.sharedDepots.values());
        for (int owner : owners) {
            try {
                SteamSession.AppDetails shared = session.getAppDetails(owner);
                if (shared.installDir.isEmpty()) shared.installDir = "Steamworks Shared";
                // Only the depots this game actually uses / 이 게임이 실제로 쓰는 depot만
                shared.depotIds = new ArrayList<>();
                for (java.util.Map.Entry<Integer, Integer> e : game.sharedDepots.entrySet())
                    if (e.getValue() == owner) shared.depotIds.add(e.getKey());
                new File(library, "common/" + shared.installDir).mkdirs();
                writeAppManifest(library, shared, steamId);
                Log.i(DLTAG, "shared app " + owner + " registered (" + shared.name + ", build " + shared.buildId
                        + ", depots " + shared.depotIds + ")");
            } catch (Exception e) {
                Log.w(DLTAG, "shared app " + owner + " registration failed", e);
            }
        }
    }

    // "SharedDepots" { "<depot>" "<owner app>" } - what Steam itself writes for
    // depots borrowed from another app
    // "SharedDepots" { "<depot>" "<소유 앱>" } - 다른 앱에서 빌린 depot에 Steam이
    // 직접 쓰는 형식임
    private static String sharedBlock(SteamSession.AppDetails app) {
        if (app.sharedDepots.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\t\"SharedDepots\"\n\t{\n");
        for (java.util.Map.Entry<Integer, Integer> e : app.sharedDepots.entrySet())
            sb.append("\t\t\"").append(e.getKey()).append("\"\t\t\"").append(e.getValue()).append("\"\n");
        return sb.append("\t}\n").toString();
    }

    /**
     * Makes every library game visible inside the container's own Steam folder:
     * appmanifest copied, common/<dir> linked. Safe to call on every launch.
     * 라이브러리의 모든 게임을 컨테이너 자체 Steam 폴더에서 보이게 함: appmanifest는
     * 복사, common/<폴더>는 링크. 실행할 때마다 불러도 안전함
     */
    public static void linkLibraryInto(Context context, File driveC) {
        // Both libraries (internal, SD card) appear as the one default library of
        // the container
        // 두 라이브러리(내장, SD카드)를 컨테이너의 기본 라이브러리 하나로 보이게 함
        for (File lib : getLibraryDirs(context)) linkOneLibrary(lib, driveC);
    }

    private static void linkOneLibrary(File lib, File driveC) {
        File[] acfs = lib.listFiles((d, n) -> n.startsWith("appmanifest_") && n.endsWith(".acf"));
        if (acfs == null) return;
        File steamApps = new File(driveC, "Program Files (x86)/Steam/steamapps");
        File containerCommon = new File(steamApps, "common");
        containerCommon.mkdirs();
        for (File acf : acfs) {
            FileUtils.copy(acf, new File(steamApps, acf.getName()));
            String dir = readInstallDir(acf);
            if (dir == null) continue;
            File target = new File(lib, "common/" + dir);
            File link = new File(containerCommon, dir);
            if (!target.isDirectory()) continue;
            // A link left over from the other library (game moved or downloaded
            // again there) is pointed at the folder that exists now
            // 다른 라이브러리를 가리키던 링크(게임을 옮겼거나 그쪽에 다시 받음)는 지금
            // 있는 폴더를 가리키게 고침
            if (Files.isSymbolicLink(link.toPath()) && !link.exists()) link.delete();
            if (!link.exists()) FileUtils.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        }
    }

    public static void linkLibraryInto(Context context, Container container) {
        linkLibraryInto(context, new File(container.getRootDir(), ".wine/drive_c"));
    }

    private static String readInstallDir(File acf) {
        return readAcfValue(acf, "installdir");
    }

    /** InstalledDepots -> "depot:manifest:size,..." (agent WN_STEAM_DEPOTS format)
     *  InstalledDepots -> "depot:manifest:size,..."(에이전트 WN_STEAM_DEPOTS 형식) */
    public static String readAcfDepots(File acf) {
        return readAcfBlock(acf, "InstalledDepots", true);
    }

    /** SharedDepots -> "depot:app,..." (agent WN_STEAM_SHARED_DEPOTS format)
     *  SharedDepots -> "depot:app,..."(에이전트 WN_STEAM_SHARED_DEPOTS 형식) */
    public static String readAcfSharedDepots(File acf) {
        return readAcfBlock(acf, "SharedDepots", false);
    }

    // Minimal reader for the two blocks this installer writes itself
    // 이 설치기가 직접 쓰는 두 블록만 읽는 최소 파서
    private static String readAcfBlock(File acf, String block, boolean depotEntries) {
        StringBuilder out = new StringBuilder();
        try {
            if (!acf.isFile()) return "";
            boolean inBlock = false;
            String depot = null;
            for (String line : FileUtils.readLines(acf)) {
                String t = line.trim();
                if (!inBlock) {
                    if (t.equals("\"" + block + "\"")) inBlock = true;
                    continue;
                }
                if (t.equals("}") && depot == null) break;       // end of block / 블록 끝
                String[] p = t.split("\"");
                if (depotEntries) {
                    if (p.length == 2 && t.startsWith("\"")) depot = p[1];            // "12345"
                    else if (depot != null && p.length >= 4 && p[1].equals("manifest")) {
                        if (out.length() > 0) out.append(',');
                        out.append(depot).append(':').append(p[3]).append(":0");
                    } else if (t.equals("}")) depot = null;
                } else if (p.length >= 4) {                                          // "depot" "app"
                    if (out.length() > 0) out.append(',');
                    out.append(p[1]).append(':').append(p[3]);
                }
            }
        } catch (Exception ignored) {}
        return out.toString();
    }

    /** Top-level "key" "value" from an appmanifest; null if absent.
     *  appmanifest의 최상위 "키" "값"을 읽음. 없으면 null */
    public static String readAcfValue(File acf, String key) {
        try {
            if (!acf.isFile()) return null;
            for (String line : FileUtils.readLines(acf)) {
                String t = line.trim();
                if (t.startsWith("\"" + key + "\"")) {
                    String[] p = t.split("\"");
                    return p.length >= 4 ? p[3] : null;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== Verification / 검증 ====================

    /**
     * Returns the files of a finished download that cannot be right: every
     * .exe / .dll that does not start with "MZ" (Windows program header), and
     * the game's main executable when it is missing or empty. The downloader's
     * own folder (.DepotDownloader) is skipped. Reads 2 bytes per file, so it
     * takes seconds even for large games. Empty list = passed.
     * 다 받은 다운로드에서 정상일 수 없는 파일을 돌려줌: "MZ"(Windows 실행 파일 머리)로
     * 시작하지 않는 모든 .exe / .dll과, 없거나 비어 있는 게임 주 실행 파일임.
     * 다운로더 자체 폴더(.DepotDownloader)는 건너뜀. 파일마다 2바이트만 읽어서 큰
     * 게임도 몇 초면 끝남. 빈 목록 = 통과
     */
    static List<String> verifyInstall(File gameDir, String exePath) {
        List<String> broken = new ArrayList<>();
        String root = gameDir.getAbsolutePath();

        // Main executable must exist and have content
        // 주 실행 파일은 있어야 하고 내용이 있어야 함
        if (exePath != null && !exePath.isEmpty()) {
            File exe = new File(gameDir, exePath.replace('\\', '/'));
            if (!exe.isFile() || exe.length() == 0) broken.add(exePath + " (missing)");
        }

        // Walks the folder without recursion (deep trees cannot overflow the stack)
        // 재귀 없이 폴더를 돌음(깊은 폴더 구조에서도 스택이 넘치지 않음)
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(gameDir);
        while (!stack.isEmpty()) {
            File[] children = stack.pop().listFiles();
            if (children == null) continue;
            for (File f : children) {
                if (f.isDirectory()) {
                    if (!f.getName().equals(".DepotDownloader")) stack.push(f);
                    continue;
                }
                String n = f.getName().toLowerCase(java.util.Locale.ENGLISH);
                if (!n.endsWith(".exe") && !n.endsWith(".dll")) continue;
                // 0-byte files carry no header to check; the main exe is covered above
                // 0바이트 파일은 확인할 머리가 없음. 주 실행 파일은 위에서 따로 확인함
                if (f.length() == 0) continue;
                if (!startsWithMz(f)) {
                    String p = f.getAbsolutePath();
                    broken.add(p.startsWith(root) ? p.substring(root.length() + 1) : p);
                }
            }
        }
        return broken;
    }

    // True when the file starts with the two bytes 'M' 'Z'
    // 파일이 'M' 'Z' 두 바이트로 시작하면 true
    private static boolean startsWithMz(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 'M' && in.read() == 'Z';
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Removes these depots from the downloader's record of installed manifests
     * ("installedManifestIDs" in .DepotDownloader/depot.config). With the record
     * gone, the next download checks the files on disk and fetches what is wrong,
     * instead of skipping the depot as already installed. Both possible record
     * locations are cleaned (library common folder and the game folder).
     * 다운로더의 설치된 매니페스트 기록(.DepotDownloader/depot.config의
     * "installedManifestIDs")에서 이 depot들을 뺌. 기록이 없어지면 다음 다운로드가
     * "이미 설치됨"으로 건너뛰지 않고 디스크의 파일을 확인한 뒤 잘못된 것을 받음.
     * 기록이 있을 수 있는 두 곳(라이브러리 common 폴더, 게임 폴더)을 모두 정리함
     */
    static void forgetDepotRecords(File library, File gameDir, java.util.Collection<Integer> depotIds) {
        if (depotIds == null || depotIds.isEmpty()) return;
        forgetDepotRecordsIn(new File(library, "common/.DepotDownloader/depot.config"), depotIds);
        if (gameDir != null) forgetDepotRecordsIn(new File(gameDir, ".DepotDownloader/depot.config"), depotIds);
    }

    // Edits the text directly instead of re-writing it through JSONObject:
    // manifest ids are unsigned 64-bit and Android's org.json turns numbers
    // above Long.MAX_VALUE into doubles, which would corrupt the records of
    // other games in the same file
    // JSONObject로 다시 쓰지 않고 글자를 직접 고침: 매니페스트 id는 부호 없는
    // 64비트라서, 안드로이드 org.json은 Long.MAX_VALUE를 넘는 숫자를 double로 바꿈.
    // 그러면 같은 파일에 있는 다른 게임의 기록이 망가짐
    private static void forgetDepotRecordsIn(File cfg, java.util.Collection<Integer> depotIds) {
        try {
            if (!cfg.isFile()) return;
            String text = FileUtils.readString(cfg);
            if (text == null) return;
            int key = text.indexOf("\"installedManifestIDs\"");
            if (key < 0) return;
            int open = text.indexOf('{', key);
            int close = open < 0 ? -1 : text.indexOf('}', open);
            if (open < 0 || close < 0) return;
            String body = text.substring(open + 1, close);
            String edited = body;
            for (int depot : depotIds) {
                // "<depot>": <number or "string">, (trailing comma optional)
                // "<depot>": <숫자 또는 "문자열">, (뒤 쉼표는 있을 수도 없을 수도 있음)
                edited = edited.replaceAll("\"" + depot + "\"\\s*:\\s*(\"[^\"]*\"|[^,}\\s]+)\\s*,?", "");
            }
            if (edited.equals(body)) return;
            // A removed last entry leaves the previous entry's comma behind
            // 마지막 항목을 지우면 앞 항목의 쉼표가 남으므로 정리함
            edited = edited.replaceAll(",\\s*$", "");
            FileUtils.writeString(cfg, text.substring(0, open + 1) + edited + text.substring(close));
            Log.i(DLTAG, "depot records cleared " + depotIds + " in " + cfg.getAbsolutePath());
        } catch (Exception e) {
            Log.w(DLTAG, "depot record cleanup failed: " + cfg.getAbsolutePath(), e);
        }
    }

    // ==================== Installed games, move / 설치된 게임, 옮기기 ====================

    /** An installed game of one library / 라이브러리 하나의 설치된 게임 */
    public static final class InstalledGame {
        public final int appId;
        public final String name;
        public final File dir;
        public final long bytes;

        InstalledGame(int appId, String name, File dir, long bytes) {
            this.appId = appId;
            this.name = name;
            this.dir = dir;
            this.bytes = bytes;
        }
    }

    /**
     * Installed games of a library with their folder sizes, biggest first.
     * Reads every file size, so call it off the UI thread.
     * 라이브러리의 설치된 게임과 폴더 크기, 큰 것부터. 파일 크기를 모두 읽으므로 UI
     * 스레드 밖에서 부를 것
     */
    public static List<InstalledGame> installedGames(File library) {
        List<InstalledGame> list = new ArrayList<>();
        File[] acfs = library.listFiles((d, n) -> n.startsWith("appmanifest_") && n.endsWith(".acf"));
        if (acfs == null) return list;
        for (File acf : acfs) {
            String dir = readInstallDir(acf);
            if (dir == null || dir.isEmpty() || dir.contains("..")) continue;
            File gameDir = new File(library, "common/" + dir);
            if (!gameDir.isDirectory()) continue;
            int appId;
            try {
                appId = Integer.parseInt(acf.getName().substring(12, acf.getName().length() - 4));
            } catch (NumberFormatException e) {
                continue;
            }
            String name = readAcfValue(acf, "name");
            list.add(new InstalledGame(appId, name != null ? name : dir, gameDir, folderSize(gameDir)));
        }
        list.sort((x, y) -> Long.compare(y.bytes, x.bytes));
        return list;
    }

    public static long folderSize(File dir) {
        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            if (Files.isSymbolicLink(c.toPath())) continue;
            total += c.isDirectory() ? folderSize(c) : c.length();
        }
        return total;
    }

    /** Progress of a move: bytes copied so far of the total / 옮기기 진행: 전체 중 복사한 바이트 */
    public interface MoveProgress {
        void onCopied(long done, long total);
    }

    /**
     * Moves an installed game to the other library (internal <-> SD card), both
     * inside the EZlator folders. The two are different storages, so the folder
     * is copied, checked (file count and size), and only then the install record
     * moves and the old folder is deleted; on any failure the copy is removed and
     * the game stays where it was. Shortcuts keep working: they point at
     * C:\Program Files (x86)\Steam\steamapps\common\<folder>, and the
     * container links are pointed at the new place at the next launch
     * (linkLibraryInto). The downloader's depot record of the old library is
     * cleared, so the next update checks the files once. Blocking.
     * 설치된 게임을 다른 라이브러리로 옮김(내장 <-> SD카드, 둘 다 EZlator 폴더 안).
     * 서로 다른 저장소라 폴더를 복사하고 확인(파일 수와 크기)한 뒤에야 설치 기록을
     * 옮기고 원래 폴더를 지움. 중간에 실패하면 사본을 지우고 게임은 원래 자리에 남음.
     * 바로가기는 그대로 동작함: C:\Program Files (x86)\Steam\steamapps\common\<폴더>를
     * 가리키고, 컨테이너 링크는 다음 실행 때 새 위치로 고쳐짐(linkLibraryInto). 원래
     * 라이브러리의 다운로더 depot 기록은 지우므로 다음 업데이트 때 파일을 한 번 확인함.
     * 블로킹임
     */
    public static void moveGame(Context context, int appId, File toLibrary, MoveProgress progress) throws Exception {
        File from = libraryOf(context, appId);
        if (from == null) throw new IllegalStateException("not installed: " + appId);
        if (from.getAbsolutePath().equals(toLibrary.getAbsolutePath())) return;
        if (SteamDownloadState.isGameRunning(context)) throw new IllegalStateException(context.getString(R.string.steam_move_game_running));
        File acf = new File(from, "appmanifest_" + appId + ".acf");
        String dir = readInstallDir(acf);
        if (dir == null || dir.isEmpty() || dir.contains("..")) throw new IllegalStateException("bad install folder");
        File src = new File(from, "common/" + dir);
        File dst = new File(toLibrary, "common/" + dir);
        if (dst.exists()) throw new IllegalStateException(context.getString(R.string.steam_move_exists, dst.getAbsolutePath()));
        long total = folderSize(src);
        File parent = new File(toLibrary, "common");
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("cannot create " + parent);
        if (SteamLibraryPicker.shortfall(usable(parent), total) > 0) {
            throw new IllegalStateException(context.getString(R.string.steam_move_no_room));
        }
        Log.i(DLTAG, "move app=" + appId + " " + src + " -> " + dst + " (" + total + " bytes)");
        long[] done = {0};
        long[] count = {0};
        try {
            copyTree(src, dst, done, count, total, progress);
            // Check: same number of files and bytes / 확인: 파일 수와 바이트가 같음
            long[] checkCount = {0};
            long checkBytes = countTree(dst, checkCount);
            long[] srcCount = {0};
            long srcBytes = countTree(src, srcCount);
            if (checkBytes != srcBytes || checkCount[0] != srcCount[0]) {
                throw new IllegalStateException("copy check failed: " + checkCount[0] + "/" + srcCount[0] + " files");
            }
            FileUtils.copy(acf, new File(toLibrary, acf.getName()));
            if (!new File(toLibrary, acf.getName()).isFile()) throw new IllegalStateException("install record not copied");
        } catch (Exception e) {
            FileUtils.delete(dst);
            throw e;
        }
        // From here the new place is complete: the old one goes
        // 여기부터 새 자리가 완성됨: 원래 자리를 지움
        List<Integer> depots = new ArrayList<>();
        for (String entry : readAcfDepots(acf).split(",")) {
            String[] p = entry.split(":");
            try { if (p.length > 0 && !p[0].isEmpty()) depots.add(Integer.parseInt(p[0])); }
            catch (NumberFormatException ignored) {}
        }
        forgetDepotRecords(from, null, depots);
        if (!acf.delete()) Log.w(DLTAG, "could not delete old record " + acf);
        if (!FileUtils.delete(src)) Log.w(DLTAG, "old folder not fully deleted: " + src);
        Log.i(DLTAG, "moved app=" + appId + ": " + count[0] + " files");
    }

    private static void copyTree(File src, File dst, long[] done, long[] count, long total, MoveProgress progress) throws java.io.IOException {
        if (Files.isSymbolicLink(src.toPath())) {
            Files.createSymbolicLink(dst.toPath(), Files.readSymbolicLink(src.toPath()));
            // Counted like countTree() does (a link is one entry)
            // countTree()와 같게 셈(링크 하나가 항목 하나)
            count[0]++;
            return;
        }
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new java.io.IOException("cannot create " + dst);
            File[] children = src.listFiles();
            if (children == null) throw new java.io.IOException("cannot list " + src);
            for (File c : children) copyTree(c, new File(dst, c.getName()), done, count, total, progress);
            return;
        }
        byte[] buf = new byte[1 << 20];
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done[0] += n;
                if (progress != null) progress.onCopied(done[0], total);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dst.setLastModified(src.lastModified());
        count[0]++;
    }

    // Bytes and file count of a folder (links counted as files of size 0)
    // 폴더의 바이트와 파일 수(링크는 크기 0인 파일로 셈)
    private static long countTree(File dir, long[] count) {
        if (Files.isSymbolicLink(dir.toPath())) { count[0]++; return 0; }
        if (!dir.isDirectory()) { count[0]++; return dir.length(); }
        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File c : children) total += countTree(c, count);
        return total;
    }

    // ==================== Uninstall / 삭제 ====================

    /**
     * Deletes an installed game so it can be downloaded again from scratch:
     * the downloader's depot record, the copies of the install record and the
     * folder links inside every container, the game folder, and finally the
     * install record itself (last, so a failed folder delete can be retried).
     * Shortcuts are kept: a new download writes the same shortcut again, so play
     * time and per-shortcut settings stay. Blocking: call from a background thread.
     * 설치된 게임을 처음부터 다시 받을 수 있게 지움: 다운로더의 depot 기록, 모든
     * 컨테이너 안의 설치 기록 사본과 폴더 링크, 게임 폴더, 마지막으로 설치 기록
     * 자체(마지막에 지워서 폴더 삭제가 실패하면 다시 시도할 수 있음). 숏컷은 남김:
     * 다시 받으면 같은 숏컷을 다시 쓰므로 플레이 시간과 숏컷별 설정이 유지됨.
     * 블로킹이라 백그라운드 스레드에서 불러야 함
     */
    public static void uninstall(Context context, int appId) throws Exception {
        File library = libraryOf(context, appId);
        if (library == null) library = getLibraryDir(context);
        File acf = new File(library, "appmanifest_" + appId + ".acf");
        String dir = readInstallDir(acf);
        // Never touch the common folder itself or anything outside it
        // common 폴더 자체나 그 바깥은 절대 건드리지 않음
        boolean dirOk = dir != null && !dir.trim().isEmpty() && !dir.contains("..");
        File gameDir = dirOk ? new File(library, "common/" + dir) : null;

        // 1) Depot record, from the depots this install record lists
        // 1) depot 기록. 이 설치 기록에 적힌 depot 기준
        List<Integer> depots = new ArrayList<>();
        for (String entry : readAcfDepots(acf).split(",")) {
            String[] p = entry.split(":");
            try { if (p.length > 0 && !p[0].isEmpty()) depots.add(Integer.parseInt(p[0])); }
            catch (NumberFormatException ignored) {}
        }
        forgetDepotRecords(library, gameDir, depots);

        // 2) Copies inside containers (made by linkLibraryInto): the record copy
        //    and the folder link. Only a symbolic link is removed, never a real folder
        // 2) 컨테이너 안의 사본(linkLibraryInto가 만듦): 설치 기록 사본과 폴더 링크.
        //    심볼릭 링크만 지우고 실제 폴더는 절대 지우지 않음
        for (Container c : new ContainerManager(context).getContainers()) {
            File steamApps = new File(c.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamapps");
            File copy = new File(steamApps, acf.getName());
            if (copy.isFile() && !copy.delete()) Log.w(DLTAG, "could not delete " + copy);
            if (dirOk) {
                File link = new File(steamApps, "common/" + dir);
                if (Files.isSymbolicLink(link.toPath()) && !link.delete()) Log.w(DLTAG, "could not delete " + link);
            }
        }

        // 3) Game folder / 3) 게임 폴더
        if (gameDir != null && gameDir.exists()) {
            FileUtils.delete(gameDir);
            if (gameDir.exists()) throw new IllegalStateException("could not delete " + gameDir.getAbsolutePath());
        }

        // 4) Install record last / 4) 설치 기록은 마지막에
        if (acf.isFile() && !acf.delete()) throw new IllegalStateException("could not delete " + acf.getAbsolutePath());
        Log.i(DLTAG, "uninstalled app=" + appId + " dir=" + dir + " depots=" + depots);
    }

    // First container whose Wine/Proton is ARM64EC and runs the Steam agent
    // (Proton 10.0-4 is left out, see SteamWineSupport). If there is none, a
    // default ARM64EC container is created here with such a build (runs on the
    // download thread, so the synchronous ContainerManager.createArm64ECContainer()
    // is used). Returns null only when no usable ARM64EC Wine/Proton is installed.
    // Wine/Proton이 ARM64EC이고 스팀 에이전트가 동작하는 첫 번째 컨테이너를 씀
    // (Proton 10.0-4는 뺌, SteamWineSupport 참고). 없으면 여기서 그런 빌드로 기본
    // ARM64EC 컨테이너를 만듦(다운로드 스레드에서 돌기 때문에 동기식
    // ContainerManager.createArm64ECContainer()를 씀). 쓸 수 있는 ARM64EC
    // Wine/Proton이 아예 설치되어 있지 않을 때만 null을 돌려줌
    private static Container arm64ecContainer(Context context, Progress progress) {
        ContainerManager manager = new ContainerManager(context);
        ArrayList<Container> list = manager.getContainers();
        for (Container c : list) {
            String wine = c.getWineVersion();
            if (wine == null || !wine.toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec")) continue;
            if (SteamWineSupport.isBlocked(wine)) continue;
            return c;
        }
        progress.onStatus(context.getString(R.string.steam_creating_arm64ec_container));
        Container created = manager.createArm64ECContainer(new ContentsManager(context),
                wine -> !SteamWineSupport.isBlocked(wine));
        Log.i(DLTAG, created != null ? "created ARM64EC container " + created.id + " (" + created.getWineVersion() + ")"
                : "no ARM64EC Wine/Proton for Steam installed (Proton 10.0-4 is not used)");
        return created;
    }

    // Same .desktop format as ShortcutsFragment.createDesktopFileFromExePath(), plus steamAppId
    // ShortcutsFragment.createDesktopFileFromExePath()와 같은 .desktop 형식에 steamAppId를 더함
    private static String createShortcut(Context context, Container container, SteamSession.AppDetails app) {
        String name = app.name.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (name.isEmpty()) name = String.valueOf(app.appId);
        File desktopFile = new File(container.getDesktopDir(), name + ".desktop");
        String winExe = CONTAINER_STEAM_COMMON_WIN + "\\" + app.installDir + "\\" + app.exePath;

        String filesDir = context.getFilesDir().getAbsolutePath();
        String home = filesDir + "/imagefs/home/xuser";
        String winePrefix = home + "/.wine/dosdevices/z:" + home + "/.wine";
        String exec = "env WINEPREFIX=\"" + winePrefix + "\" wine " + winExe.replace("\\", "\\\\\\\\");
        File realExe = new File(new File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamapps/common/" + app.installDir),
                app.exePath.replace('\\', '/'));
        String workDir = realExe.getParentFile() != null ? realExe.getParentFile().getAbsolutePath() : "";

        String icon = "";
        File iconFile = new File(container.getIconsDir(64), name + ".png");
        if (ExeIconExtractor.extractLargestIconToFile(realExe, iconFile)) icon = name;

        String content = "[Desktop Entry]\n"
                + "Name=" + name + "\n"
                + "Exec=" + exec + "\n"
                + "Type=Application\n"
                + "StartupNotify=true\n"
                + "Path=" + workDir + "\n"
                + "Icon=" + icon + "\n"
                + "StartupWMClass=" + FileUtils.getName(app.exePath.replace('\\', '/')) + "\n\n"
                + "[Extra Data]\n"
                + "container_id:" + container.id + "\n"
                + "steamAppId=" + app.appId + "\n"
                + SteamLiteManager.launchInfoExtras(app);
        FileUtils.writeString(desktopFile, content);
        return name;
    }
}
