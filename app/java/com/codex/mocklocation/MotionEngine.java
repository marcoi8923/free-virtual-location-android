package com.codex.mocklocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 位置推算核心：摇杆方向 + 配速（m/s） -> 按时间推进经纬度。
 * 纯 Java 实现，不依赖 Android，可单独测试。
 */
public class MotionEngine {

    public static final int MODE_IDLE = 0;
    public static final int MODE_JOYSTICK = 1;
    public static final int MODE_TARGET = 2;

    /** 每秒推进的轨迹点最小间隔（米） */
    private static final double TRAIL_STEP_M = 4.0;
    private static final int TRAIL_MAX = 1500;

    private double lat = 39.908700;
    private double lon = 116.397500;
    private double pace = 3.0;          // 配速 m/s
    private double jx = 0.0;            // 摇杆向量 X（右为正，范围 -1~1）
    private double jy = 0.0;            // 摇杆向量 Y（下为正，范围 -1~1）
    private double bearing = 0.0;       // 当前方位角
    private double speed = 0.0;         // 当前速度 m/s
    private int mode = MODE_IDLE;
    private double targetLat = 0.0;
    private double targetLon = 0.0;
    private double movedMeters = 0.0;
    private long runMillis = 0L;

    private final List<double[]> trail = new ArrayList<double[]>();
    private double lastTrailLat = 0.0;
    private double lastTrailLon = 0.0;
    private boolean trailStarted = false;

    public synchronized void setPosition(double lat, double lon) {
        this.lat = clampLat(lat);
        this.lon = Geo.wrap180(lon);
        this.speed = 0.0;
        this.mode = MODE_IDLE;
        pushTrail(true);
    }

    public synchronized void setPace(double pace) {
        this.pace = Math.max(0.05, Math.min(600.0, pace));
    }

    public synchronized double getPace() {
        return pace;
    }

    /** 摇杆输入：nx/ny 为 -1~1 的归一化向量，长度代表力度 */
    public synchronized void setJoystick(double nx, double ny) {
        double m = Math.hypot(nx, ny);
        if (m > 1.0) {
            nx /= m;
            ny /= m;
        }
        this.jx = nx;
        this.jy = ny;
        if (Math.hypot(nx, ny) > 0.08) {
            this.mode = MODE_JOYSTICK;
        } else if (this.mode == MODE_JOYSTICK) {
            this.speed = 0.0;
            this.mode = MODE_IDLE;
        }
    }

    /** 摇杆回中（松手即停模式） */
    public synchronized void releaseJoystick() {
        this.jx = 0.0;
        this.jy = 0.0;
        if (this.mode == MODE_JOYSTICK) {
            this.mode = MODE_IDLE;
        }
        this.speed = 0.0;
    }

    public synchronized void setTarget(double lat, double lon) {
        this.targetLat = clampLat(lat);
        this.targetLon = Geo.wrap180(lon);
        this.mode = MODE_TARGET;
    }

    public synchronized void clearTarget() {
        if (this.mode == MODE_TARGET) {
            this.mode = MODE_IDLE;
            this.speed = 0.0;
        }
    }

    public synchronized void resetCounters() {
        movedMeters = 0.0;
        runMillis = 0L;
    }

    /** 按 dt 秒推进一次位置 */
    public synchronized void tick(double dt) {
        if (dt <= 0) {
            return;
        }
        if (dt > 5.0) {
            dt = 5.0;
        }
        runMillis += (long) (dt * 1000);

        if (mode == MODE_TARGET) {
            double dist = Geo.distance(lat, lon, targetLat, targetLon);
            if (dist < 0.5) {
                lat = targetLat;
                lon = targetLon;
                speed = 0.0;
                mode = MODE_IDLE;
            } else {
                bearing = Geo.bearing(lat, lon, targetLat, targetLon);
                double step = pace * dt;
                if (step >= dist) {
                    lat = targetLat;
                    lon = targetLon;
                    movedMeters += dist;
                    speed = 0.0;
                    mode = MODE_IDLE;
                } else {
                    double[] p = Geo.destination(lat, lon, step, bearing);
                    lat = p[0];
                    lon = p[1];
                    movedMeters += step;
                    speed = pace;
                }
            }
        } else if (mode == MODE_JOYSTICK) {
            double mag = Math.min(1.0, Math.hypot(jx, jy));
            if (mag > 0.08) {
                bearing = Geo.wrap360(Math.toDegrees(Math.atan2(jx, -jy)));
                speed = pace * mag;
                double step = speed * dt;
                double[] p = Geo.destination(lat, lon, step, bearing);
                lat = p[0];
                lon = p[1];
                movedMeters += step;
            } else {
                speed = 0.0;
            }
        } else {
            speed = 0.0;
        }
        pushTrail(false);
    }

    private void pushTrail(boolean force) {
        if (!trailStarted) {
            trailStarted = true;
            lastTrailLat = lat;
            lastTrailLon = lon;
            trail.add(new double[]{lat, lon});
            return;
        }
        if (force || Geo.distance(lastTrailLat, lastTrailLon, lat, lon) >= TRAIL_STEP_M) {
            lastTrailLat = lat;
            lastTrailLon = lon;
            trail.add(new double[]{lat, lon});
            while (trail.size() > TRAIL_MAX) {
                trail.remove(0);
            }
        }
    }

    public synchronized List<double[]> snapshotTrail() {
        return new ArrayList<double[]>(trail);
    }

    public synchronized void clearTrail() {
        trail.clear();
        trailStarted = false;
        pushTrail(true);
    }

    public synchronized double getLat() {
        return lat;
    }

    public synchronized double getLon() {
        return lon;
    }

    public synchronized double getSpeed() {
        return speed;
    }

    public synchronized double getBearing() {
        return bearing;
    }

    public synchronized int getMode() {
        return mode;
    }

    public synchronized double getMovedMeters() {
        return movedMeters;
    }

    public synchronized long getRunMillis() {
        return runMillis;
    }

    public synchronized double getEffectiveSpeed() {
        if (mode == MODE_JOYSTICK) {
            return pace * Math.min(1.0, Math.hypot(jx, jy));
        }
        return speed;
    }

    private static double clampLat(double lat) {
        return Math.max(-85.0, Math.min(85.0, lat));
    }
}
