package com.codex.mocklocation;

/**
 * 纯数学工具类（不依赖 Android），方便在桌面 JVM 上直接做单元测试。
 */
public final class Geo {

    /** 地球平均半径（米） */
    public static final double EARTH_R = 6371008.8;
    public static final double DEG = Math.PI / 180.0;

    // ---- GCJ-02（火星坐标）转换：高德/腾讯等国内地图使用，与 WGS-84 相差 300~600 米 ----
    private static final double GCJ_A = 6378245.0;
    private static final double GCJ_EE = 0.00669342162296594323;

    private Geo() {
    }

    public static double wrap360(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    public static double wrap180(double deg) {
        double d = (deg + 180.0) % 360.0;
        if (d < 0) {
            d += 360.0;
        }
        return d - 180.0;
    }

    /**
     * 从起点沿指定方位角前进指定距离后的坐标。
     *
     * @return {纬度, 经度}
     */
    public static double[] destination(double lat, double lon, double distMeters, double bearingDeg) {
        double d = distMeters / EARTH_R;
        double brg = bearingDeg * DEG;
        double lat1 = lat * DEG;
        double lon1 = lon * DEG;
        double sinLat2 = Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brg);
        sinLat2 = Math.max(-1.0, Math.min(1.0, sinLat2));
        double lat2 = Math.asin(sinLat2);
        double lon2 = lon1 + Math.atan2(
                Math.sin(brg) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        return new double[]{lat2 / DEG, wrap180(lon2 / DEG)};
    }

    /** 两点距离（米），haversine 公式 */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = (lat2 - lat1) * DEG;
        double dLon = (lon2 - lon1) * DEG;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1 * DEG) * Math.cos(lat2 * DEG) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** 从点1指向点2的方位角（0=正北，顺时针） */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = lat1 * DEG;
        double p2 = lat2 * DEG;
        double dl = (lon2 - lon1) * DEG;
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return wrap360(Math.toDegrees(Math.atan2(y, x)));
    }

    /** 经纬度 -> Web 墨卡托像素坐标（zoom 层的 256 像素瓦片体系） */
    public static double[] lonLatToPixel(double lon, double lat, int zoom) {
        double n = Math.pow(2.0, zoom) * 256.0;
        double x = (lon + 180.0) / 360.0 * n;
        double s = Math.sin(lat * DEG);
        s = Math.max(-0.9999, Math.min(0.9999, s));
        double y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n;
        return new double[]{x, y};
    }

    /** Web 墨卡托像素坐标 -> 经纬度 */
    public static double[] pixelToLonLat(double x, double y, int zoom) {
        double n = Math.pow(2.0, zoom) * 256.0;
        double lon = x / n * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
        return new double[]{lat, wrap180(lon)};
    }

    /** 每像素代表的米数（Web 墨卡托，中心纬度处的近似值） */
    public static double metersPerPixel(double lat, double zoom) {
        return 156543.03392804097 * Math.cos(lat * DEG) / Math.pow(2.0, zoom);
    }

    /** 方位角 -> 中文/字母方向 */
    public static String dirName(double bearing) {
        String[] names = {"正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北"};
        int idx = (int) Math.floor(wrap360(bearing) / 45.0 + 0.5) % 8;
        return names[idx];
    }

    /** WGS-84 -> GCJ-02（在中国境外原样返回） */
    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat * DEG;
        double magic = Math.sin(radLat);
        magic = 1 - GCJ_EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (GCJ_A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    /** GCJ-02 -> WGS-84（迭代逼近，误差 < 1e-7 度） */
    public static double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double wLat = lat;
        double wLon = lon;
        for (int i = 0; i < 5; i++) {
            double[] g = wgs84ToGcj02(wLat, wLon);
            wLat += lat - g[0];
            wLon += lon - g[1];
        }
        return new double[]{wLat, wLon};
    }

    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
