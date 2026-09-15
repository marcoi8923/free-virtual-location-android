import com.codex.mocklocation.Geo;
import com.codex.mocklocation.MotionEngine;

import java.util.List;
import java.util.Locale;

/** 桌面 JVM 单元测试：验证几何计算与摇杆/配速推算逻辑是否正确 */
public class EngineTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testDistance();
        testBearing();
        testJoystickPace();
        testJoystickMagnitude();
        testTargetMode();
        testTrail();
        testMercatorRoundTrip();
        testPaceClamp();
        testGcjOffset();

        System.out.println("----------------------------------------");
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testDistance() {
        double d = Geo.distance(0, 0, 0, 0.001);
        check("1 毫度经度 ≈ 111.2 m", Math.abs(d - 111.19) < 0.5, d);
        double d2 = Geo.distance(39.9087, 116.3975, 39.9087, 116.3975);
        check("同点距离为 0", d2 == 0.0, d2);
    }

    private static void testBearing() {
        check("正北 = 0°", Math.abs(Geo.bearing(0, 0, 0.01, 0)) < 0.5, Geo.bearing(0, 0, 0.01, 0));
        check("正东 = 90°", Math.abs(Geo.bearing(0, 0, 0, 0.01) - 90) < 0.5, Geo.bearing(0, 0, 0, 0.01));
        check("正南 = 180°", Math.abs(Geo.bearing(0, 0, -0.01, 0) - 180) < 0.5, Geo.bearing(0, 0, -0.01, 0));
    }

    /** 摇杆朝北、配速 3 m/s，10 秒应前进约 30 米 */
    private static void testJoystickPace() {
        MotionEngine e = new MotionEngine();
        e.setPosition(30.0, 120.0);
        e.setPace(3.0);
        e.setJoystick(0f, -1f);
        for (int i = 0; i < 100; i++) {
            e.tick(0.1);
        }
        double moved = e.getMovedMeters();
        double realDist = Geo.distance(30.0, 120.0, e.getLat(), e.getLon());
        check("10 秒 @3m/s ≈ 30 m", Math.abs(moved - 30.0) < 0.5, moved);
        check("位移与里程一致", Math.abs(realDist - 30.0) < 0.5, realDist);
        check("向北移动，纬度增大", e.getLat() > 30.0, e.getLat());
        check("方向约为 0°", Geo.wrap180(e.getBearing() - 0) < 1.0, e.getBearing());
    }

    /** 摇杆力度 = 配速百分比 */
    private static void testJoystickMagnitude() {
        MotionEngine e = new MotionEngine();
        e.setPosition(30.0, 120.0);
        e.setPace(10.0);
        e.setJoystick(0.5f, 0f);
        for (int i = 0; i < 20; i++) {
            e.tick(0.1);
        }
        check("力度 50% → 5 m/s，2 秒 ≈ 10 m", Math.abs(e.getMovedMeters() - 10.0) < 0.3, e.getMovedMeters());
        check("向东移动，经度增大", e.getLon() > 120.0, e.getLon());
        check("方向约为 90°", Math.abs(e.getBearing() - 90) < 1.0, e.getBearing());
    }

    /** 地图点选目标点后，按配速自动前进并停在目标点 */
    private static void testTargetMode() {
        MotionEngine e = new MotionEngine();
        e.setPosition(30.0, 120.0);
        e.setPace(5.0);
        double[] target = Geo.destination(30.0, 120.0, 40.0, 45.0);
        e.setTarget(target[0], target[1]);
        for (int i = 0; i < 100; i++) {
            e.tick(0.1);
            if (e.getMode() == MotionEngine.MODE_IDLE) {
                break;
            }
        }
        double remain = Geo.distance(e.getLat(), e.getLon(), target[0], target[1]);
        check("到达目标点（误差 < 0.6 m）", remain < 0.6, remain);
        check("到达后速度归零", e.getSpeed() == 0.0, e.getSpeed());
        check("里程约为 40 m", Math.abs(e.getMovedMeters() - 40.0) < 1.0, e.getMovedMeters());
    }

    private static void testTrail() {
        MotionEngine e = new MotionEngine();
        e.setPosition(30.0, 120.0);
        e.setPace(20.0);
        e.setJoystick(0f, -1f);
        for (int i = 0; i < 100; i++) {
            e.tick(0.1);
        }
        List<double[]> trail = e.snapshotTrail();
        // 20 m/s × 10 秒 = 200 米；轨迹按“距上一点 ≥4 米”记录，浮点抖动会让间隔在 4~6 米之间
        double minGap = Double.MAX_VALUE;
        double maxGap = 0;
        for (int i = 1; i < trail.size(); i++) {
            double d = Geo.distance(trail.get(i - 1)[0], trail.get(i - 1)[1], trail.get(i)[0], trail.get(i)[1]);
            minGap = Math.min(minGap, d);
            maxGap = Math.max(maxGap, d);
        }
        check("轨迹点数量合理", trail.size() >= 30 && trail.size() <= 60, trail.size());
        check("轨迹点间隔 4~6 米", minGap >= 3.9 && maxGap <= 6.5, minGap + "~" + maxGap);
        double trailLen = 0;
        for (int i = 1; i < trail.size(); i++) {
            trailLen += Geo.distance(trail.get(i - 1)[0], trail.get(i - 1)[1], trail.get(i)[0], trail.get(i)[1]);
        }
        check("轨迹长度 ≈ 里程", Math.abs(trailLen - e.getMovedMeters()) < 6.0, trailLen);
        e.clearTrail();
        check("清空轨迹后剩 1 个点", e.snapshotTrail().size() == 1, e.snapshotTrail().size());
    }

    private static void testMercatorRoundTrip() {
        int zoom = 16;
        double[] px = Geo.lonLatToPixel(116.3975, 39.9087, zoom);
        double[] ll = Geo.pixelToLonLat(px[0], px[1], zoom);
        check("墨卡托投影往返精度", Math.abs(ll[0] - 39.9087) < 1e-6 && Math.abs(ll[1] - 116.3975) < 1e-6,
                ll[0] + "," + ll[1]);
        double mpp = Geo.metersPerPixel(39.9087, zoom);
        check("z16 每像素约 1.5~2.5 m", mpp > 1.0 && mpp < 3.0, mpp);
    }

    private static void testPaceClamp() {
        MotionEngine e = new MotionEngine();
        e.setPace(0.0);
        check("配速下限 0.05", Math.abs(e.getPace() - 0.05) < 1e-9, e.getPace());
        e.setPace(9999);
        check("配速上限 600", Math.abs(e.getPace() - 600.0) < 1e-9, e.getPace());
    }

    /** GCJ-02 纠偏（高德等国内地图用）：偏移量、往返一致性、境外不偏移 */
    private static void testGcjOffset() {
        double[] g = Geo.wgs84ToGcj02(39.9087, 116.3975);
        double shift = Geo.distance(39.9087, 116.3975, g[0], g[1]);
        check("北京 GCJ02 偏移 100~800m", shift > 100 && shift < 800, shift);
        double[] w = Geo.gcj02ToWgs84(g[0], g[1]);
        check("GCJ02 往返误差 < 1e-6 度",
                Math.abs(w[0] - 39.9087) < 1e-6 && Math.abs(w[1] - 116.3975) < 1e-6,
                w[0] + "," + w[1]);
        double[] tokyo = Geo.wgs84ToGcj02(35.6762, 139.6503);
        check("境外坐标不偏移", tokyo[0] == 35.6762 && tokyo[1] == 139.6503, tokyo[0] + "," + tokyo[1]);
    }

    private static void check(String name, boolean ok, Object value) {
        if (ok) {
            passed++;
            System.out.println(String.format(Locale.US, "[PASS] %-28s = %s", name, value));
        } else {
            failed++;
            System.out.println(String.format(Locale.US, "[FAIL] %-28s = %s", name, value));
        }
    }
}
