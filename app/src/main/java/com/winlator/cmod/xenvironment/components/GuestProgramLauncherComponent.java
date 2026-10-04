package com.winlator.cmod.xenvironment.components;

import android.os.Process;

import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private String box86Preset = Box86_64Preset.COMPATIBILITY;
    private String box64Preset = Box86_64Preset.COMPATIBILITY;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private boolean wow64Mode = true;

    // Base start() is intentionally empty. Only BionicProgramLauncherComponent is ever
    // created and it overrides start() without calling super, so the old glibc/PRoot
    // launch path here (execGuestProgram, extractBox86_64Files, box86 env vars) never
    // ran. It also pointed at libproot.so, which the APK does not ship.
    // 기본 start()는 의도적으로 비워둠. 실제로 생성되는 건 BionicProgramLauncherComponent
    // 뿐이고, 이 클래스가 super 호출 없이 start()를 재정의하므로 여기 있던 예전
    // glibc/PRoot 실행 경로(execGuestProgram, extractBox86_64Files, box86 환경변수)는
    // 한 번도 실행되지 않았음. APK에 없는 libproot.so를 가리키고 있기도 했음
    @Override
    public void start() {}

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public String[] getBindingPaths() {
        return bindingPaths;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox86Preset() {
        return box86Preset;
    }

    public void setBox86Preset(String box86Preset) {
        this.box86Preset = box86Preset;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public void suspendProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.suspendProcess(pid);
        }
    }

    public void resumeProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.resumeProcess(pid);
        }
    }

    public String execShellCommand(String command) {
        throw new UnsupportedOperationException("execShellCommand not implemented in base class.");
    }

}
