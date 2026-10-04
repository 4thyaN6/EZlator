package com.winlator.cmod.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.material.CursorMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import com.winlator.cmod.renderer.material.WindowMaterial;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    public final XServerView xServerView;
    private final XServer xServer;
    public final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private String forceFullscreenWMClass = null;
    // Borderless: hide Wine's frame window (title bar + borders) around a game's
    // client window. Only the frame is dropped from rendering - the client keeps
    // its size and position, so aspect ratio and touch/mouse mapping are untouched.
    // 테두리 없음: 게임 클라이언트 창을 감싼 Wine 프레임 창(제목 표시줄+테두리)을
    // 숨김. 프레임만 그리지 않고 클라이언트 창은 크기·위치 그대로라서 화면비와
    // 터치/마우스 좌표 변환에는 영향이 없음
    private boolean borderless = false;
    private boolean fullscreen = false;
    // Diagnostic state for the window-list log above
    // 위 창 목록 로그를 위한 진단용 상태
    private final StringBuilder windowSignature = new StringBuilder();
    private String lastWindowSignature = "";
    private boolean toggleFullscreen = false;
    public boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private boolean rootWindowDownsized = false;
    private boolean screenOffsetYRelativeToCursor = false;
    private String[] unviewableWMClasses = null;
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    public int surfaceWidth;
    public int surfaceHeight;
    private final EffectComposer effectComposer;

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        this.effectComposer = new EffectComposer(this);
        rootCursorDrawable = createRootCursorDrawable();

        quadVertices.put(new float[]{
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GPUImage.checkIsSupported();

        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
    }

    // Called after every drawn frame (GL thread). The HUD counts these instead
    // of raw X content updates: one game frame can arrive as several updates
    // (the reported "400 FPS with a 120 limit"), while GLSurfaceView draws at
    // most once per display refresh
    // 프레임을 그릴 때마다 호출됨(GL 스레드). HUD는 X 내용 갱신 횟수 대신 이것을
    // 셈: 게임 프레임 하나가 여러 번의 갱신으로 들어올 수 있는데(보고된 "120 제한인데
    // 400 FPS"), GLSurfaceView는 화면 주사율당 최대 한 번만 그림
    private volatile Runnable frameDrawnListener;

    public void setOnFrameDrawnListener(Runnable listener) {
        this.frameDrawnListener = listener;
    }

    // Window the activity identified as the game's render surface (the Vulkan /
    // GL client window carrying the HUD property). Preferred fullscreen target
    // 액티비티가 게임의 렌더링 면으로 찾아 둔 창(HUD 속성을 가진 Vulkan / GL
    // 클라이언트 창)임. 전체화면 대상으로 우선 씀
    public volatile int preferredFullscreenWindowId = -1;

    @Override
    public void onDrawFrame(GL10 gl) {
        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
            if (!fullscreen) FullscreenMapping.clear();
        }

        drawFrame();
        Runnable l = frameDrawnListener;
        if (l != null) l.run();
    }

    public void drawFrame() {
        // Update the viewport if necessary
        if (viewportNeedsUpdate && magnifierEnabled) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            }
            else {
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        // Clear the screen before drawing
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Apply basic transformations and draw windows
        if (magnifierEnabled) {
            // Apply magnifier transformations if enabled
            float pointerX = 0;
            float pointerY = 0;
            float magnifierZoom = !screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;

            if (magnifierZoom != 1.0f) {
                pointerX = Mathf.clamp(xServer.pointer.getX() * magnifierZoom - xServer.screenInfo.width * 0.5f, 0, xServer.screenInfo.width * Math.abs(1.0f - magnifierZoom));
            }

            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
                float scaleY = magnifierZoom != 1.0f ? Math.abs(1.0f - magnifierZoom) : 0.5f;
                float offsetY = xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                pointerY = Mathf.clamp(xServer.pointer.getY() * magnifierZoom - offsetY, 0, xServer.screenInfo.height * scaleY);
            }

            XForm.makeTransform(tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0);
        } else {
            if (!fullscreen) {
                int pointerY = 0;
                if (screenOffsetYRelativeToCursor) {
                    short halfScreenHeight = (short)(xServer.screenInfo.height / 2);
                    pointerY = Mathf.clamp(xServer.pointer.getY() - halfScreenHeight / 2, 0, halfScreenHeight);
                }

                XForm.makeTransform(tmpXForm2, viewTransformation.sceneOffsetX, viewTransformation.sceneOffsetY - pointerY, viewTransformation.sceneScaleX, viewTransformation.sceneScaleY, 0);

                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            } else {
                XForm.identity(tmpXForm2);
            }
        }

        // Render windows without effects
        // Was renderWindows(xrImmersive); XR mode is removed, so it is always false
        // 원래 renderWindows(xrImmersive)였음. XR 모드를 제거해서 항상 false임
        renderWindows(false);

        // Render cursor if enabled
        // The X cursor is not drawn while the fullscreen mapping is active: it
        // would sit at its unscaled position
        // 전체화면 변환 중에는 X 커서를 그리지 않음: 배율이 적용되지 않은 자리에
        // 그려지기 때문임
        if (cursorVisible && !rootWindowDownsized) renderCursor();

        // Disable scissor test if magnifier is disabled and not in fullscreen mode
        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }

        // Apply all the effects using EffectComposer
        if (effectComposer.hasEffects()) {
            effectComposer.render();  // <-- This line applies the effects
        }

    }


    @Override
    public void onMapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        if (resized) {
            xServerView.queueEvent(this::updateScene);
        }
        else xServerView.queueEvent(() -> updateWindowPosition(window));
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) xServerView.requestRender();
    }

    @Override
    public void onPointerMove(short x, short y) {
        xServerView.requestRender();
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        renderDrawable(drawable, x, y, material, false);
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material, boolean forceFullscreen) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            if (forceFullscreen) {
                short newHeight = (short)Math.min(xServer.screenInfo.height, ((float)xServer.screenInfo.width / drawable.width) * drawable.height);
                short newWidth = (short)(((float)newHeight / drawable.height) * drawable.width);
                XForm.set(tmpXForm1, (xServer.screenInfo.width - newWidth) * 0.5f, (xServer.screenInfo.height - newHeight) * 0.5f, newWidth, newHeight);
            }
            else XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);

            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void renderWindows(boolean forceFullscreen) {
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        boolean singleWindow = forceFullscreen;
        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            rootWindowDownsized = false;
            if (fullscreen && !renderableWindows.isEmpty()) {
                RenderableWindow root = renderableWindows.get(0);
                if ((root.content.width < xServer.screenInfo.width) || (root.content.height < xServer.screenInfo.height)) {
                    rootWindowDownsized = true;
                    singleWindow = true;
                }
            }
            if (fullscreen && !forceFullscreen && renderFullscreenTarget()) {
                // Drawn by renderFullscreenTarget() / renderFullscreenTarget()가 그림
            }
            else if (singleWindow && !renderableWindows.isEmpty()) {
                RenderableWindow window = renderableWindows.get(renderableWindows.size() - 1);
                renderDrawable(window.content, window.rootX, window.rootY, windowMaterial, true);
            } else {
                for (RenderableWindow window : renderableWindows) {
                    renderDrawable(window.content, window.rootX, window.rootY, windowMaterial, window.forceFullscreen);
                }
            }
        }

        quadVertices.disable();

        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e("GLRenderer", "OpenGL Error: " + error);
        }

    }

    // ==================== Fullscreen ====================
    // ==================== 전체화면 ====================
    // Goal: a windowed game should look fullscreen. The game's window is scaled
    // to fill the X screen (stretched on each axis) and every window is moved by the
    // same transform, so in-game popups stay in place. Windows that fully
    // contain the target and are bigger (its frame with the title bar, the Wine
    // desktop) are not drawn, which crops the title bar and borders on all sides.
    // 목표: 창 모드 게임이 전체화면처럼 보이게 함. 게임 창을 X 화면에 맞게 키우고
    // (가로·세로 각각 늘림), 모든 창을 같은 변환으로 옮겨서 게임 안 팝업도 제자리에 있음.
    // 대상을 완전히 감싸면서 더 큰 창(제목 표시줄이 있는 프레임, Wine 데스크톱)은
    // 그리지 않아서 사방의 제목 표시줄과 테두리가 잘려 나감
    private boolean renderFullscreenTarget() {
        RenderableWindow target = pickFullscreenTarget();
        if (target == null) {
            FullscreenMapping.clear();
            return false;
        }
        float sw = xServer.screenInfo.width, sh = xServer.screenInfo.height;
        float tw = target.content.width, th = target.content.height;
        // Fill the screen on both axes (was: fit, keeping the aspect,
        // which left empty strips on the right / bottom)
        // 가로·세로 모두 화면에 꽉 채움(예전엔 비율을 지켜 맞춰서 오른쪽 /
        // 아래에 빈 띠가 남았음)
        float s = sw / tw, sy = sh / th;
        float ox = -target.rootX * s;
        float oy = -target.rootY * sy;
        FullscreenMapping.set(s, sy, ox, oy);

        for (RenderableWindow w : renderableWindows) {
            if (w.content == null) continue;
            if (w != target && encloses(w, target)) continue;
            synchronized (w.content.renderLock) {
                Texture texture = w.content.getTexture();
                texture.updateFromDrawable(w.content);
                XForm.set(tmpXForm1, ox + w.rootX * s, oy + w.rootY * sy, w.content.width * s, w.content.height * sy);
                XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
                GLES20.glUniform1i(windowMaterial.getUniformLocation("texture"), 0);
                GLES20.glUniform1fv(windowMaterial.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            }
        }
        return true;
    }

    // 1) the window the activity marked as the game's render surface,
    // 2) else the largest window smaller than the X screen (the game window),
    // 3) none -> the game already covers the screen, nothing to do
    // 1) 액티비티가 게임 렌더링 면으로 표시한 창,
    // 2) 없으면 X 화면보다 작은 창 중 가장 큰 창(게임 창),
    // 3) 없음 -> 게임이 이미 화면을 덮고 있으므로 할 일 없음
    private RenderableWindow pickFullscreenTarget() {
        int sw = xServer.screenInfo.width, sh = xServer.screenInfo.height;
        int preferred = preferredFullscreenWindowId;
        RenderableWindow best = null;
        long bestArea = 0;
        for (RenderableWindow w : renderableWindows) {
            if (w.content == null || w.content.width <= 0 || w.content.height <= 0) continue;
            if (preferred != -1 && w.windowId == preferred) {
                return (w.content.width >= sw && w.content.height >= sh) ? null : w;
            }
            if (w.content.width >= sw && w.content.height >= sh) continue;
            long area = (long) w.content.width * w.content.height;
            if (area > bestArea) {
                bestArea = area;
                best = w;
            }
        }
        return best;
    }

    private static boolean encloses(RenderableWindow outer, RenderableWindow inner) {
        int ox2 = outer.rootX + outer.content.width, oy2 = outer.rootY + outer.content.height;
        int ix2 = inner.rootX + inner.content.width, iy2 = inner.rootY + inner.content.height;
        boolean contains = outer.rootX <= inner.rootX && outer.rootY <= inner.rootY && ox2 >= ix2 && oy2 >= iy2;
        long outerArea = (long) outer.content.width * outer.content.height;
        long innerArea = (long) inner.content.width * inner.content.height;
        return contains && outerArea > innerArea;
    }

    private void renderCursor() {
        cursorMaterial.use();
        GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(cursorMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = xServer.pointer.getClampedX();
            short y = xServer.pointer.getClampedY();

            // In fullscreen the cursor goes through the same mapping as
            // the game (it used to be hidden, which lost the pointer in
            // touchpad mode). Scaled by the same factor so it matches the game
            // 전체화면에서는 커서도 게임과 같은 변환을 거침(예전에는 숨겨서
            // 터치패드 모드에서 커서가 사라졌음). 게임과 맞도록 같은 배율로 키움
            if (FullscreenMapping.active) {
                float s = FullscreenMapping.scale, sy = FullscreenMapping.scaleY;
                float ox = FullscreenMapping.offsetX, oy = FullscreenMapping.offsetY;
                Drawable img = cursor != null ? (cursor.isVisible() ? cursor.cursorImage : null) : rootCursorDrawable;
                int hx = cursor != null ? cursor.hotSpotX : 0, hy = cursor != null ? cursor.hotSpotY : 0;
                if (img != null) {
                    synchronized (img.renderLock) {
                        Texture texture = img.getTexture();
                        texture.updateFromDrawable(img);
                        XForm.set(tmpXForm1, ox + (x - hx) * s, oy + (y - hy) * sy, img.width * s, img.height * sy);
                        XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
                        GLES20.glUniform1i(cursorMaterial.getUniformLocation("texture"), 0);
                        GLES20.glUniform1fv(cursorMaterial.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                    }
                }
            }
            else if (cursor != null) {
                if (cursor.isVisible()) renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial);
            }
            else renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
        }

        quadVertices.disable();
    }

    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    private void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            windowSignature.setLength(0);
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
            // Fires only when the set of windows actually changes
            // 창 구성이 실제로 바뀔 때만 찍힘
            String signature = windowSignature.toString();
            if (!signature.equals(lastWindowSignature)) {
                lastWindowSignature = signature;
                Log.d("GLRenderer", "renderable windows: " + signature);
            }
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;

            if (unviewableWMClasses != null) {
                String wmClass = window.getClassName();
                for (String unviewableWMClass : unviewableWMClasses) {
                    if (wmClass.contains(unviewableWMClass)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false;
                        break;
                    }
                }
            }

            if (viewable) {
                // A frame is a parent whose only child is the client and that is at
                // most 16px wider (side borders) and 48px taller (caption + borders).
                // The parent was collected just before this child, so it is removed
                // from the list here. The Wine desktop has many children, so it never
                // matches.
                // 프레임은 자식이 클라이언트 하나뿐이고, 폭이 최대 16px(양옆 테두리),
                // 높이가 최대 48px(제목 표시줄+테두리) 더 큰 부모 창임. 부모는 이 자식
                // 바로 앞에 목록에 들어갔으므로 여기서 빼냄. Wine 데스크탑은 자식이
                // 많아서 절대 해당되지 않음
                // BUG FIX: only TOP-LEVEL windows (children of the root or of the Wine
                // desktop) can be frames. Before, a game's own client window that
                // briefly held one near-full-size child (e.g. an in-game settings
                // panel) matched too, so the game image itself was dropped and the
                // screen went blank when the panel appeared/closed.
                // 버그 수정: 프레임이 될 수 있는 건 최상위 창(루트 또는 Wine 데스크탑의
                // 자식)뿐임. 예전엔 게임 자체 클라이언트 창이 거의 같은 크기의 자식
                // 하나(예: 게임 내 설정창)를 잠깐 가질 때도 조건에 걸려서, 게임 화면
                // 자체가 빠지고 설정창이 뜨거나 닫힐 때 화면이 사라졌음
                if (borderless) {
                    Window parent = window.getParent();
                    Window grand = parent != null ? parent.getParent() : null;
                    boolean parentIsTopLevel = grand != null && (grand == xServer.windowManager.rootWindow
                            || grand.getParent() == xServer.windowManager.rootWindow);
                    if (parent != null && parent != xServer.windowManager.rootWindow && parentIsTopLevel
                            && parent.getChildCount() == 1) {
                        int bx = parent.getWidth() - window.getWidth();
                        int by = parent.getHeight() - window.getHeight();
                        if (bx > 0 && by > 0 && bx <= 16 && by <= 48) removeRenderableWindow(parent);
                    }
                }

                if (forceFullscreenWMClass != null) {
                    short width = window.getWidth();
                    short height = window.getHeight();
                    boolean forceFullscreen= false;

                    if (width >= 320 && height >= 200 && width < xServer.screenInfo.width && height < xServer.screenInfo.height) {
                        Window parent = window.getParent();
                        boolean parentHasWMClass = parent.getClassName().contains(forceFullscreenWMClass);
                        boolean hasWMClass = window.getClassName().contains(forceFullscreenWMClass);
                        if (hasWMClass) {
                            forceFullscreen = !parentHasWMClass && window.getChildCount() == 0;
                        }
                        else {
                            short borderX = (short)(parent.getWidth() - width);
                            short borderY = (short)(parent.getHeight() - height);
                            if (parent.getChildCount() == 1 && borderX > 0 && borderY > 0 && borderX <= 12) {
                                forceFullscreen = true;
                                removeRenderableWindow(parent);
                            }
                        }
                    }

                    RenderableWindow rw = new RenderableWindow(window.getContent(), x, y, forceFullscreen);
                    rw.windowId = window.id;
                    renderableWindows.add(rw);
                }
                else {
                    RenderableWindow rw = new RenderableWindow(window.getContent(), x, y);
                    rw.windowId = window.id;
                    renderableWindows.add(rw);
                }
                // Diagnostic. This is the only place that knows which X11 window each entry
                // came from - RenderableWindow itself keeps no wmClass or id, so logging at
                // draw time can only show sizes and cannot tell explorer from the game.
                // Keyed on the joined wmClass list so it fires when the set actually changes,
                // not merely when the count happens to differ between frames.
                // 진단용. 각 항목이 어느 X11 창에서 왔는지 아는 곳은 여기뿐임.
                // RenderableWindow에는 wmClass도 id도 없어서 그리는 시점에 찍으면 크기만
                // 보이고 explorer와 게임을 구분할 수 없음.
                // 개수가 아니라 wmClass 목록 문자열을 기준으로 비교해서, 프레임마다 개수가
                // 흔들리는 경우에 로그가 폭주하지 않도록 함
                String wmClass = window.getClassName();
                windowSignature.append(wmClass).append('(')
                        .append(window.getContent().width).append('x')
                        .append(window.getContent().height).append(") ");
            }
        }

        for (Window child : window.getChildren()) {
            collectRenderableWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    private void removeRenderableWindow(Window window) {
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).content == window.getContent()) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        xServerView.requestRender();
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public boolean isScreenOffsetYRelativeToCursor() {
        return screenOffsetYRelativeToCursor;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        xServerView.requestRender();
    }

    public String getForceFullscreenWMClass() {
        return forceFullscreenWMClass;
    }

    public void setBorderless(boolean borderless) {
        this.borderless = borderless;
    }

    public void setForceFullscreenWMClass(String forceFullscreenWMClass) {
        this.forceFullscreenWMClass = forceFullscreenWMClass;
    }

    public String[] getUnviewableWMClasses() {
        return unviewableWMClasses;
    }

    public void setUnviewableWMClasses(String... unviewableWMNames) {
        this.unviewableWMClasses = unviewableWMNames;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        xServerView.requestRender();
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }

    public boolean isViewportNeedsUpdate() {
        return viewportNeedsUpdate;
    }

    public void setViewportNeedsUpdate(boolean viewportNeedsUpdate) {
        this.viewportNeedsUpdate = viewportNeedsUpdate;
    }

    public VertexAttribute getQuadVertices() {
        return quadVertices;
    }

    public EffectComposer getEffectComposer (){
        return effectComposer;
    }


}
