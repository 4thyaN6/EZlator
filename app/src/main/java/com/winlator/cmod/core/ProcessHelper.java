package com.winlator.cmod.core;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

public abstract class ProcessHelper {
    public static final boolean PRINT_DEBUG = true; // FIXME change to false
    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();
    private static final byte SIGCONT = 18;
    private static final byte SIGSTOP = 19;
    private static final byte SIGTERM = 15;
    private static final byte SIGKILL = 9;

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, SIGSTOP);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, SIGCONT);
    }

    public static void terminateProcess(int pid) {
        Process.sendSignal(pid, SIGTERM);
    }

    public static void killProcess(int pid) {
        Process.sendSignal(pid, SIGKILL);
    }

    // How long terminateAllWineProcesses() waits for SIGTERM before SIGKILL
    // terminateAllWineProcesses()가 SIGKILL 전에 SIGTERM 결과를 기다리는 시간임
    private static final long TERMINATE_GRACE_MS = 1500;
    private static final long TERMINATE_POLL_MS = 100;

    // CHANGED: SIGTERM alone left Wine/box64 processes alive between
    // sessions. Logcat 2026-09-30: winhandler.exe (pid 20769) and Game.exe under
    // box64 (pid 26748) got SIGTERM on every exit from 22:01 to 22:25 and were
    // still there each time; the live box64 then made the next box64 extraction
    // fail with ETXTBSY. Now: SIGCONT first (a process stopped by
    // pauseAllWineProcesses() cannot act on SIGTERM), then SIGTERM, wait up to
    // TERMINATE_GRACE_MS, and SIGKILL whatever is still alive. Callers run this
    // off the UI thread (exitApp() uses an executor), so the wait is safe.
    // 변경: SIGTERM만으로는 Wine/box64 프로세스가 세션 사이에 살아남았음.
    // 로그캣 2026-09-30: winhandler.exe(pid 20769)와 box64 위의 Game.exe(pid 26748)가
    // 22:01~22:25 동안 종료할 때마다 SIGTERM을 받았는데도 매번 남아 있었고, 살아 있는
    // box64 때문에 다음 box64 압축 해제가 ETXTBSY로 실패했음. 이제는 먼저 SIGCONT를
    // 보내고(pauseAllWineProcesses()로 멈춘 프로세스는 SIGTERM을 처리하지 못함),
    // SIGTERM 후 최대 TERMINATE_GRACE_MS만큼 기다린 뒤, 남은 프로세스는 SIGKILL함.
    // 호출하는 곳이 UI 스레드 밖에서 돌리므로(exitApp()은 executor 사용) 기다려도 안전함
    // ==================== Staged shutdown ====================
    // ==================== 단계별 종료 ====================
    // Logcat 2026-10-02 22:01~22:55: an ARM64EC "C:\Program Files (x86)\Steam\
    // steam.exe" (pid 16858) kept running at about 700% CPU (7 cores) through
    // every game exit and even through an app reinstall - that was the heat
    // that only a reboot stopped. Cause: the old list matched names by "wine",
    // "exe" or "box64" in /proc/<pid>/stat, but that name is cut to 15
    // characters ("C:\Program File"), and an ARM64EC process does not run the
    // box64 binary either, so it was never signalled. Android then logged
    // "Unable to remove cgroup ... Device or resource busy" after every exit.
    // Now every process of this app's uid that is not an app process itself
    // (cmdline "com.ezlator.app...") counts as a guest process, and they are
    // stopped in this order, each stage SIGTERM -> up to 1 s -> SIGKILL:
    //   1. game processes (Windows path outside C:\windows)
    //   2. other Wine processes (system .exe, wine loader)
    //   3. wineserver
    //   4. box64 / FEX
    //   5. PRoot
    //   6. anything else that is left (audio server etc.)
    //   7. check: whatever survived gets SIGKILL, the count is logged
    // 로그캣 2026-10-02 22:01~22:55: ARM64EC "C:\Program Files (x86)\Steam\steam.exe"
    // (pid 16858)가 게임을 끝낼 때마다, 심지어 앱을 다시 설치한 뒤에도 CPU 약 700%
    // (코어 7개)로 계속 돌았음 - 재부팅해야만 멈추던 발열이 이것이었음. 원인: 예전
    // 목록은 /proc/<pid>/stat의 이름에서 "wine", "exe", "box64"를 찾았는데, 그 이름은
    // 15자로 잘리고("C:\Program File"), ARM64EC 프로세스는 box64 실행 파일도 쓰지
    // 않아서 한 번도 신호를 받지 못했음. 그래서 종료할 때마다 안드로이드가 "Unable to
    // remove cgroup ... Device or resource busy"를 남겼음.
    // 이제는 이 앱 uid의 프로세스 중 앱 자체 프로세스(cmdline "com.ezlator.app...")가
    // 아닌 것을 모두 게스트 프로세스로 보고, 아래 순서로 멈춤. 단계마다
    // SIGTERM -> 최대 1초 -> SIGKILL임:
    //   1. 게임 프로세스(C:\windows 밖의 Windows 경로)
    //   2. 나머지 Wine 프로세스(시스템 .exe, wine 로더)
    //   3. wineserver
    //   4. box64 / FEX
    //   5. PRoot
    //   6. 그 밖에 남은 것(오디오 서버 등)
    //   7. 확인: 살아남은 것은 SIGKILL하고 개수를 기록함
    private static final long STAGE_GRACE_MS = 1000;

    private static final class GuestProc {
        final int pid;
        final String comm;
        final String cmdline;
        final String exe;

        GuestProc(int pid, String comm, String cmdline, String exe) {
            this.pid = pid;
            this.comm = comm;
            this.cmdline = cmdline;
            this.exe = exe;
        }

        int stage() {
            String c = cmdline.toLowerCase(java.util.Locale.ENGLISH);
            String e = exe.toLowerCase(java.util.Locale.ENGLISH);
            String n = comm.toLowerCase(java.util.Locale.ENGLISH);
            // Decided by the Windows paths anywhere in the command
            // line. On X86_64 (box64) the line starts with the program name and
            // the wine loader path ("services.exe .../wine C:\\windows\\..."),
            // so the old "starts with c:\\windows" test put Wine services into
            // the game stage; any C:\\windows path now marks a system process
            // 명령줄 어디에 있든 Windows 경로로 판단함. X86_64(box64)에서는 줄이
            // 프로그램 이름과 wine 로더 경로로 시작해서("services.exe .../wine
            // C:\\windows\\..."), 예전 "c:\\windows로 시작" 검사는 Wine 서비스를 게임
            // 단계에 넣었음. 이제 C:\\windows 경로가 있으면 시스템 프로세스임
            boolean windowsPath = c.matches("(?s).*[a-z]:\\\\.*");
            boolean systemPath = c.contains("c:\\windows\\");
            boolean helper = c.contains("winhandler.exe") || c.contains("wfm.exe")
                    || c.contains("explorer.exe") || c.contains("tabtip.exe");
            if (windowsPath && !systemPath && !helper) return 1;
            windowsPath = windowsPath || c.contains(".exe");
            if (n.contains("wineserver") || c.contains("wineserver")) return 3;
            if (windowsPath || n.contains("wine") || e.contains("/wine")) return 2;
            if (e.endsWith("/box64") || n.contains("box64") || e.contains("fex") || n.contains("fex")) return 4;
            if (e.contains("proot") || n.contains("proot")) return 5;
            return 6;
        }

        @Override
        public String toString() {
            return pid + " (" + (cmdline.isEmpty() ? comm : cmdline) + ")";
        }
    }

    // Every process of this uid except the app's own processes
    // 이 uid의 모든 프로세스 중 앱 자체 프로세스를 뺀 것
    private static ArrayList<GuestProc> listGuestProcesses() {
        ArrayList<GuestProc> list = new ArrayList<>();
        int myPid = Process.myPid();
        int myUid = Process.myUid();
        File proc = new File("/proc");
        String[] pids = proc.list((dir, name) -> name.matches("[0-9]+"));
        if (pids == null) return list;
        for (String p : pids) {
            int pid = Integer.parseInt(p);
            if (pid == myPid) continue;
            try {
                if (procUid(pid) != myUid) continue;
                String stat = readFirstLine(new File(proc, p + "/stat"));
                if (stat == null) continue;
                int open = stat.indexOf('('), close = stat.lastIndexOf(')');
                if (close != -1 && close + 2 < stat.length() && stat.charAt(close + 2) == 'Z') continue;
                String comm = open >= 0 && close > open ? stat.substring(open + 1, close) : "";
                String cmdline = readCmdline(new File(proc, p + "/cmdline"));
                // App processes (main, ":download") are not guests
                // 앱 프로세스(메인, ":download")는 게스트가 아님
                if (cmdline.startsWith("com.ezlator") || cmdline.startsWith("com.winlator")) continue;
                String exe = "";
                try {
                    exe = new File(proc, p + "/exe").getCanonicalPath();
                } catch (IOException | SecurityException ignored) {}
                list.add(new GuestProc(pid, comm, cmdline, exe));
            } catch (Exception ignored) {
                // Process ended while being read / 읽는 도중 끝난 프로세스
            }
        }
        return list;
    }

    private static int procUid(int pid) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/" + pid + "/status")))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    return Integer.parseInt(parts[0]);
                }
            }
        }
        return -1;
    }

    private static String readFirstLine(File f) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readCmdline(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).replace('\0', ' ').trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isAlive(int pid) {
        return new File("/proc/" + pid).exists();
    }

    // Number of game processes (stage 1) running now
    // 지금 돌고 있는 게임 프로세스(1단계) 개수임
    public static int countGameProcesses() {
        int n = 0;
        for (GuestProc g : listGuestProcesses()) if (g.stage() == 1) n++;
        return n;
    }

    // Stops every guest process in the staged order above
    // 위의 단계 순서대로 모든 게스트 프로세스를 멈춤
    public static void terminateAllGuestProcesses(String reason) {
        terminateAllGuestProcesses(reason, 6);
    }

    // maxStage 4 = Wine side only (game, Wine, wineserver, box64/FEX); used by
    // the in-session Wine restart, which must keep PRoot and the audio server
    // maxStage 4 = Wine 쪽만(게임, Wine, wineserver, box64/FEX). 세션 중 Wine 재시작에
    // 씀. 이때는 PRoot와 오디오 서버를 살려 둬야 함
    public static void terminateAllGuestProcesses(String reason, int maxStage) {
        ArrayList<GuestProc> all = listGuestProcesses();
        if (all.isEmpty()) {
            Log.i("ProcessHelper", "cleanup(" + reason + "): no guest processes");
            return;
        }
        Log.i("ProcessHelper", "cleanup(" + reason + "): " + all.size() + " guest processes");
        for (int stage = 1; stage <= maxStage; stage++) {
            ArrayList<GuestProc> group = new ArrayList<>();
            for (GuestProc g : all) if (g.stage() == stage && isAlive(g.pid)) group.add(g);
            if (group.isEmpty()) continue;
            for (GuestProc g : group) {
                resumeProcess(g.pid);
                terminateProcess(g.pid);
            }
            long deadline = System.currentTimeMillis() + STAGE_GRACE_MS;
            while (System.currentTimeMillis() < deadline) {
                boolean any = false;
                for (GuestProc g : group) if (isAlive(g.pid)) { any = true; break; }
                if (!any) break;
                try {
                    Thread.sleep(TERMINATE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (GuestProc g : group) {
                if (isAlive(g.pid)) {
                    Log.w("ProcessHelper", "stage " + stage + ": " + g + " ignored SIGTERM, sending SIGKILL");
                    killProcess(g.pid);
                }
            }
        }
        // 7. Final check / 최종 확인
        ArrayList<GuestProc> left = new ArrayList<>();
        for (GuestProc g : listGuestProcesses()) if (g.stage() <= maxStage) left.add(g);
        for (GuestProc g : left) {
            Log.w("ProcessHelper", "cleanup(" + reason + "): still alive, SIGKILL " + g);
            killProcess(g.pid);
        }
        Log.i("ProcessHelper", "cleanup(" + reason + "): done, " + left.size() + " needed a final SIGKILL");
    }

    // Kept for existing callers; now runs the staged shutdown
    // 기존 호출부용으로 남김. 이제 단계별 종료를 실행함
    public static void terminateAllWineProcesses() {
        terminateAllGuestProcesses("wine", 4);
    }

    public static void pauseAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            suspendProcess(Integer.parseInt(process));
        }
    }

    public static void resumeAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            resumeProcess(Integer.parseInt(process));
        }
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, String[] envp) {
        return exec(command, envp, null);
    }

    public static int exec(String command, String[] envp, File workingDir) {
        return exec(command, envp, workingDir, null);
    }

    public static int exec(String command, String[] envp, File workingDir, Callback<Integer> terminationCallback) {
        Log.d("ProcessHelper", "env: " + Arrays.toString(envp) + "\ncmd: " + command);

        // Store env vars for future use
        EnvironmentManager.setEnvVars(envp);

        int pid = -1;
        try {
            Log.d("ProcessHelper", "Splitting command: " + command);
            String[] splitCommand = splitCommand(command);
            Log.d("ProcessHelper", "Split command result: " + Arrays.toString(splitCommand));
            Log.d("ProcessHelper", "Starting process...");
            ProcessBuilder pb = new ProcessBuilder(splitCommand);
            pb.directory(workingDir);
            pb.environment().putAll(EnvironmentManager.getEnvVars());
            if (debugCallbacks.isEmpty()) {
                File null_file = new File("/dev/null");
                pb.redirectError(null_file);
                pb.redirectOutput(null_file);
            }
            java.lang.Process process = pb.start();

            // Accessing hidden field
            Log.d("ProcessHelper", "Accessing hidden field to get PID");
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);
            Log.d("ProcessHelper", "Process started with pid: " + pid);

            if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }

        }
        catch (Exception e) {
            Log.e("ProcessHelper", "Error executing command: " + command, e);
        }
        return pid;
    }

    private static void createDebugThread(final InputStream inputStream) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PRINT_DEBUG) System.out.println(line);
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                    }
                }
            }
            catch (IOException e) {
                Log.e("ProcessHelper", "Error in debug thread", e);
            }
        });
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
            Log.d("ProcessHelper", "All debug callbacks removed");
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) debugCallbacks.add(callback);
            Log.d("ProcessHelper", "Added debug callback: " + callback.toString());
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
            Log.d("ProcessHelper", "Removed debug callback: " + callback.toString());
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);

            if (startedQuotes) {
                if (currChar == '"') {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += '"';
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"') {
                startedQuotes = true;
                value += '"';
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    // CHANGED: reads each /proc/<pid>/stat with try-with-resources
    // (the old code never closed the stream), adds a pid only once even when both
    // filters match (e.g. "winedevice.exe"), and skips zombies (state Z). A zombie
    // is already dead and cannot be signalled, so listing it only made
    // terminateAllWineProcesses() wait for nothing.
    // 변경: /proc/<pid>/stat을 try-with-resources로 읽음(예전 코드는 스트림을
    // 닫지 않았음). 필터 두 개에 다 걸려도(예: "winedevice.exe") pid를 한 번만 넣음.
    // 좀비(상태 Z)는 뺌. 좀비는 이미 죽은 상태라 신호가 안 먹으므로, 목록에 넣으면
    // terminateAllWineProcesses()가 헛되이 기다리기만 함
    public static ArrayList<String> listRunningWineProcesses(){
        File proc = new File("/proc");
        // "box64" added: a guest process can still be named "box64"
        // (e.g. before box64 renames it), and those were never signalled
        // "box64" 추가: 게스트 프로세스 이름이 아직 "box64"일 수 있는데
        // (예: box64가 이름을 바꾸기 전), 그런 프로세스는 신호를 받은 적이 없었음
        String[] filters = {"wine", "exe", "box64"};
        ArrayList<String> filteredPids = new ArrayList<String>();
        String[] allPids = proc.list(new FilenameFilter(){
            public boolean accept(File proc, String filename){
                return new File(proc, filename).isDirectory() && filename.matches("[0-9]+");
            }
        });
        if (allPids == null) return filteredPids;

        for (String pid : allPids) {
            String data;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(proc + "/" + pid + "/stat")))) {
                data = br.readLine();
            }
            catch (IOException e) {
                continue;
            }
            if (data == null) continue;

            // stat format: "pid (comm) state ...", comm may contain spaces
            // stat 형식: "pid (comm) state ...", comm 안에 공백이 있을 수 있음
            int close = data.lastIndexOf(')');
            if (close != -1 && close + 2 < data.length() && data.charAt(close + 2) == 'Z') continue;

            boolean match = false;
            for (String filter : filters) {
                if (data.contains(filter)) {
                    match = true;
                    break;
                }
            }
            // ADDED: also catch box64 processes whose name has neither
            // "wine" nor "exe". Logcat 2026-10-01 00:33~00:35: after the name-based
            // cleanup every listed pid died, yet the next start still hit ETXTBSY on
            // imagefs/usr/bin/box64, so some process running that binary was never
            // listed. /proc/<pid>/exe points at the binary being run, which is exact.
            // 추가: 이름에 "wine"도 "exe"도 없는 box64 프로세스도 잡음. 로그캣
            // 2026-10-01 00:33~00:35: 이름 기준 정리로 목록에 있던 pid는 전부 종료됐는데도
            // 다음 실행에서 imagefs/usr/bin/box64가 여전히 ETXTBSY였음. 즉 그 파일을 실행
            // 중인 프로세스 중 목록에 안 잡힌 것이 있었음. /proc/<pid>/exe는 실제 실행 중인
            // 파일을 가리키므로 정확함
            if (!match) {
                try {
                    String exe = new File(proc, pid + "/exe").getCanonicalPath();
                    match = exe.endsWith("/usr/bin/box64");
                }
                catch (IOException | SecurityException ignored) {}
            }
            if (match) filteredPids.add(pid);
        }
        return filteredPids;
    }
}
