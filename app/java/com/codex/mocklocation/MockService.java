package com.codex.mocklocation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 前台服务：把 MotionEngine 推算出的坐标持续写入系统“模拟位置提供者”，
 * 这样其它 App 读取定位时拿到的就是我们设定的位置。
 */
public class MockService extends Service {

    public static final String ACTION_START = "com.codex.mocklocation.action.START";
    public static final String ACTION_STOP = "com.codex.mocklocation.action.STOP";
    public static final String ACTION_SHOW_OVERLAY = "com.codex.mocklocation.action.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.codex.mocklocation.action.HIDE_OVERLAY";

    private static final String CHANNEL_ID = "mock_location";
    private static final int NOTIF_ID = 0x1701;
    private static final long TICK_MS = 200L;

    private final List<String> providers = new ArrayList<String>();
    private LocationManager lm;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean running = false;
    private Thread worker;
    private long lastNotify = 0L;
    private OverlayJoystick overlay;
    private RealGps.Listener followListener;
    private boolean followRegistered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_HIDE_OVERLAY.equals(intent.getAction())) {
            if (overlay != null) {
                overlay.hide();
            }
            return START_STICKY;
        }
        startForegroundSafely();
        if (!running) {
            running = true;
            AppState.running = true;
            setupProviders();
            startWorker();
            acquireWakeLock();
        }
        syncFollow();
        if (intent != null && ACTION_SHOW_OVERLAY.equals(intent.getAction())) {
            showOverlay();
        } else if (Prefs.isOverlayEnabled(this)) {
            // 服务启动时按用户上次的选择恢复悬浮摇杆
            showOverlay();
        }
        return START_STICKY;
    }

    private void showOverlay() {
        showOverlayInternal();
    }

    /**
     * 跟随模式：把手机真实定位同步成模拟位置（服务在跑时也能后台跟随）。
     */
    private void syncFollow() {
        boolean want = Prefs.isFollowReal(this);
        if (want == followRegistered) {
            return;
        }
        if (followListener == null) {
            followListener = new RealGps.Listener() {
                @Override
                public void onRealGpsChanged() {
                    if (!RealGps.hasFix) {
                        return;
                    }
                    if (AppState.engine.getMode() == MotionEngine.MODE_IDLE) {
                        AppState.engine.setPosition(RealGps.lat, RealGps.lon);
                    }
                }
            };
        }
        if (want) {
            RealGps.get(this).addListener(followListener);
        } else {
            RealGps.get(this).removeListener(followListener);
        }
        followRegistered = want;
    }

    private void showOverlayInternal() {
        if (overlay == null) {
            overlay = new OverlayJoystick(this);
        }
        if (OverlayJoystick.canDraw(this)) {
            overlay.show();
        } else {
            AppState.lastError = "没有「显示在其他应用上层」权限，悬浮摇杆无法显示。";
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        AppState.running = false;
        AppState.mockReady = false;
        if (overlay != null) {
            overlay.hide();
            overlay = null;
        }
        if (followRegistered && followListener != null) {
            RealGps.get(this).removeListener(followListener);
            followRegistered = false;
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        for (String p : new ArrayList<String>(providers)) {
            try {
                lm.setTestProviderEnabled(p, false);
            } catch (Throwable ignored) {
            }
            try {
                lm.removeTestProvider(p);
            } catch (Throwable ignored) {
            }
        }
        providers.clear();
        releaseWakeLock();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ---------------- 模拟位置提供者 ----------------

    private void setupProviders() {
        if (providers.size() > 0) {
            return;
        }
        String[] names = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        boolean denied = false;
        for (String name : names) {
            try {
                lm.addTestProvider(name, false, false, false, false, true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                lm.setTestProviderEnabled(name, true);
                providers.add(name);
            } catch (SecurityException se) {
                denied = true;
            } catch (IllegalArgumentException iae) {
                // 已经存在同名测试提供者，直接启用
                try {
                    lm.setTestProviderEnabled(name, true);
                    providers.add(name);
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                denied = true;
            }
        }
        if (providers.isEmpty()) {
            AppState.mockReady = false;
            AppState.lastError = denied
                    ? "系统拒绝了模拟位置：请到 开发者选项 → 选择模拟位置信息应用 里选中本应用，然后重新点“开始模拟”。"
                    : "无法创建模拟位置提供者，请确认已开启手机定位。";
        } else {
            AppState.mockReady = true;
            AppState.lastError = "";
        }
    }

    private void pushLocation() {
        double lat = AppState.engine.getLat();
        double lon = AppState.engine.getLon();
        double speed = AppState.engine.getSpeed();
        double bearing = AppState.engine.getBearing();
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        long nowMillis = System.currentTimeMillis();

        for (String p : providers) {
            try {
                Location loc = new Location(p);
                loc.setLatitude(lat);
                loc.setLongitude(lon);
                loc.setAccuracy(3.0f);
                loc.setAltitude(50.0);
                loc.setSpeed((float) speed);
                loc.setBearing((float) bearing);
                loc.setTime(nowMillis);
                loc.setElapsedRealtimeNanos(nowNanos);
                if (Build.VERSION.SDK_INT >= 26) {
                    loc.setVerticalAccuracyMeters(3.0f);
                    loc.setSpeedAccuracyMetersPerSecond(0.3f);
                    loc.setBearingAccuracyDegrees(2.0f);
                }
                lm.setTestProviderLocation(p, loc);
            } catch (SecurityException se) {
                AppState.mockReady = false;
                AppState.lastError = "系统拒绝了模拟位置：请到 开发者选项 → 选择模拟位置信息应用 里选中本应用。";
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 主循环 ----------------

    private void startWorker() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                long last = SystemClock.elapsedRealtime();
                long lastRetry = 0L;
                long lastFollowCheck = 0L;
                while (running) {
                    long now = SystemClock.elapsedRealtime();
                    double dt = (now - last) / 1000.0;
                    last = now;
                    if (dt <= 0) {
                        dt = TICK_MS / 1000.0;
                    }
                    AppState.engine.tick(dt);
                    pushLocation();

                    // 没拿到提供者时每秒重试一次（用户可能刚在开发者选项里授权）
                    if (providers.isEmpty() && now - lastRetry > 1000L) {
                        lastRetry = now;
                        setupProviders();
                    }
                    if (now - lastNotify > 5000L) {
                        lastNotify = now;
                        updateNotification();
                    }
                    if (now - lastFollowCheck > 1000L) {
                        lastFollowCheck = now;
                        syncFollow();
                    }
                    try {
                        Thread.sleep(TICK_MS);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }, "mock-location-worker");
        worker.setDaemon(true);
        worker.start();
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "模拟定位",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("模拟定位运行状态");
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 1, open, piFlags);

        Intent stop = new Intent(this, MockService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return b.build();
    }

    private void startForegroundSafely() {
        Notification n = buildNotification("模拟定位运行中", "正在写入模拟位置");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable t2) {
                AppState.lastError = "前台服务启动失败：" + t2;
            }
        }
    }

    private void updateNotification() {
        if (nm == null) {
            return;
        }
        String text = String.format(Locale.US, "%.5f, %.5f · %.1f m/s",
                AppState.engine.getLat(), AppState.engine.getLon(), AppState.engine.getEffectiveSpeed());
        try {
            nm.notify(NOTIF_ID, buildNotification("模拟定位运行中", text));
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 电源 ----------------

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mockloc:run");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }
}
