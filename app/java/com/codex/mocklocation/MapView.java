package com.codex.mocklocation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 极简滑动地图：OpenStreetMap 瓦片 + 当前位置/目标点/轨迹绘制。
 * 单指拖动平移、双指捏合缩放、单击设置目标点；瓦片不可用时自动退化为坐标网格。
 */
public class MapView extends View {

    public interface OnMapTapListener {
        void onTap(double lat, double lon);
    }

    private static final double MIN_ZOOM = 3.0;
    private static final double MAX_ZOOM = 19.0;

    private double centerLat = 39.908700;
    private double centerLon = 116.397500;
    private double zoom = 16.0;

    private double posLat = 39.908700;
    private double posLon = 116.397500;
    private float posBearing = 0f;

    private boolean hasTarget = false;
    private double targetLat;
    private double targetLon;

    private boolean hasReal = false;
    private double realLat;
    private double realLon;
    private float realAccuracy = 0f;

    private List<double[]> trail = new ArrayList<double[]>();

    private TileLoader loader;
    private OnMapTapListener tapListener;
    private Bitmap markerBmp;
    /** 复用的临时对象，避免每帧分配 */
    private final RectF tmpDst = new RectF();
    private final Rect tmpSrc = new Rect();
    private float density = 1f;
    /** 轨迹路径缓存（瓦片像素坐标系，平移时无需重算） */
    private Path trailPath;
    private int trailPathZoom = -1;
    private int trailPathSize = -1;
    private boolean trailPathGcj = false;
    /** 当前图源是否为 GCJ-02（高德等），此时要把 WGS-84 坐标纠偏后再投影 */
    private boolean gcjDatum = false;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint();
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint posPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint posRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint offlineGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint offlineRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint realFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint realRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint realAccPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint realAccStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float downX, downY;
    private long downTime;
    private double downCenterPxX, downCenterPxY;
    private boolean dragging;
    private boolean multiTouch;
    private float pinchStartSpan;
    private double pinchStartZoom;

    public MapView(Context context) {
        super(context);
        init();
    }

    public MapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MapView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        loader = new TileLoader(new TileLoader.Listener() {
            @Override
            public void onTileLoaded(String key) {
                postInvalidate();
            }
        });
        gridPaint.setColor(0xFF223041);
        gridPaint.setStrokeWidth(1f);
        placeholderPaint.setColor(0xFF1A212B);
        trailPaint.setColor(0xCC38BDF8);
        trailPaint.setStrokeWidth(5f);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        posPaint.setColor(0xFF22C55E);
        posRingPaint.setColor(Color.WHITE);
        posRingPaint.setStyle(Paint.Style.STROKE);
        posRingPaint.setStrokeWidth(3f);
        targetPaint.setColor(0xFFF59E0B);
        targetPaint.setStyle(Paint.Style.STROKE);
        targetPaint.setStrokeWidth(4f);
        textPaint.setColor(0xFFE6EDF3);
        textPaint.setTextSize(dp(11));
        barPaint.setColor(0xFFE6EDF3);
        barPaint.setStrokeWidth(3f);
        // 离线底图配色：比占位底色亮一些，保证网格和距离圈能看清
        offlineGridPaint.setColor(0xFF2F4A63);
        offlineGridPaint.setStrokeWidth(dp(1.5f));
        offlineRingPaint.setColor(0x7722C55E);
        offlineRingPaint.setStyle(Paint.Style.STROKE);
        offlineRingPaint.setStrokeWidth(dp(2f));
        // 手机真实定位：蓝色圆点 + 精度圈
        realFillPaint.setColor(0xFF3B82F6);
        realRingPaint.setColor(Color.WHITE);
        realRingPaint.setStyle(Paint.Style.STROKE);
        realRingPaint.setStrokeWidth(dp(3f));
        realAccPaint.setColor(0x223B82F6);
        realAccStrokePaint.setColor(0x883B82F6);
        realAccStrokePaint.setStyle(Paint.Style.STROKE);
        realAccStrokePaint.setStrokeWidth(dp(1.5f));
        try {
            markerBmp = BitmapFactory.decodeResource(getResources(), R.drawable.ic_my_location);
        } catch (Throwable t) {
            markerBmp = null;
        }
    }

    private float dp(float v) {
        return v * density;
    }

    /** WGS-84 经纬度 -> 瓦片像素坐标（自动处理 GCJ-02 图源） */
    private double[] pixelOf(double lat, double lon, int z) {
        if (gcjDatum) {
            double[] g = Geo.wgs84ToGcj02(lat, lon);
            return Geo.lonLatToPixel(g[1], g[0], z);
        }
        return Geo.lonLatToPixel(lon, lat, z);
    }

    /** 瓦片像素坐标 -> WGS-84 经纬度（自动处理 GCJ-02 图源） */
    private double[] latLonOf(double pxX, double pxY, int z) {
        double[] ll = Geo.pixelToLonLat(pxX, pxY, z);
        if (gcjDatum) {
            return Geo.gcj02ToWgs84(ll[0], ll[1]);
        }
        return ll;
    }

    // ---------------- 对外接口 ----------------

    public void setOnMapTapListener(OnMapTapListener l) {
        tapListener = l;
    }

    public void setCenter(double lat, double lon) {
        this.centerLat = clampLat(lat);
        this.centerLon = Geo.wrap180(lon);
        invalidate();
    }

    public void setCenterKeepZoom(double lat, double lon) {
        setCenter(lat, lon);
    }

    public double getCenterLat() {
        return centerLat;
    }

    public double getCenterLon() {
        return centerLon;
    }

    public double getZoom() {
        return zoom;
    }

    public void setZoom(double z) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        invalidate();
    }

    public void zoomIn() {
        setZoom(zoom + 1);
    }

    public void zoomOut() {
        setZoom(zoom - 1);
    }

    public void setPosition(double lat, double lon) {
        double la = clampLat(lat);
        double lo = Geo.wrap180(lon);
        // 位置没变化就不重绘，省电（长时间模拟时很关键）
        if (Math.abs(la - posLat) < 1e-7 && Math.abs(lo - posLon) < 1e-7) {
            return;
        }
        posLat = la;
        posLon = lo;
        invalidate();
    }

    public void setBearing(float bearing) {
        if (Math.abs(bearing - posBearing) < 0.3f) {
            return;
        }
        posBearing = bearing;
        invalidate();
    }

    public void setTarget(double lat, double lon) {
        hasTarget = true;
        targetLat = clampLat(lat);
        targetLon = Geo.wrap180(lon);
        invalidate();
    }

    public void clearTarget() {
        hasTarget = false;
        invalidate();
    }

    /** 手机真实定位（蓝色圆点 + 精度圈） */
    public void setRealPosition(double lat, double lon, float accuracyMeters) {
        hasReal = true;
        realLat = clampLat(lat);
        realLon = Geo.wrap180(lon);
        realAccuracy = accuracyMeters;
        invalidate();
    }

    public void clearRealPosition() {
        if (!hasReal) {
            return;
        }
        hasReal = false;
        invalidate();
    }

    public void setTrail(List<double[]> t) {
        trail = t == null ? new ArrayList<double[]>() : t;
        invalidate();
    }

    public boolean isTilesUnavailable() {
        return loader.isAllCoolingDown();
    }

    /** 当前是否完全没有瓦片（用于显示离线网格底图） */
    public boolean hasNoTile() {
        return loader.hasNoTile();
    }

    /** 当前图源在此区域没有数据 */
    public boolean sourceHasNoData() {
        return loader.sourceHasNoData();
    }

    /** 手动重新加载地图瓦片 */
    public void retryTiles() {
        loader.retryAll();
        invalidate();
    }

    /** 切换地图源（-1 自动，或 TileLoader.SOURCES 的下标） */
    public void setMapSource(int mode) {
        loader.setMode(mode);
        trailPath = null;          // 坐标系可能变化，轨迹缓存作废
        invalidate();
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int z = (int) Math.floor(zoom);
        double scale = Math.pow(2.0, zoom - z);
        gcjDatum = loader.isGcj();
        double[] cpx = pixelOf(centerLat, centerLon, z);
        double left = cpx[0] - (w / 2.0) / scale;
        double top = cpx[1] - (h / 2.0) / scale;
        float ts = (float) (256 * scale);

        int x0 = (int) Math.floor(left / 256.0);
        int x1 = (int) Math.floor((left + w / scale) / 256.0);
        int y0 = (int) Math.floor(top / 256.0);
        int y1 = (int) Math.floor((top + h / scale) / 256.0);
        int maxTile = (1 << z) - 1;

        canvas.drawColor(0xFF10151C);

        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                float px = (float) ((tx * 256.0 - left) * scale);
                float py = (float) ((ty * 256.0 - top) * scale);
                tmpDst.set(px, py, px + ts, py + ts);
                if (ty < 0 || ty > maxTile) {
                    canvas.drawRect(tmpDst, placeholderPaint);
                    continue;
                }
                int wx = ((tx % (maxTile + 1)) + (maxTile + 1)) % (maxTile + 1);
                String key = z + "/" + wx + "/" + ty;
                Bitmap bmp = loader.get(key);
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, tmpDst, tilePaint);
                } else {
                    // 先用低一级/多级瓦片放大顶上，拖动时不会出现大片空白（地图"变快"的关键）
                    if (!drawAncestorTile(canvas, z, wx, ty, tmpDst)) {
                        canvas.drawRect(tmpDst, placeholderPaint);
                        canvas.drawLine(px, py, px + ts, py, gridPaint);
                        canvas.drawLine(px, py, px, py + ts, gridPaint);
                    }
                    loader.request(key, z, wx, ty);
                }
            }
        }

        // 先粗后细：顺便预取 z-2 / z-3 的祖先瓦片，这样当前层还没下载完时，
        // 也能先铺上一层模糊底图，地图"立刻有东西"而不是等好几秒空白。
        if (z >= 12) {
            for (int tx = x0; tx <= x1; tx++) {
                for (int ty = y0; ty <= y1; ty++) {
                    if (ty < 0 || ty > maxTile) {
                        continue;
                    }
                    int wx = ((tx % (maxTile + 1)) + (maxTile + 1)) % (maxTile + 1);
                    for (int level = 2; level <= 3; level++) {
                        int az = z - level;
                        if (az < 0) {
                            break;
                        }
                        int ax = wx >> level;
                        int ay = ty >> level;
                        String akey = az + "/" + ax + "/" + ay;
                        if (loader.get(akey) == null) {
                            loader.request(akey, az, ax, ay);
                        }
                    }
                }
            }
        }

        // 底图没加载出来时，先画坐标网格 + 距离圈，保证"所在位置"看得见
        if (loader.hasNoTile()) {
            drawOfflineBase(canvas, w, h, z, scale, left, top);
        }

        // 轨迹
        if (trail.size() > 1) {
            ensureTrailPath(z);
            // 路径缓存在瓦片像素坐标系里，平移时直接用矩阵变换绘制，不再逐点重算
            float oldStroke = trailPaint.getStrokeWidth();
            trailPaint.setStrokeWidth(oldStroke / (float) scale);
            canvas.save();
            canvas.translate((float) (-left * scale), (float) (-top * scale));
            canvas.scale((float) scale, (float) scale);
            canvas.drawPath(trailPath, trailPaint);
            canvas.restore();
            trailPaint.setStrokeWidth(oldStroke);
        }

        // 目标点
        if (hasTarget) {
            double[] t = pixelOf(targetLat, targetLon, z);
            float sx = (float) ((t[0] - left) * scale);
            float sy = (float) ((t[1] - top) * scale);
            canvas.drawCircle(sx, sy, dp(9), targetPaint);
            canvas.drawLine(sx - dp(14), sy, sx + dp(14), sy, targetPaint);
            canvas.drawLine(sx, sy - dp(14), sx, sy + dp(14), targetPaint);
        }

        // 当前位置：使用位置图标图片 + 方向箭头
        double[] p = pixelOf(posLat, posLon, z);
        float psx = (float) ((p[0] - left) * scale);
        float psy = (float) ((p[1] - top) * scale);

        // 手机真实定位（先画，压在模拟位置下面）
        if (hasReal) {
            double[] r = pixelOf(realLat, realLon, z);
            float rx = (float) ((r[0] - left) * scale);
            float ry = (float) ((r[1] - top) * scale);
            if (realAccuracy > 1f) {
                float accPx = (float) (realAccuracy / Geo.metersPerPixel(centerLat, zoom));
                if (accPx > dp(6) && accPx < Math.max(w, h) * 2) {
                    canvas.drawCircle(rx, ry, accPx, realAccPaint);
                    canvas.drawCircle(rx, ry, accPx, realAccStrokePaint);
                }
            }
            canvas.drawCircle(rx, ry, dp(8), realFillPaint);
            canvas.drawCircle(rx, ry, dp(8), realRingPaint);
        }

        float rad = (float) Math.toRadians(posBearing);
        // 方向箭头（在图标外侧，指向行进方向）
        float tipLen = dp(30);
        float ax = psx + (float) Math.sin(rad) * tipLen;
        float ay = psy - (float) Math.cos(rad) * tipLen;
        float leftX = psx + (float) Math.sin(rad - 2.6) * dp(15);
        float leftY = psy - (float) Math.cos(rad - 2.6) * dp(15);
        float rightX = psx + (float) Math.sin(rad + 2.6) * dp(15);
        float rightY = psy - (float) Math.cos(rad + 2.6) * dp(15);
        Path arrow = new Path();
        arrow.moveTo(ax, ay);
        arrow.lineTo(leftX, leftY);
        arrow.lineTo(rightX, rightY);
        arrow.close();
        canvas.drawPath(arrow, posRingPaint);
        canvas.drawPath(arrow, posPaint);

        if (markerBmp != null) {
            float size = dp(38);
            canvas.drawBitmap(markerBmp, null,
                    new RectF(psx - size / 2, psy - size / 2, psx + size / 2, psy + size / 2), tilePaint);
        } else {
            // 图片解码失败时退化为圆形标记，保证不会没有位置标记
            canvas.drawCircle(psx, psy, dp(9), posPaint);
            canvas.drawCircle(psx, psy, dp(9), posRingPaint);
        }

        // 比例尺
        double mpp = Geo.metersPerPixel(centerLat, zoom);
        double[] candidates = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};
        double targetPx = dp(70);
        double best = candidates[0];
        for (double cand : candidates) {
            if (cand / mpp <= targetPx) {
                best = cand;
            }
        }
        float barLen = (float) (best / mpp);
        float bx = dp(10);
        float by = h - dp(12);
        canvas.drawLine(bx, by, bx + barLen, by, barPaint);
        canvas.drawLine(bx, by - dp(4), bx, by + dp(4), barPaint);
        canvas.drawLine(bx + barLen, by - dp(4), bx + barLen, by + dp(4), barPaint);
        String lenText = best >= 1000 ? (best / 1000) + " km" : ((int) best) + " m";
        canvas.drawText(lenText, bx, by - dp(8), textPaint);
        canvas.drawText("© OpenStreetMap", w - dp(96), h - dp(6), textPaint);

        // 顶部状态提示
        if (loader.sourceHasNoData()) {
            textPaint.setColor(0xFFF59E0B);
            canvas.drawText("当前图源在此区域没有数据，点右上「源」更换地图源",
                    dp(10), dp(16), textPaint);
            textPaint.setColor(0xFFE6EDF3);
        } else if (loader.hasNoTile()) {
            textPaint.setColor(0xFFF59E0B);
            String tip = loader.isAllCoolingDown()
                    ? "地图源暂时不可用，已用坐标网格代替（点右侧 ↻ 重试）"
                    : "地图加载中…（点右侧 ↻ 可重试）";
            canvas.drawText(tip, dp(10), dp(16), textPaint);
            textPaint.setColor(0xFFE6EDF3);
        }
    }

    /** 重建轨迹路径（仅在缩放层级变化或轨迹点数变化时执行） */
    private void ensureTrailPath(int z) {
        if (trailPath != null && trailPathZoom == z && trailPathSize == trail.size()
                && trailPathGcj == gcjDatum) {
            return;
        }
        Path p = new Path();
        boolean first = true;
        for (double[] pt : trail) {
            double[] t = pixelOf(pt[0], pt[1], z);
            if (first) {
                p.moveTo((float) t[0], (float) t[1]);
                first = false;
            } else {
                p.lineTo((float) t[0], (float) t[1]);
            }
        }
        trailPath = p;
        trailPathZoom = z;
        trailPathSize = trail.size();
        trailPathGcj = gcjDatum;
    }

    /**
     * 用祖先层级的瓦片先顶上：拖动/放大时立刻有画面，不用等新瓦片下载完。
     */
    private boolean drawAncestorTile(Canvas canvas, int z, int x, int y, RectF dst) {
        for (int level = 1; level <= 3; level++) {
            int az = z - level;
            if (az < 0) {
                return false;
            }
            int ax = x >> level;
            int ay = y >> level;
            Bitmap b = loader.get(az + "/" + ax + "/" + ay);
            if (b == null) {
                continue;
            }
            int sub = 256 >> level;
            int sx = (x - (ax << level)) * sub;
            int sy = (y - (ay << level)) * sub;
            tmpSrc.set(sx, sy, sx + sub, sy + sub);
            canvas.drawBitmap(b, tmpSrc, dst, tilePaint);
            return true;
        }
        return false;
    }

    /** 离线底图：经纬网格 + 以当前位置为中心的距离圈 */
    private void drawOfflineBase(Canvas canvas, int w, int h, int z, double scale, double left, double top) {
        double[] pos = pixelOf(posLat, posLon, z);
        float px = (float) ((pos[0] - left) * scale);
        float py = (float) ((pos[1] - top) * scale);

        // 距离圈
        double[] rings = {100, 250, 500, 1000, 2000};
        for (double m : rings) {
            float r = (float) (m / Geo.metersPerPixel(centerLat, zoom));
            if (r < dp(28) || r > Math.max(w, h)) {
                continue;
            }
            canvas.drawCircle(px, py, r, offlineRingPaint);
            String label = m >= 1000 ? (m / 1000) + " km" : ((int) m) + " m";
            canvas.drawText(label, px + r * 0.7f, py - r * 0.7f, textPaint);
        }

        // 经纬网格
        double[] steps = {0.0001, 0.0002, 0.0005, 0.001, 0.002, 0.005, 0.01, 0.02};
        double step = steps[steps.length - 1];
        for (double s : steps) {
            double[] a = pixelOf(centerLat, centerLon, z);
            double[] b = pixelOf(centerLat, centerLon + s, z);
            if (Math.abs(b[0] - a[0]) * scale >= dp(130)) {
                step = s;
                break;
            }
        }
        double latSpan = 360.0 * (h / 2.0 / scale) / (256.0 * Math.pow(2, z));
        double lonSpan = 360.0 * (w / 2.0 / scale) / (256.0 * Math.pow(2, z));
        double baseLon = Math.floor((centerLon - lonSpan) / step) * step;
        double baseLat = Math.floor((centerLat - latSpan) / step) * step;
        for (double lon = baseLon; lon <= centerLon + lonSpan + step; lon += step) {
            double[] t = pixelOf(centerLat, lon, z);
            float x = (float) ((t[0] - left) * scale);
            canvas.drawLine(x, 0, x, h, offlineGridPaint);
            canvas.drawText(String.format(Locale.US, "%.5f", lon), x + dp(3), h - dp(22), textPaint);
        }
        for (double lat = baseLat; lat <= centerLat + latSpan + step; lat += step) {
            double[] t = pixelOf(lat, centerLon, z);
            float y = (float) ((t[1] - top) * scale);
            canvas.drawLine(0, y, w, y, offlineGridPaint);
            canvas.drawText(String.format(Locale.US, "%.5f", lat), dp(4), y - dp(3), textPaint);
        }
    }

    // ---------------- 触摸 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int z = (int) Math.floor(zoom);
        double scale = Math.pow(2.0, zoom - z);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = System.currentTimeMillis();
                dragging = false;
                multiTouch = false;
                double[] c = pixelOf(centerLat, centerLon, z);
                downCenterPxX = c[0];
                downCenterPxY = c[1];
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                pinchStartSpan = span(event);
                pinchStartZoom = zoom;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    float s = span(event);
                    if (pinchStartSpan > 0) {
                        double nz = pinchStartZoom + Math.log(s / pinchStartSpan) / Math.log(2);
                        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, nz));
                    }
                    dragging = true;
                    invalidate();
                    return true;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.hypot(dx, dy) > dp(6)) {
                    dragging = true;
                }
                double nx = downCenterPxX - dx / scale;
                double ny = downCenterPxY - dy / scale;
                double[] moved = latLonOf(nx, ny, z);
                centerLat = clampLat(moved[0]);
                centerLon = moved[1];
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (!dragging && !multiTouch && System.currentTimeMillis() - downTime < 400) {
                    double[] cpx = pixelOf(centerLat, centerLon, z);
                    double px = cpx[0] + (event.getX() - getWidth() / 2.0) / scale;
                    double py = cpx[1] + (event.getY() - getHeight() / 2.0) / scale;
                    double[] tapped = latLonOf(px, py, z);
                    if (tapListener != null) {
                        tapListener.onTap(tapped[0], tapped[1]);
                    }
                }
                dragging = false;
                multiTouch = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                multiTouch = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float span(MotionEvent e) {
        if (e.getPointerCount() < 2) {
            return 0;
        }
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private static double clampLat(double lat) {
        return Math.max(-85.0, Math.min(85.0, lat));
    }
}
