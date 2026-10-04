package com.winlator.cmod.renderer;

// Fullscreen mapping shared by the renderer and touch input.
// In fullscreen the game's own window (title bar and borders cut off) is
// scaled up to fill the X screen (each axis on its own):
//   screen = offset + game * scale (x: scale, y: scaleY)
// TouchpadView applies the inverse so a tap still lands on the right spot.
// Inactive (scale 1, offset 0) outside fullscreen.
// 렌더러와 터치 입력이 함께 쓰는 전체화면 변환임.
// 전체화면에서는 게임 자체 창(제목 표시줄과 테두리를 잘라 낸 것)을 X 화면에
// 꽉 차게 키움(가로·세로 따로):
//   화면 = 오프셋 + 게임 * 배율
// TouchpadView가 역변환을 적용해서 탭이 그대로 맞는 자리에 떨어지게 함.
// 전체화면이 아니면 비활성(배율 1, 오프셋 0)임
public final class FullscreenMapping {
    public static volatile boolean active = false;
    // Separate horizontal / vertical scale - the game window is
    // stretched to fill the whole screen (aspect not kept; requested, the small
    // distortion is acceptable compared with the empty right / bottom strips)
    // 가로 / 세로 배율을 따로 둠 - 게임 창을 화면 전체에 맞게 늘림(비율 유지
    // 안 함. 요청 사항으로, 오른쪽 / 아래 빈 띠보다 약간의 왜곡이 낫다고 판단함)
    public static volatile float scale = 1f;
    public static volatile float scaleY = 1f;
    public static volatile float offsetX = 0f;
    public static volatile float offsetY = 0f;

    private FullscreenMapping() {}

    public static void set(float sx, float sy, float ox, float oy) {
        scale = sx;
        scaleY = sy;
        offsetX = ox;
        offsetY = oy;
        active = true;
    }

    public static void clear() {
        active = false;
        scale = 1f;
        scaleY = 1f;
        offsetX = 0f;
        offsetY = 0f;
    }

    // Screen point (X screen coordinates) -> game point, in place
    // 화면 좌표(X 화면 좌표)를 게임 좌표로 바꿈(배열을 직접 고침)
    public static float[] toGame(float[] p) {
        if (!active || scale == 0f || scaleY == 0f) return p;
        p[0] = (p[0] - offsetX) / scale;
        p[1] = (p[1] - offsetY) / scaleY;
        return p;
    }
}
