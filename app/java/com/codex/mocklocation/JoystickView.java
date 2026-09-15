package com.codex.mocklocation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 摇杆：方向决定行进方向，离圆心的距离决定力度（0~100% 配速）。
 * 松手后默认保持方向持续移动（自锁），可切换为“松手即停”。
 */
public class JoystickView extends View {

    public interface Listener {
        void onMove(float nx, float ny, float magnitude);

        void onRelease();
    }

    /** 摇杆内实际可拖动的归一化范围（留一点边距给外圈） */
    private static final float TRAVEL = 0.80f;
    private static final float DEAD_ZONE = 0.08f;

    private float nx = 0f;
    private float ny = 0f;
    private boolean touching = false;
    private boolean releaseStop = false;
    private Listener listener;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dirPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        ringPaint.setColor(0xFF30363D);

        basePaint.setColor(0xFF131A22);

        crossPaint.setColor(0xFF223041);
        crossPaint.setStrokeWidth(dp(1));

        knobPaint.setColor(0xFF22C55E);

        knobRingPaint.setColor(0xFFFFFFFF);
        knobRingPaint.setStyle(Paint.Style.STROKE);
        knobRingPaint.setStrokeWidth(dp(3));

        dirPaint.setColor(0x8822C55E);
        dirPaint.setStrokeWidth(dp(6));
        dirPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(0xFF8B949E);
        textPaint.setTextSize(dp(12));
        textPaint.setTextAlign(Paint.Align.CENTER);

        smallPaint.setColor(0xFFE6EDF3);
        smallPaint.setTextSize(dp(13));
        smallPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setReleaseStop(boolean stop) {
        releaseStop = stop;
        if (stop) {
            reset();
        }
        invalidate();
    }

    public boolean isReleaseStop() {
        return releaseStop;
    }

    public float getNx() {
        return nx;
    }

    public float getNy() {
        return ny;
    }

    public float getMagnitude() {
        return Math.min(1f, (float) Math.hypot(nx, ny));
    }

    public void reset() {
        nx = 0f;
        ny = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - dp(12);
        if (r <= 0) {
            return;
        }
        float kr = r * 0.26f;

        canvas.drawCircle(cx, cy, r, basePaint);
        canvas.drawCircle(cx, cy, r, ringPaint);
        canvas.drawCircle(cx, cy, r * 0.62f, crossPaint);
        crossPaint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, r * 0.62f, crossPaint);
        canvas.drawLine(cx - r, cy, cx + r, cy, ringPaint);
        canvas.drawLine(cx, cy - r, cx, cy + r, ringPaint);

        // 方向刻度
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6.0;
            float x1 = cx + (float) Math.sin(a) * r * 0.88f;
            float y1 = cy - (float) Math.cos(a) * r * 0.88f;
            float x2 = cx + (float) Math.sin(a) * r * 0.97f;
            float y2 = cy - (float) Math.cos(a) * r * 0.97f;
            canvas.drawLine(x1, y1, x2, y2, ringPaint);
        }

        // 方位文字
        canvas.drawText("N", cx, cy - r + dp(15), textPaint);
        canvas.drawText("S", cx, cy + r - dp(5), textPaint);
        canvas.drawText("E", cx + r - dp(11), cy + dp(4), textPaint);
        canvas.drawText("W", cx - r + dp(11), cy + dp(4), textPaint);

        float kx = cx + nx * r * TRAVEL;
        float ky = cy + ny * r * TRAVEL;
        float mag = getMagnitude();
        if (mag > DEAD_ZONE) {
            canvas.drawLine(cx, cy, kx, ky, dirPaint);
        }

        int accent = mag > DEAD_ZONE ? 0xFF22C55E : 0xFF2A3542;
        knobPaint.setShader(new RadialGradient(kx, ky, kr * 1.4f,
                new int[]{0xFF3BE07A, accent}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(kx, ky, kr, knobPaint);
        canvas.drawCircle(kx, ky, kr, knobRingPaint);
        knobPaint.setShader(null);

        canvas.drawText(String.format("%d%%", Math.round(mag * 100)), kx, ky + dp(5), smallPaint);
        canvas.drawText(releaseStop ? "松手即停" : "持续移动", cx, cy + r * 0.62f + dp(16), textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - dp(12);
        float travel = Math.max(1f, r * TRAVEL);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                update(event.getX(), event.getY(), cx, cy, travel);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (releaseStop) {
                    nx = 0f;
                    ny = 0f;
                    invalidate();
                    if (listener != null) {
                        listener.onMove(0f, 0f, 0f);
                        listener.onRelease();
                    }
                } else {
                    invalidate();
                    if (listener != null) {
                        listener.onRelease();
                    }
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void update(float x, float y, float cx, float cy, float travel) {
        float dx = (x - cx) / travel;
        float dy = (y - cy) / travel;
        float m = (float) Math.hypot(dx, dy);
        if (m > 1f) {
            dx /= m;
            dy /= m;
            m = 1f;
        }
        if (m < DEAD_ZONE) {
            dx = 0f;
            dy = 0f;
            m = 0f;
        }
        nx = dx;
        ny = dy;
        invalidate();
        if (listener != null) {
            listener.onMove(nx, ny, m);
        }
    }
}
