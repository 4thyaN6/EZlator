package com.winlator.cmod.widget;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

// Long-press feedback for shortcut tiles: a line runs around the
// tile's rounded border while the finger is held; when it has gone all the
// way round, the menu opens. progress 0..1 = share of the border drawn,
// starting at the top centre and going clockwise.
// 숏컷 타일의 길게 누르기 표시임: 손가락을 누르고 있는 동안 타일의 둥근
// 테두리를 따라 선이 그려지고, 한 바퀴를 다 돌면 메뉴가 열림. progress 0..1 =
// 그려진 테두리 비율이며 위쪽 가운데에서 시계 방향으로 시작함
public class BorderTraceDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final float inset;
    private final Path full = new Path();
    private final Path part = new Path();
    private PathMeasure measure;
    private float progress = 0f;

    public BorderTraceDrawable(int color, float strokePx, float radiusPx) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(strokePx);
        paint.setColor(color);
        this.radius = radiusPx;
        this.inset = strokePx / 2f;
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        super.onBoundsChange(b);
        full.reset();
        RectF r = new RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float cx = r.centerX();
        // Start at the top centre, clockwise / 위쪽 가운데에서 시계 방향으로 시작
        full.moveTo(cx, r.top);
        full.lineTo(r.right - radius, r.top);
        full.arcTo(new RectF(r.right - 2 * radius, r.top, r.right, r.top + 2 * radius), -90, 90);
        full.lineTo(r.right, r.bottom - radius);
        full.arcTo(new RectF(r.right - 2 * radius, r.bottom - 2 * radius, r.right, r.bottom), 0, 90);
        full.lineTo(r.left + radius, r.bottom);
        full.arcTo(new RectF(r.left, r.bottom - 2 * radius, r.left + 2 * radius, r.bottom), 90, 90);
        full.lineTo(r.left, r.top + radius);
        full.arcTo(new RectF(r.left, r.top, r.left + 2 * radius, r.top + 2 * radius), 180, 90);
        full.lineTo(cx, r.top);
        measure = new PathMeasure(full, false);
    }

    @Override
    public void draw(Canvas canvas) {
        if (progress <= 0f || measure == null) return;
        part.reset();
        measure.getSegment(0, measure.getLength() * progress, part, true);
        canvas.drawPath(part, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
