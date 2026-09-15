package com.codex.mocklocation;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * 悬浮摇杆：以系统级悬浮窗显示，可跨应用使用。
 * 拖动顶部横条移动位置，松手自动吸附到最近的左右边缘。
 */
public class OverlayJoystick {

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View panel;
    private WindowManager.LayoutParams lp;
    private JoystickView joystick;
    private TextView tvSpeed;
    private boolean showing;
    private boolean tickerRunning;

    private float downRawX, downRawY;
    private int startX, startY;
    private boolean dragging;

    public OverlayJoystick(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return showing;
    }

    public static boolean canDraw(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) {
            return true; // 6.0 以下安装时即授予
        }
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    public void show() {
        if (showing || !canDraw(ctx)) {
            return;
        }
        try {
            panel = LayoutInflater.from(ctx).inflate(R.layout.overlay_joystick, null);
        } catch (Throwable t) {
            AppState.lastError = "悬浮窗创建失败：" + t;
            return;
        }

        joystick = (JoystickView) panel.findViewById(R.id.ovJoystick);
        tvSpeed = (TextView) panel.findViewById(R.id.ovSpeed);
        joystick.setReleaseStop(Prefs.getReleaseStop(ctx));
        joystick.setListener(new JoystickView.Listener() {
            @Override
            public void onMove(float nx, float ny, float magnitude) {
                AppState.engine.setJoystick(nx, ny);
            }

            @Override
            public void onRelease() {
                if (joystick.isReleaseStop()) {
                    AppState.engine.releaseJoystick();
                }
            }
        });

        View dragArea = panel.findViewById(R.id.ovDragArea);
        dragArea.setOnTouchListener(dragListener);
        panel.setOnTouchListener(dragListener);
        panel.findViewById(R.id.ovClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setOverlayEnabled(ctx, false);
                hide();
            }
        });

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = clampX(Prefs.getOverlayX(ctx), 0);
        lp.y = clampY(Prefs.getOverlayY(ctx), 0);

        try {
            wm.addView(panel, lp);
        } catch (Throwable t) {
            AppState.lastError = "悬浮窗添加失败：" + t;
            panel = null;
            return;
        }
        panel.post(new Runnable() {
            @Override
            public void run() {
                if (panel != null) {
                    lp.x = clampX(lp.x, panel.getWidth());
                    lp.y = clampY(lp.y, panel.getHeight());
                    try {
                        wm.updateViewLayout(panel, lp);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        showing = true;
        AppState.overlayActive = true;
        startTicker();
    }

    public void hide() {
        stopTicker();
        if (panel != null) {
            try {
                wm.removeView(panel);
            } catch (Throwable ignored) {
            }
        }
        panel = null;
        joystick = null;
        tvSpeed = null;
        showing = false;
        AppState.overlayActive = false;
    }

    // ---------------- 拖动 ----------------

    private final View.OnTouchListener dragListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (lp == null || panel == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (event.getRawX() - downRawX);
                    int dy = (int) (event.getRawY() - downRawY);
                    if (Math.abs(dx) + Math.abs(dy) > dp(6)) {
                        dragging = true;
                    }
                    lp.x = clampX(startX + dx, panel.getWidth());
                    lp.y = clampY(startY + dy, panel.getHeight());
                    try {
                        wm.updateViewLayout(panel, lp);
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        snapToEdge();
                        Prefs.saveOverlayPos(ctx, lp.x, lp.y);
                    }
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    };

    private void snapToEdge() {
        if (panel == null || lp == null) {
            return;
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        final int right = Math.max(0, dm.widthPixels - panel.getWidth());
        final int target = (lp.x + panel.getWidth() / 2 < dm.widthPixels / 2) ? 0 : right;
        final int from = lp.x;
        if (from == target) {
            return;
        }
        try {
            ValueAnimator anim = ValueAnimator.ofInt(from, target);
            anim.setDuration(180);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (panel == null || lp == null) {
                        return;
                    }
                    lp.x = (Integer) animation.getAnimatedValue();
                    try {
                        wm.updateViewLayout(panel, lp);
                    } catch (Throwable ignored) {
                    }
                }
            });
            anim.start();
        } catch (Throwable t) {
            lp.x = target;
            try {
                wm.updateViewLayout(panel, lp);
            } catch (Throwable ignored) {
            }
        }
    }

    private int clampX(int x, int panelW) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int fallbackW = (int) dp(176);
        int max = Math.max(0, dm.widthPixels - (panelW > 0 ? panelW : fallbackW));
        return Math.max(0, Math.min(max, x));
    }

    private int clampY(int y, int panelH) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int fallbackH = (int) dp(230);
        int max = Math.max(0, dm.heightPixels - (panelH > 0 ? panelH : fallbackH));
        return Math.max(0, Math.min(max, y));
    }

    private float dp(float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    // ---------------- 速度刷新 ----------------

    private void startTicker() {
        if (tickerRunning) {
            return;
        }
        tickerRunning = true;
        ui.post(tickRunnable);
    }

    private void stopTicker() {
        tickerRunning = false;
        ui.removeCallbacks(tickRunnable);
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!tickerRunning) {
                return;
            }
            if (tvSpeed != null) {
                double sp = AppState.engine.getEffectiveSpeed();
                tvSpeed.setText(String.format(Locale.US, "%.2f m/s · %.1f km/h", sp, sp * 3.6));
            }
            ui.postDelayed(this, 500);
        }
    };

    /** 服务启动时按用户上次的选择自动恢复悬浮窗 */
    public static void restoreIfEnabled(Context ctx, OverlayJoystick overlay) {
        if (Prefs.isOverlayEnabled(ctx) && canDraw(ctx)) {
            overlay.show();
        }
    }
}
