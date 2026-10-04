package com.winlator.cmod.renderer;

import com.winlator.cmod.xserver.Drawable;

class RenderableWindow {
    final Drawable content;
    short rootX;
    short rootY;
    final boolean forceFullscreen;
    // X window id (lets fullscreen mode find the game's client window)
    // X 창 id임(전체화면 모드가 게임의 클라이언트 창을 찾는 데 씀)
    int windowId = -1;

    public RenderableWindow(Drawable content, int rootX, int rootY) {
        this(content, rootX, rootY, false);
    }

    public RenderableWindow(Drawable content, int rootX, int rootY, boolean forceFullscreen) {
        this.content = content;
        this.rootX = (short)rootX;
        this.rootY = (short)rootY;
        this.forceFullscreen = forceFullscreen;
    }
}
