package com.codex.mocklocation;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 轻量偏好存储：上次位置、配速、缩放、收藏点 */
public final class Prefs {

    private static final String NAME = "mockloc";
    private static final String K_LAT = "lat";
    private static final String K_LON = "lon";
    private static final String K_PACE = "pace";
    private static final String K_ZOOM = "zoom";
    private static final String K_RELEASE = "release_stop";
    private static final String K_FAV = "favorites";
    private static final String K_GUIDE = "guide_confirmed";
    private static final String K_OVERLAY = "overlay_enabled";
    private static final String K_OVERLAY_WANT = "overlay_wanted";
    private static final String K_OVERLAY_X = "overlay_x";
    private static final String K_OVERLAY_Y = "overlay_y";
    private static final String K_POS_INIT = "position_initialized";
    private static final String K_FOLLOW = "follow_real";
    private static final String K_MAP_SOURCE = "map_source";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static double getLat(Context c) {
        return Double.longBitsToDouble(sp(c).getLong(K_LAT, Double.doubleToLongBits(39.908700)));
    }

    public static double getLon(Context c) {
        return Double.longBitsToDouble(sp(c).getLong(K_LON, Double.doubleToLongBits(116.397500)));
    }

    public static double getPace(Context c) {
        return Double.longBitsToDouble(sp(c).getLong(K_PACE, Double.doubleToLongBits(3.0)));
    }

    public static double getZoom(Context c) {
        return Double.longBitsToDouble(sp(c).getLong(K_ZOOM, Double.doubleToLongBits(16.0)));
    }

    public static boolean getReleaseStop(Context c) {
        return sp(c).getBoolean(K_RELEASE, false);
    }

    public static boolean isGuideConfirmed(Context c) {
        return sp(c).getBoolean(K_GUIDE, false);
    }

    public static void setGuideConfirmed(Context c, boolean v) {
        sp(c).edit().putBoolean(K_GUIDE, v).apply();
    }

    public static boolean isOverlayEnabled(Context c) {
        return sp(c).getBoolean(K_OVERLAY, false);
    }

    public static void setOverlayEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_OVERLAY, v).apply();
    }

    /** 是否正等待用户授予悬浮窗权限 */
    public static boolean isOverlayWanted(Context c) {
        return sp(c).getBoolean(K_OVERLAY_WANT, false);
    }

    public static void setOverlayWanted(Context c, boolean v) {
        sp(c).edit().putBoolean(K_OVERLAY_WANT, v).apply();
    }

    public static int getOverlayX(Context c) {
        return sp(c).getInt(K_OVERLAY_X, 0);
    }

    public static int getOverlayY(Context c) {
        return sp(c).getInt(K_OVERLAY_Y, 600);
    }

    public static void saveOverlayPos(Context c, int x, int y) {
        sp(c).edit().putInt(K_OVERLAY_X, x).putInt(K_OVERLAY_Y, y).apply();
    }

    /** 是否已经用手机真实定位初始化过起点 */
    public static boolean isPositionInitialized(Context c) {
        return sp(c).getBoolean(K_POS_INIT, false);
    }

    public static void setPositionInitialized(Context c, boolean v) {
        sp(c).edit().putBoolean(K_POS_INIT, v).apply();
    }

    /** 是否让模拟位置跟随手机真实定位 */
    public static boolean isFollowReal(Context c) {
        return sp(c).getBoolean(K_FOLLOW, false);
    }

    public static void setFollowReal(Context c, boolean v) {
        sp(c).edit().putBoolean(K_FOLLOW, v).apply();
    }

    /** 地图图源：-1 自动，0..n 指定图源下标 */
    public static int getMapSource(Context c) {
        return sp(c).getInt(K_MAP_SOURCE, TileLoader.MODE_AUTO);
    }

    public static void setMapSource(Context c, int v) {
        sp(c).edit().putInt(K_MAP_SOURCE, v).apply();
    }

    public static void save(Context c, double lat, double lon, double pace, double zoom, boolean releaseStop) {
        sp(c).edit()
                .putLong(K_LAT, Double.doubleToLongBits(lat))
                .putLong(K_LON, Double.doubleToLongBits(lon))
                .putLong(K_PACE, Double.doubleToLongBits(pace))
                .putLong(K_ZOOM, Double.doubleToLongBits(zoom))
                .putBoolean(K_RELEASE, releaseStop)
                .apply();
    }

    /** 收藏点格式：名称|纬度|经度，多行存储 */
    public static List<double[]> getFavorites(Context c) {
        List<double[]> out = new ArrayList<double[]>();
        String raw = sp(c).getString(K_FAV, "");
        if (raw == null || raw.length() == 0) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length == 3) {
                try {
                    out.add(new double[]{Double.parseDouble(parts[1]), Double.parseDouble(parts[2])});
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    public static List<String> getFavoriteNames(Context c) {
        List<String> out = new ArrayList<String>();
        String raw = sp(c).getString(K_FAV, "");
        if (raw == null || raw.length() == 0) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length == 3) {
                out.add(parts[0]);
            }
        }
        return out;
    }

    public static void addFavorite(Context c, String name, double lat, double lon) {
        String safe = name.replace("|", " ").replace("\n", " ").trim();
        if (safe.length() == 0) {
            safe = "未命名";
        }
        String raw = sp(c).getString(K_FAV, "");
        String line = safe + "|" + lat + "|" + lon;
        String next = (raw == null || raw.length() == 0) ? line : raw + "\n" + line;
        sp(c).edit().putString(K_FAV, next).apply();
    }

    public static void removeFavorite(Context c, int index) {
        List<String> lines = new ArrayList<String>();
        String raw = sp(c).getString(K_FAV, "");
        if (raw != null && raw.length() > 0) {
            for (String l : raw.split("\n")) {
                if (l.trim().length() > 0) {
                    lines.add(l);
                }
            }
        }
        if (index >= 0 && index < lines.size()) {
            lines.remove(index);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        sp(c).edit().putString(K_FAV, sb.toString()).apply();
    }

    public static void clearFavorites(Context c) {
        sp(c).edit().putString(K_FAV, "").apply();
    }
}
