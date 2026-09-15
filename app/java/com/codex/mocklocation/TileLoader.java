package com.codex.mocklocation;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地图瓦片下载与缓存。
 *
 * 设计要点：
 * 1. 多个图源互为备份：OSM 官方 → OSM 德国镜像 → Esri；
 *    高德（GCJ-02 火星坐标，中文地图）需要坐标纠偏，由 MapView 负责换算。
 * 2. 启动时并行探测，按响应速度挑最快的可用图源；探测失败的直接冷却，不再白等。
 * 3. 识别"暂无可显示数据"的灰底占位瓦片（Esri 在国内经常返回这种图），当作失败处理。
 * 4. 支持用户手动指定图源（界面上的"图源"按钮）。
 */
public class TileLoader {

    public interface Listener {
        /** 有新瓦片到达（key 为 null 表示状态变化） */
        void onTileLoaded(String key);
    }

    /** 图源定义 */
    public static class Source {
        public final String name;
        public final String url;      // %1$d=z %2$d=x %3$d=y
        public final boolean gcj;     // 是否使用 GCJ-02 坐标系（高德/腾讯）

        Source(String name, String url, boolean gcj) {
            this.name = name;
            this.url = url;
            this.gcj = gcj;
        }
    }

    public static final Source[] SOURCES = {
            new Source("OSM 官方", "https://tile.openstreetmap.org/%1$d/%2$d/%3$d.png", false),
            new Source("OSM 德国镜像", "https://a.tile.openstreetmap.de/%1$d/%2$d/%3$d.png", false),
            new Source("Esri 世界街道图", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/%1$d/%3$d/%2$d", false),
            new Source("高德地图（中文）", "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=%2$d&y=%3$d&z=%1$d", true)
    };

    public static final int MODE_AUTO = -1;

    private static final String UA = "MockLocation/1.5 (Android; personal use)";
    private static final int FAIL_LIMIT = 2;
    private static final long COOLDOWN_MS = 60_000L;

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final ConcurrentHashMap<String, Boolean> loading = new ConcurrentHashMap<String, Boolean>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final LruCache<String, Bitmap> cache;

    private final int[] failCount = new int[SOURCES.length];
    private final long[] cooldownUntil = new long[SOURCES.length];
    private volatile int preferred = 0;
    private volatile int mode = MODE_AUTO;
    private volatile boolean hasAnyTile = false;
    private volatile boolean probed = false;

    public TileLoader(Listener listener) {
        this.listener = listener;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        if (maxKb < 4096) {
            maxKb = 4096;
        }
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        probeSources();
    }

    // ---------------- 图源选择 ----------------

    public void setMode(int newMode) {
        synchronized (this) {
            mode = newMode;
            if (newMode >= 0 && newMode < SOURCES.length) {
                preferred = newMode;
                probed = true;
                for (int i = 0; i < SOURCES.length; i++) {
                    failCount[i] = 0;
                    cooldownUntil[i] = 0;
                }
            } else {
                probed = false;
            }
        }
        if (newMode == MODE_AUTO) {
            probeSources();
        }
        notifyListener(null);
    }

    public int getMode() {
        return mode;
    }

    /** 当前使用的图源是否需要 GCJ-02 纠偏 */
    public boolean isGcj() {
        int p = preferred;
        return p >= 0 && p < SOURCES.length && SOURCES[p].gcj;
    }

    public String currentSourceName() {
        int p = preferred;
        return (p >= 0 && p < SOURCES.length) ? SOURCES[p].name : SOURCES[0].name;
    }

    private void probeSources() {
        final long[] costs = new long[SOURCES.length];
        final boolean[] ok = new boolean[SOURCES.length];
        final AtomicInteger done = new AtomicInteger(0);
        for (int i = 0; i < SOURCES.length; i++) {
            final int idx = i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    long t0 = SystemClock.elapsedRealtime();
                    Bitmap b = download(String.format(SOURCES[idx].url, 3, 4, 3), 2500, 2500);
                    costs[idx] = SystemClock.elapsedRealtime() - t0;
                    ok[idx] = b != null;
                    if (done.incrementAndGet() == SOURCES.length) {
                        finishProbe(ok, costs);
                    }
                }
            });
        }
    }

    private void finishProbe(boolean[] ok, long[] costs) {
        if (mode != MODE_AUTO) {
            probed = true;
            return;   // 用户在探测期间手动选了图源，以用户选择为准
        }
        int bestIdx = -1;
        long bestMs = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (int i = 0; i < ok.length; i++) {
            if (ok[i]) {
                if (costs[i] < bestMs) {
                    bestMs = costs[i];
                    bestIdx = i;
                }
                failCount[i] = 0;
                cooldownUntil[i] = 0;
            } else {
                failCount[i] = FAIL_LIMIT;
                cooldownUntil[i] = now + COOLDOWN_MS * 2;
            }
        }
        if (bestIdx >= 0) {
            preferred = bestIdx;
        }
        probed = true;
        notifyListener(null);
    }

    // ---------------- 状态查询 ----------------

    public Bitmap get(String key) {
        return cache.get(key);
    }

    public boolean hasNoTile() {
        return !hasAnyTile;
    }

    public boolean isAllCoolingDown() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < SOURCES.length; i++) {
            if (mode >= 0) {
                return false;
            }
            if (cooldownUntil[i] <= now) {
                return false;
            }
        }
        return true;
    }

    /** 手动指定图源时，该图源在当前区域是否没有数据（连续失败） */
    public boolean sourceHasNoData() {
        int m = mode;
        return m >= 0 && m < failCount.length && failCount[m] >= 3;
    }

    /** 手动重试：清掉冷却与失败计数（清空缓存以便重新拉取占位瓦片） */
    public void retryAll() {
        synchronized (this) {
            for (int i = 0; i < SOURCES.length; i++) {
                failCount[i] = 0;
                cooldownUntil[i] = 0;
            }
            if (mode == MODE_AUTO) {
                preferred = 0;
                probed = false;
            }
        }
        cache.evictAll();
        hasAnyTile = false;
        if (mode == MODE_AUTO) {
            probeSources();
        }
        notifyListener(null);
    }

    public void request(final String key, final int z, final int x, final int y) {
        if (cache.get(key) != null) {
            return;
        }
        if (loading.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                long now = System.currentTimeMillis();
                // 取快照，避免下载过程中用户切换图源导致下标越界
                int m = mode;
                int n = SOURCES.length;
                if (m >= 0 && m < n) {
                    // 用户指定图源：只用它
                    bmp = download(String.format(SOURCES[m].url, z, x, y), 6000, 6000);
                    synchronized (TileLoader.this) {
                        if (bmp == null) {
                            failCount[m] = Math.min(FAIL_LIMIT + 1, failCount[m] + 1);
                        } else {
                            failCount[m] = 0;
                        }
                    }
                } else {
                    int base = preferred;
                    if (base < 0 || base >= n) {
                        base = 0;
                    }
                    for (int k = 0; k < n; k++) {
                        int idx = (base + k) % n;
                        if (cooldownUntil[idx] > now) {
                            continue;
                        }
                        bmp = download(String.format(SOURCES[idx].url, z, x, y), 4500, 4500);
                        if (bmp != null) {
                            synchronized (TileLoader.this) {
                                preferred = idx;
                                failCount[idx] = 0;
                            }
                            break;
                        }
                        synchronized (TileLoader.this) {
                            failCount[idx]++;
                            if (failCount[idx] >= FAIL_LIMIT) {
                                cooldownUntil[idx] = System.currentTimeMillis() + COOLDOWN_MS;
                            }
                        }
                    }
                }
                loading.remove(key);
                if (bmp != null) {
                    hasAnyTile = true;
                    cache.put(key, bmp);
                    notifyListener(key);
                } else if (mode < 0 && isAllCoolingDown()) {
                    notifyListener(null);
                }
            }
        });
    }

    private void notifyListener(final String key) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onTileLoaded(key);
            }
        });
    }

    private Bitmap download(String url, int connectTimeout, int readTimeout) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "image/png,image/*");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            in = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp != null && isPlaceholderTile(bmp)) {
                bmp.recycle();
                return null;   // "暂无可显示数据"的灰底占位图，当作失败
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 识别占位瓦片：部分图源（例如 Esri 在国内）没有数据时会返回
     * 一张灰底 + "Map data not yet available" 的图，颜色大量集中在 (204,204,204)。
     */
    private static boolean isPlaceholderTile(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 32 || h < 32) {
            return false;
        }
        int stepX = Math.max(1, w / 32);
        int stepY = Math.max(1, h / 32);
        int total = 0;
        int gray = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                total++;
                if (Math.abs(r - 204) <= 3 && Math.abs(g - 204) <= 3 && Math.abs(b - 204) <= 3) {
                    gray++;
                }
            }
        }
        return total > 0 && gray * 2 > total;   // 超过一半是 (204,204,204)
    }
}
