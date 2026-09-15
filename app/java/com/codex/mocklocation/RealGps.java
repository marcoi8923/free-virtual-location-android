package com.codex.mocklocation;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 手机真实定位（GPS / 网络）读取器。
 *
 * - 全局单例，界面和服务共用一份状态，避免重复订阅 GPS 浪费电量；
 * - 没有任何监听者时自动停止定位；
 * - 同时订阅 GPS 与网络定位，谁先给结果就用谁，并记录精度与来源。
 */
public class RealGps {

    public interface Listener {
        void onRealGpsChanged();
    }

    /** 最近的定位结果（全局共享） */
    public static volatile boolean hasFix = false;
    public static volatile double lat = 0.0;
    public static volatile double lon = 0.0;
    public static volatile float accuracy = 0f;
    public static volatile long fixTime = 0L;
    public static volatile long fixElapsed = 0L;
    public static volatile String provider = "";
    public static volatile String status = "未开启";

    private static RealGps INSTANCE;

    public static synchronized RealGps get(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new RealGps(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    public static synchronized boolean isStarted() {
        return INSTANCE != null && INSTANCE.tracking;
    }

    /** 系统定位开关是否打开 */
    public static boolean locationEnabled(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 距今多少秒，界面用来显示"3 秒前" */
    public static long ageSeconds() {
        if (!hasFix) {
            return -1;
        }
        return (SystemClock.elapsedRealtime() - fixElapsed) / 1000L;
    }

    public static Location snapshot() {
        if (!hasFix) {
            return null;
        }
        Location l = new Location(provider.length() > 0 ? provider : "gps");
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAccuracy(accuracy);
        l.setTime(fixTime);
        return l;
    }

    public static String describe() {
        if (!hasFix) {
            return "真实定位：" + status;
        }
        long age = ageSeconds();
        return String.format(Locale.US, "真实定位：%.6f, %.6f  ±%.0fm · %s · %d 秒前",
                lat, lon, accuracy, provider.length() > 0 ? provider : "定位", age);
    }

    private final Context ctx;
    private final LocationManager lm;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final LocationListener impl;
    private volatile boolean tracking = false;

    private RealGps(Context ctx) {
        this.ctx = ctx;
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        this.impl = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                handleFix(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
                if (!tracking && !listeners.isEmpty()) {
                    startIfNeeded();
                }
            }

            @Override
            public void onProviderDisabled(String provider) {
                status = "手机定位开关已关闭";
                notifyAllListeners();
            }
        };
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
        startIfNeeded();
        l.onRealGpsChanged();
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) {
            stopUpdates();
        }
    }

    /** 手动重新开始（例如用户刚打开系统定位开关） */
    public void restart() {
        stopUpdates();
        if (!listeners.isEmpty()) {
            startIfNeeded();
        }
    }

    private void startIfNeeded() {
        if (tracking) {
            return;
        }
        if (!hasPermission()) {
            status = "没有定位权限";
            notifyAllListeners();
            return;
        }
        boolean gps = false;
        boolean net = false;
        try {
            gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Throwable ignored) {
        }
        try {
            net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable ignored) {
        }
        if (!gps && !net) {
            status = "系统定位开关未打开";
            notifyAllListeners();
            return;
        }
        // 先用系统缓存的最后位置顶上，界面立刻有值，再等实时定位刷新
        if (!hasFix) {
            try {
                Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last == null) {
                    last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (last == null) {
                    last = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                }
                if (last != null) {
                    handleFix(last);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            if (gps) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, impl, Looper.getMainLooper());
            }
            if (net) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 0f, impl, Looper.getMainLooper());
            }
            tracking = true;
            status = "正在定位…";
        } catch (Throwable t) {
            status = "定位启动失败";
        }
        notifyAllListeners();
    }

    private void stopUpdates() {
        if (!tracking) {
            return;
        }
        try {
            lm.removeUpdates(impl);
        } catch (Throwable ignored) {
        }
        tracking = false;
        if (!hasFix) {
            status = "未开启";
        }
        notifyAllListeners();
    }

    private void handleFix(Location location) {
        if (location == null) {
            return;
        }
        lat = location.getLatitude();
        lon = location.getLongitude();
        accuracy = location.getAccuracy();
        fixTime = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
        fixElapsed = SystemClock.elapsedRealtime();
        String p = location.getProvider();
        provider = p == null ? "" : (p.startsWith("gps") ? "GPS" : "网络");
        hasFix = true;
        status = String.format(Locale.US, "%.6f, %.6f  ±%.0fm · %s", lat, lon, accuracy, provider);
        notifyAllListeners();
    }

    private boolean hasPermission() {
        try {
            return ctx.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.os.Process.myPid(), android.os.Process.myUid())
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void notifyAllListeners() {
        for (Listener l : listeners) {
            try {
                l.onRealGpsChanged();
            } catch (Throwable ignored) {
            }
        }
    }
}
