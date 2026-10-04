package com.winlator.cmod.widget;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;

import java.util.Locale;

// Compact in-game HUD: one small pill in the top-right corner.
//   while starting : [spinner] Starting…
//   while running  : <engine> | <FPS>   (only when "Show FPS" is on)
// The old five-box line (FPS / Renderer / GPU / RAM / CPU) was reported as
// too large, and nothing told a first-time user that the game was still
// loading. The public methods the activity calls are unchanged
// (reset / setRenderer / setGpuName / update).
// 게임 중 간단 HUD: 오른쪽 위 작은 알약 모양 하나임.
//   시작 중   : [스피너] 시작 중…
//   실행 중   : <엔진> | <FPS>   ("FPS 표시"가 켜져 있을 때만)
// 예전 5칸 줄(FPS / 렌더러 / GPU / RAM / CPU)은 너무 크다는 보고가 있었고,
// 처음 쓰는 사람에게 게임이 아직 로딩 중이라는 걸 알려 주는 것이 없었음.
// 액티비티가 부르는 공개 메서드는 그대로임(reset / setRenderer / setGpuName / update)
public class FrameRating extends FrameLayout implements Runnable {
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private String renderer = null;

    private final View spinner;
    private final TextView tvRenderer;
    private final TextView tvDivider;
    private final TextView tvFPS;

    private boolean showFps = false;
    private boolean loading = false;

    public FrameRating(Context context, Container container) {
        this(context, container, null);
    }

    public FrameRating(Context context, Container container, AttributeSet attrs) {
        this(context, container, attrs, 0);
    }

    public FrameRating(Context context, Container container, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        spinner = view.findViewById(R.id.PBHudLoading);
        tvRenderer = view.findViewById(R.id.TVRenderer);
        tvDivider = view.findViewById(R.id.TVHudDivider);
        tvFPS = view.findViewById(R.id.TVFPS);
        addView(view);
        setVisibility(GONE);
    }

    // Whether engine + FPS stay visible after loading ends
    // 로딩이 끝난 뒤에도 엔진 + FPS를 남길지 정함
    public void setShowFps(boolean showFps) {
        this.showFps = showFps;
    }

    // Spinner + "Starting…" until the first rendered frame (or the timeout)
    // 첫 화면이 그려질 때까지(또는 시간 초과까지) 스피너 + "시작 중…"
    public void startLoading() {
        loading = true;
        spinner.setVisibility(VISIBLE);
        tvRenderer.setText(R.string.hud_starting);
        tvDivider.setVisibility(GONE);
        tvFPS.setVisibility(GONE);
        setVisibility(VISIBLE);
    }

    // Ends the loading state; the pill stays only in "Show FPS" mode
    // 로딩 상태를 끝냄. "FPS 표시" 모드일 때만 알약이 남음
    public void finishLoading() {
        if (!loading) return;
        loading = false;
        spinner.setVisibility(GONE);
        if (!showFps) {
            setVisibility(GONE);
            return;
        }
        tvDivider.setVisibility(VISIBLE);
        tvFPS.setVisibility(VISIBLE);
        refreshTexts();
    }

    public void reset() {
        renderer = null;
        lastFPS = 0;
        lastTime = 0;
        frameCount = 0;
    }

    public void setRenderer(String renderer) {
        this.renderer = displayEngineName(renderer);
    }

    // The engine name comes from the game's Vulkan layer as each library reports it:
    // DXVK sends "DXVK" but vkd3d-proton (DirectX 12) sends lowercase "vkd3d".
    // Known names are shown in upper case so the HUD reads the same for both
    // (vkd3d -> VKD3D); a suffix after the name is kept as is, other names unchanged
    // 엔진 이름은 게임의 Vulkan 레이어가 라이브러리가 알려 준 그대로 넘겨 줌:
    // DXVK는 "DXVK"를 보내지만 vkd3d-proton(DirectX 12)은 소문자 "vkd3d"를 보냄.
    // 알려진 이름은 대문자로 보여 줘서 HUD 표기를 맞춤(vkd3d -> VKD3D). 이름 뒤에
    // 붙은 부분은 그대로 두고, 그 밖의 이름은 바꾸지 않음
    private static String displayEngineName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        for (String known : new String[]{"VKD3D", "DXVK"}) {
            if (trimmed.regionMatches(true, 0, known, 0, known.length())) {
                return known + trimmed.substring(known.length());
            }
        }
        return trimmed;
    }

    // GPU name is no longer shown; kept so the activity's call sites stay valid
    // GPU 이름은 더 이상 표시하지 않음. 액티비티의 호출부가 그대로 동작하도록 남김
    public void setGpuName(String gpuName) {}

    // A new game frame arrived (X content update of the render window).
    // Several updates per game frame are merged into one until it is drawn
    // 새 게임 프레임이 들어왔음(렌더 창의 X 내용 갱신). 게임 프레임 하나의 여러
    // 갱신은 그려질 때까지 하나로 합쳐짐
    private volatile boolean dirty = false;

    public void markDirty() {
        dirty = true;
    }

    // Called after each drawn frame (GL thread): counts it only if it carried
    // a new game frame, so the value cannot exceed the display refresh rate
    // 그려진 프레임마다 호출됨(GL 스레드): 새 게임 프레임을 담았을 때만 셈. 그래서
    // 값이 화면 주사율을 넘을 수 없음
    public void onFrameDrawn() {
        if (!dirty) return;
        dirty = false;
        update();
    }

    // Frame counter (now fed by onFrameDrawn)
    // 프레임 카운터(이제 onFrameDrawn이 호출함)
    public void update() {
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();
        if (time >= lastTime + 500) {
            lastFPS = ((float) (frameCount * 1000) / (time - lastTime));
            post(this);
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    @Override
    public void run() {
        // First measured frame rate = the game is drawing, loading is over
        // 첫 프레임 측정값이 나왔다 = 게임이 그리고 있음, 로딩 끝
        if (loading) finishLoading();
        if (!showFps) return;
        if (getVisibility() == GONE) setVisibility(VISIBLE);
        tvDivider.setVisibility(VISIBLE);
        tvFPS.setVisibility(VISIBLE);
        refreshTexts();
    }

    // Texts are only replaced when they actually change: an unchanged setText
    // still costs a layout pass of the HUD
    // 글자가 실제로 바뀔 때만 교체함: 같은 값으로 setText해도 HUD 레이아웃 계산이
    // 한 번 돌기 때문임
    private void refreshTexts() {
        String engine = renderer != null && !renderer.isEmpty() ? renderer : "OpenGL";
        String fps = String.format(Locale.ENGLISH, "%.0f FPS", lastFPS);
        if (!engine.contentEquals(tvRenderer.getText())) tvRenderer.setText(engine);
        if (!fps.contentEquals(tvFPS.getText())) tvFPS.setText(fps);
    }
}
