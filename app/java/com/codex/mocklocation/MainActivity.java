package com.codex.mocklocation;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 主界面：遥测 + 地图 + 摇杆 + 配速 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 101;
    private static final int REQ_LOC_INIT = 102;
    private static final double PACE_MIN = 0.2;
    private static final double PACE_MAX = 60.0;
    private static final double PACE_RANGE = PACE_MAX / PACE_MIN;
    private static final String DEFAULT_HINT =
            "首次使用：开发者选项 → 选择模拟位置信息应用 → 选「模拟定位」，再点「开始模拟」。摇杆方向＝行进方向，离圆心越远越快。";

    private TextView tvCoord, tvSpeed, tvBearing, tvDist, tvTime, tvPace, tvHint, tvStatus;
    private TextView tvGpsInfo, btnGps;
    private TextView btnFollow;
    private TextView btnToggle;
    private TextView btnOverlay;
    private TextView[] presetChips;
    private MapView map;
    private JoystickView joystick;
    private SeekBar sbPace;
    private EditText etPace;
    private CheckBox cbReleaseStop;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private int trailTick = 0;
    private boolean locatingOnce = false;
    private boolean gpsPanelOn = false;
    private RealGps gps;

    /** 真实定位的副作用：首次启动定位、跟随模式 */
    private final RealGps.Listener gpsSideEffects = new RealGps.Listener() {
        @Override
        public void onRealGpsChanged() {
            if (!RealGps.hasFix) {
                return;
            }
            if (!Prefs.isPositionInitialized(MainActivity.this) && Prefs.isGuideConfirmed(MainActivity.this)) {
                applyStartPosition(RealGps.snapshot());
                return;
            }
            if (Prefs.isFollowReal(MainActivity.this) && !AppState.running
                    && AppState.engine.getMode() == MotionEngine.MODE_IDLE) {
                AppState.engine.setPosition(RealGps.lat, RealGps.lon);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        gps = RealGps.get(this);

        tvCoord = (TextView) findViewById(R.id.tvCoord);
        tvGpsInfo = (TextView) findViewById(R.id.tvGpsInfo);
        btnGps = (TextView) findViewById(R.id.btnGps);
        btnFollow = (TextView) findViewById(R.id.btnFollow);
        tvSpeed = (TextView) findViewById(R.id.tvSpeed);
        tvBearing = (TextView) findViewById(R.id.tvBearing);
        tvDist = (TextView) findViewById(R.id.tvDist);
        tvTime = (TextView) findViewById(R.id.tvTime);
        tvPace = (TextView) findViewById(R.id.tvPace);
        tvHint = (TextView) findViewById(R.id.tvHint);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        btnToggle = (TextView) findViewById(R.id.btnToggle);
        btnOverlay = (TextView) findViewById(R.id.btnOverlay);
        map = (MapView) findViewById(R.id.map);
        joystick = (JoystickView) findViewById(R.id.joystick);
        sbPace = (SeekBar) findViewById(R.id.sbPace);
        etPace = (EditText) findViewById(R.id.etPace);
        cbReleaseStop = (CheckBox) findViewById(R.id.cbReleaseStop);
        presetChips = new TextView[]{
                (TextView) findViewById(R.id.btnPaceWalk),
                (TextView) findViewById(R.id.btnPaceRun),
                (TextView) findViewById(R.id.btnPaceBike),
                (TextView) findViewById(R.id.btnPaceCar),
                (TextView) findViewById(R.id.btnPaceTrain),
                (TextView) findViewById(R.id.btnPacePlane)
        };

        double lat = Prefs.getLat(this);
        double lon = Prefs.getLon(this);
        double pace = Prefs.getPace(this);
        AppState.engine.setPosition(lat, lon);
        AppState.engine.setPace(pace);

        map.setCenter(lat, lon);
        map.setZoom(Prefs.getZoom(this));
        map.setPosition(lat, lon);
        map.setTrail(AppState.engine.snapshotTrail());
        map.setMapSource(Prefs.getMapSource(this));

        boolean releaseStop = Prefs.getReleaseStop(this);
        cbReleaseStop.setChecked(releaseStop);
        joystick.setReleaseStop(releaseStop);

        bindPaceControls();
        bindJoystick();
        bindMap();
        bindButtons();
        syncPaceUi(pace);
        updateStatus();
        ui.post(tick);
        updateGpsSubscription();

        // 首次打开：先看使用说明图片，确认后进入主界面
        if (!Prefs.isGuideConfirmed(this)) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(MainActivity.this, GuideActivity.class)
                            .putExtra(GuideActivity.EXTRA_FIRST_RUN, true));
                }
            }, 300);
        }
    }

    // ---------------- 交互绑定 ----------------

    private void bindPaceControls() {
        sbPace.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    applyPace(PACE_MIN * Math.pow(PACE_RANGE, progress / 100.0), true);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        final double[] presets = {1.4, 3.0, 6.0, 15.0, 80.0, 250.0};
        for (int i = 0; i < presetChips.length; i++) {
            final double v = presets[i];
            presetChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    applyPace(v, true);
                }
            });
        }

        findViewById(R.id.btnApplyPace).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                applyCustomPace();
            }
        });

        // 键盘上的「完成/回车」也能直接应用配速
        etPace.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                    applyCustomPace();
                    return true;
                }
                return false;
            }
        });

        cbReleaseStop.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                joystick.setReleaseStop(isChecked);
                if (isChecked) {
                    AppState.engine.releaseJoystick();
                }
                savePrefs();
            }
        });
    }

    private void bindJoystick() {
        joystick.setListener(new JoystickView.Listener() {
            @Override
            public void onMove(float nx, float ny, float magnitude) {
                AppState.engine.setJoystick(nx, ny);
                if (magnitude > 0.08f) {
                    map.clearTarget();
                }
            }

            @Override
            public void onRelease() {
                if (joystick.isReleaseStop()) {
                    AppState.engine.releaseJoystick();
                }
            }
        });
    }

    private void bindMap() {
        map.setOnMapTapListener(new MapView.OnMapTapListener() {
            @Override
            public void onTap(final double lat, final double lon) {
                AppState.engine.setTarget(lat, lon);
                map.setTarget(lat, lon);
                String msg = String.format(Locale.US, "目标点 %.5f, %.5f：将以 %.1f m/s 前进", lat, lon, AppState.engine.getPace());
                if (!AppState.running) {
                    msg += "（点“开始模拟”后开始移动）";
                }
                setHint(msg, false);
            }
        });
    }

    private void bindButtons() {
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (AppState.running) {
                    stopMock();
                } else {
                    requestThenStart();
                }
            }
        });

        findViewById(R.id.btnGps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleGpsPanel();
            }
        });

        findViewById(R.id.btnGpsUse).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                useRealAsStart();
            }
        });

        findViewById(R.id.btnFollow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleFollow();
            }
        });

        findViewById(R.id.btnMapCenter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                double lat = map.getCenterLat();
                double lon = map.getCenterLon();
                AppState.engine.setTarget(lat, lon);
                map.setTarget(lat, lon);
                String msg = String.format(Locale.US, "地图中心已设为目标点，将按 %.1f m/s 前进", AppState.engine.getPace());
                setHint(AppState.running ? msg : msg + "（点“开始模拟”后开始移动）", false);
            }
        });

        findViewById(R.id.btnManual).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showManualDialog();
            }
        });

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showSaveDialog();
            }
        });

        findViewById(R.id.btnFav).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showFavoritesDialog();
            }
        });

        findViewById(R.id.btnTrail).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AppState.engine.clearTrail();
                map.setTrail(AppState.engine.snapshotTrail());
                setHint("轨迹已清空", false);
            }
        });

        findViewById(R.id.btnDev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openDeveloperOptions();
            }
        });

        findViewById(R.id.btnBattery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestIgnoreBattery();
            }
        });

        findViewById(R.id.btnOverlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleOverlay();
            }
        });

        findViewById(R.id.btnHelp).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, GuideActivity.class)
                        .putExtra(GuideActivity.EXTRA_FIRST_RUN, false));
            }
        });

        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String txt = String.format(Locale.US, "%.6f,%.6f",
                        AppState.engine.getLat(), AppState.engine.getLon());
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("坐标", txt));
                    Toast.makeText(MainActivity.this, "已复制：" + txt, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    setHint("复制失败：" + t, true);
                }
            }
        });

        findViewById(R.id.btnZoomIn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                map.zoomIn();
            }
        });

        findViewById(R.id.btnReload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                map.retryTiles();
                setHint("正在重新加载地图瓦片…", false);
            }
        });

        findViewById(R.id.btnMapSource).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMapSourceDialog();
            }
        });

        findViewById(R.id.btnZoomOut).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                map.zoomOut();
            }
        });

        findViewById(R.id.btnCenter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                map.setCenter(AppState.engine.getLat(), AppState.engine.getLon());
            }
        });
    }

    // ---------------- 配速 ----------------

    private void applyPace(double pace, boolean persist) {
        AppState.engine.setPace(pace);
        syncPaceUi(pace);
        if (persist) {
            savePrefs();
        }
    }

    private void applyCustomPace() {
        String s = etPace.getText().toString().trim();
        if (s.length() == 0) {
            setHint("请输入配速，单位 m/s（例如 1.4 步行、3 跑步、15 驾车）", true);
            return;
        }
        try {
            double v = Double.parseDouble(s);
            if (v < 0.05 || v > 600) {
                setHint("配速请填 0.05 ~ 600 m/s 之间", true);
                return;
            }
            applyPace(v, true);
            etPace.clearFocus();
            hideKeyboard();
            setHint(String.format(Locale.US, "配速已设为 %.2f m/s（%.1f km/h）", v, v * 3.6), false);
        } catch (NumberFormatException nfe) {
            setHint("配速格式不正确，请输入数字", true);
        }
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void syncPaceUi(double pace) {
        tvPace.setText(String.format(Locale.US, "%.2f m/s · %.1f km/h", pace, pace * 3.6));
        int p = (int) Math.round(100.0 * Math.log(pace / PACE_MIN) / Math.log(PACE_RANGE));
        p = Math.max(0, Math.min(100, p));
        sbPace.setProgress(p);
        if (!etPace.hasFocus()) {
            String shown = pace == Math.floor(pace) ? String.valueOf((long) pace)
                    : String.format(Locale.US, "%.2f", pace);
            etPace.setText(shown);
        }
        double[] presets = {1.4, 3.0, 6.0, 15.0, 80.0, 250.0};
        for (int i = 0; i < presetChips.length; i++) {
            presetChips[i].setSelected(Math.abs(pace - presets[i]) < 0.011);
        }
    }

    // ---------------- 服务启停 ----------------

    private void toggleOverlay() {
        if (AppState.overlayActive) {
            Prefs.setOverlayEnabled(this, false);
            sendServiceAction(MockService.ACTION_HIDE_OVERLAY);
            setHint("悬浮摇杆已关闭（仍可用本页摇杆控制）。", false);
            return;
        }
        if (!OverlayJoystick.canDraw(this)) {
            Prefs.setOverlayWanted(this, true);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                setHint("请在页面里允许「显示在其他应用上层」，返回本应用后会自动打开悬浮摇杆。", false);
            } catch (Throwable t) {
                setHint("请手动到 设置 → 应用 → 特殊权限 → 显示在其他应用上层 里允许本应用。", true);
            }
            return;
        }
        enableOverlay();
    }

    private void enableOverlay() {
        Prefs.setOverlayWanted(this, false);
        Prefs.setOverlayEnabled(this, true);
        if (!AppState.running) {
            startMock();
        }
        sendServiceAction(MockService.ACTION_SHOW_OVERLAY);
        setHint("悬浮摇杆已开启：拖动顶部横条移动位置，点 × 关闭；摇杆方向＝行进方向。", false);
    }

    private void sendServiceAction(String action) {
        Intent i = new Intent(this, MockService.class);
        i.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            try {
                startService(i);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.isOverlayWanted(this) && OverlayJoystick.canDraw(this)) {
            enableOverlay();
        }
        updateGpsSubscription();
        maybeInitializePosition();
    }

    /**
     * 首次打开时用手机真实定位作为起点，这样一进来看到的就是"我所在位置"。
     * 之前固定用默认坐标（北京天安门），用户在自己城市打开地图就看不到身边的地图。
     */
    private void maybeInitializePosition() {
        if (Prefs.isPositionInitialized(this) || !Prefs.isGuideConfirmed(this)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC_INIT);
            return;
        }
        if (RealGps.hasFix) {
            applyStartPosition(RealGps.snapshot());
            return;
        }
        // 交给 RealGps：先取系统缓存位置，再等 GPS/网络实时定位
        gps.addListener(gpsSideEffects);
        setHint("正在获取你的当前位置作为起点…", false);
    }

    private void applyStartPosition(Location l) {
        AppState.engine.setPosition(l.getLatitude(), l.getLongitude());
        map.clearTarget();
        map.setCenter(l.getLatitude(), l.getLongitude());
        Prefs.setPositionInitialized(this, true);
        Prefs.save(this, l.getLatitude(), l.getLongitude(), AppState.engine.getPace(),
                map.getZoom(), cbReleaseStop.isChecked());
        setHint(String.format(Locale.US,
                "已用你的真实位置作为起点：%.6f, %.6f", l.getLatitude(), l.getLongitude()), false);
    }

    private void requestThenStart() {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
            return;
        }
        startMock();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) {
            if (requestCode == REQ_LOC_INIT) {
                maybeInitializePosition();
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setHint("没有定位权限。请到 系统设置 → 应用 → 模拟定位 → 权限 里允许「位置信息」，否则系统可能忽略模拟位置。", true);
            return;
        }
        startMock();
    }

    private void startMock() {
        AppState.engine.resetCounters();
        Intent i = new Intent(this, MockService.class);
        i.setAction(MockService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            AppState.running = true;
        } catch (Throwable t) {
            try {
                startService(i);
                AppState.running = true;
            } catch (Throwable t2) {
                setHint("启动失败：" + t2, true);
            }
        }
        setHint("已开始模拟：摇杆控制方向，配速决定速度。若没生效，请确认已在开发者选项里选中本应用。", false);
        updateStatus();
    }

    private void stopMock() {
        try {
            Intent i = new Intent(this, MockService.class);
            i.setAction(MockService.ACTION_STOP);
            startService(i);
        } catch (Throwable ignored) {
        }
        stopService(new Intent(this, MockService.class));
        AppState.running = false;
        AppState.mockReady = false;
        AppState.engine.releaseJoystick();
        joystick.reset();
        setHint("已停止模拟。长按摇杆方向已复位。", false);
        updateStatus();
    }

    // ---------------- 状态刷新 ----------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            MotionEngine e = AppState.engine;
            double lat = e.getLat();
            double lon = e.getLon();
            double speed = e.getEffectiveSpeed();
            double bearing = e.getBearing();

            tvCoord.setText(String.format(Locale.US, "%.6f, %.6f", lat, lon));
            setTextIfChanged(tvSpeed, String.format(Locale.US, "%.2f m/s\n%.1f km/h", speed, speed * 3.6));
            setTextIfChanged(tvBearing, String.format(Locale.US, "%d° %s", Math.round(bearing), Geo.dirName(bearing)));

            double moved = e.getMovedMeters();
            setTextIfChanged(tvDist, moved >= 1000
                    ? String.format(Locale.US, "%.2f km", moved / 1000.0)
                    : String.format(Locale.US, "%.0f m", moved));

            long sec = e.getRunMillis() / 1000L;
            setTextIfChanged(tvTime, String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60));

            map.setPosition(lat, lon);
            map.setBearing((float) bearing);
            // 手机真实定位：蓝点 + 精度圈
            if (tvGpsInfo != null) {
                tvGpsInfo.setText(RealGps.describe());
                tvGpsInfo.setTextColor(RealGps.hasFix
                        ? Color.parseColor("#FF60A5FA") : Color.parseColor("#FF8B949E"));
            }
            if (RealGps.hasFix) {
                map.setRealPosition(RealGps.lat, RealGps.lon, RealGps.accuracy);
            }
            trailTick++;
            if (trailTick % 3 == 0) {
                map.setTrail(e.snapshotTrail());
            }
            updateStatus();
            ui.postDelayed(this, 250);
        }
    };

    private void updateStatus() {
        if (btnOverlay != null) {
            btnOverlay.setSelected(AppState.overlayActive);
        }
        updateGpsButton();
        if (AppState.running) {
            if (AppState.mockReady) {
                tvStatus.setText("运行中");
                tvStatus.setBackgroundResource(R.drawable.badge_on);
                tvStatus.setTextColor(Color.parseColor("#FF7CF7A6"));
            } else {
                tvStatus.setText("等待授权");
                tvStatus.setBackgroundResource(R.drawable.badge_err);
                tvStatus.setTextColor(Color.parseColor("#FFFCA5A5"));
            }
            btnToggle.setText("停止模拟");
            btnToggle.setBackgroundResource(R.drawable.btn_danger);
            btnToggle.setTextColor(Color.WHITE);
        } else {
            tvStatus.setText("未运行");
            tvStatus.setBackgroundResource(R.drawable.badge_idle);
            tvStatus.setTextColor(Color.parseColor("#FF8B949E"));
            btnToggle.setText("开始模拟");
            btnToggle.setBackgroundResource(R.drawable.btn_primary);
            btnToggle.setTextColor(Color.parseColor("#FF06210F"));
        }
        String err = AppState.lastError;
        if (err != null && err.length() > 0) {
            tvHint.setText("⚠ " + err);
            tvHint.setTextColor(Color.parseColor("#FFFCA5A5"));
        }
    }

    private void setHint(String text, boolean error) {
        tvHint.setText(text);
        tvHint.setTextColor(error ? Color.parseColor("#FFFCA5A5") : Color.parseColor("#FF8B949E"));
    }

    /** 文本没变就不刷新，避免无谓的布局与重绘 */
    private void setTextIfChanged(TextView tv, String text) {
        if (!text.contentEquals(tv.getText())) {
            tv.setText(text);
        }
    }

    // ---------------- 对话框 ----------------

    private void showManualDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_manual, null);
        final EditText etLat = (EditText) v.findViewById(R.id.etLat);
        final EditText etLon = (EditText) v.findViewById(R.id.etLon);
        etLat.setText(String.format(Locale.US, "%.6f", AppState.engine.getLat()));
        etLon.setText(String.format(Locale.US, "%.6f", AppState.engine.getLon()));
        new AlertDialog.Builder(this)
                .setTitle("输入坐标（直接跳转）")
                .setView(v)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            double lat = Double.parseDouble(etLat.getText().toString().trim());
                            double lon = Double.parseDouble(etLon.getText().toString().trim());
                            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                                setHint("坐标超范围：纬度 -90~90，经度 -180~180", true);
                                return;
                            }
                            AppState.engine.setPosition(lat, lon);
                            map.clearTarget();
                            map.setCenter(lat, lon);
                            setHint(String.format(Locale.US, "已跳转到 %.6f, %.6f", lat, lon), false);
                        } catch (Throwable t) {
                            setHint("坐标格式不正确", true);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSaveDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_name, null);
        final EditText etName = (EditText) v.findViewById(R.id.etName);
        new AlertDialog.Builder(this)
                .setTitle("收藏当前位置")
                .setView(v)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = etName.getText().toString().trim();
                        Prefs.addFavorite(MainActivity.this, name,
                                AppState.engine.getLat(), AppState.engine.getLon());
                        setHint("已收藏：" + (name.length() == 0 ? "未命名" : name), false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFavoritesDialog() {
        final List<String> names = Prefs.getFavoriteNames(this);
        final List<double[]> pts = Prefs.getFavorites(this);
        if (names.isEmpty()) {
            setHint("收藏夹还是空的：先在地图上选点，再点「收藏此点」", false);
            return;
        }
        final ListView lv = new ListView(this);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, names);
        lv.setAdapter(adapter);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("收藏夹（点选＝前往，长按＝删除）")
                .setView(lv)
                .setNegativeButton("关闭", null)
                .create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                double[] p = pts.get(position);
                if (AppState.running) {
                    AppState.engine.setTarget(p[0], p[1]);
                    map.setTarget(p[0], p[1]);
                    setHint(String.format(Locale.US, "前往「%s」：%.5f, %.5f", names.get(position), p[0], p[1]), false);
                } else {
                    AppState.engine.setPosition(p[0], p[1]);
                    map.clearTarget();
                    map.setCenter(p[0], p[1]);
                    setHint(String.format(Locale.US, "已跳转到「%s」", names.get(position)), false);
                }
                dialog.dismiss();
            }
        });
        lv.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String removed = names.remove(position);
                pts.remove(position);
                Prefs.removeFavorite(MainActivity.this, position);
                adapter.notifyDataSetChanged();
                setHint("已删除收藏：" + removed, false);
                return true;
            }
        });
        dialog.show();
    }

    // ---------------- 其它 ----------------

    /** 开关手机真实定位（GPS + 网络） */
    private void toggleGpsPanel() {
        if (gpsPanelOn) {
            gpsPanelOn = false;
            updateGpsSubscription();
            updateGpsButton();
            setHint("已停止读取手机定位（保留最后一次结果）。", false);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
            setHint("请先允许定位权限，然后再点「获取定位」。", false);
            return;
        }
        if (!RealGps.locationEnabled(this)) {
            showLocationSettingsDialog();
            return;
        }
        gpsPanelOn = true;
        gps.restart();
        updateGpsSubscription();
        updateGpsButton();
        setHint("正在读取手机 GPS 定位：地图上蓝点是手机真实位置，绿色图标是模拟位置。", false);
    }

    /** 把最新一次真实定位设为模拟起点 */
    private void useRealAsStart() {
        if (!RealGps.hasFix) {
            setHint("还没拿到定位结果：先点「获取定位」等几秒；室内可到窗边或室外再试。", true);
            return;
        }
        applyStartPosition(RealGps.snapshot());
    }

    /** 跟随：模拟位置持续跟随手机真实定位 */
    private void toggleFollow() {
        boolean on = !Prefs.isFollowReal(this);
        Prefs.setFollowReal(this, on);
        if (on) {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
            } else if (!RealGps.locationEnabled(this)) {
                showLocationSettingsDialog();
            } else {
                gps.restart();
            }
            setHint("已开启跟随：模拟位置会跟着手机真实定位走；推摇杆或点地图可临时脱离。", false);
        } else {
            setHint("已关闭跟随定位。", false);
        }
        updateGpsSubscription();
        updateGpsButton();
    }

    /** 是否需要订阅真实定位（面板开启 / 跟随 / 首次初始化） */
    private void updateGpsSubscription() {
        boolean need = gpsPanelOn
                || Prefs.isFollowReal(this)
                || (!Prefs.isPositionInitialized(this) && Prefs.isGuideConfirmed(this));
        if (need) {
            gps.addListener(gpsSideEffects);
        } else {
            gps.removeListener(gpsSideEffects);
        }
    }

    private void updateGpsButton() {
        if (btnGps == null) {
            return;
        }
        boolean active = gpsPanelOn || Prefs.isFollowReal(this);
        btnGps.setText(active ? "停止定位" : "获取定位");
        btnGps.setBackgroundResource(active ? R.drawable.btn_primary : R.drawable.btn_secondary);
        btnGps.setTextColor(active ? Color.parseColor("#FF06210F") : Color.parseColor("#FFE6EDF3"));
        if (btnFollow != null) {
            btnFollow.setSelected(Prefs.isFollowReal(this));
        }
    }

    private void showLocationSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("手机定位开关没有打开")
                .setMessage("请先打开系统的定位开关（GPS），本应用才能读取当前位置。")
                .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 地图源选择：自动 / OSM 官方 / OSM 镜像 / Esri / 高德（中文） */
    private void showMapSourceDialog() {
        final String[] items = new String[TileLoader.SOURCES.length + 1];
        items[0] = "自动（启动时测速选最快）";
        for (int i = 0; i < TileLoader.SOURCES.length; i++) {
            items[i + 1] = TileLoader.SOURCES[i].name
                    + (TileLoader.SOURCES[i].gcj ? "（自动坐标纠偏）" : "");
        }
        final int current = Prefs.getMapSource(this);
        int checked = current == TileLoader.MODE_AUTO ? 0 : current + 1;
        new AlertDialog.Builder(this)
                .setTitle("地图源（当前：" + currentSourceLabel(current) + "）")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int mode = which == 0 ? TileLoader.MODE_AUTO : which - 1;
                        Prefs.setMapSource(MainActivity.this, mode);
                        map.setMapSource(mode);
                        dialog.dismiss();
                        String extra = (mode >= 0 && TileLoader.SOURCES[mode].gcj)
                                ? "（已自动做坐标纠偏）" : "";
                        setHint("地图源已切换为：" + currentSourceLabel(mode) + extra, false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String currentSourceLabel(int mode) {
        if (mode >= 0 && mode < TileLoader.SOURCES.length) {
            return TileLoader.SOURCES[mode].name;
        }
        return "自动";
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            setHint("在「选择模拟位置信息应用」里选中「模拟定位」，再回到本应用。", false);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable t2) {
                setHint("无法打开系统设置，请手动进入开发者选项。", true);
            }
        }
    }

    private void requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < 23) {
            setHint("当前系统版本无需设置", false);
            return;
        }
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                setHint("已经允许后台常驻，长时间模拟不会被省电策略杀掉。", false);
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            setHint("请在 系统设置 → 电池 → 应用省电策略 里把本应用设为「无限制」。", false);
        }
    }

    private void savePrefs() {
        Prefs.save(this, AppState.engine.getLat(), AppState.engine.getLon(),
                AppState.engine.getPace(), map.getZoom(), cbReleaseStop.isChecked());
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePrefs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        if (gps != null) {
            gps.removeListener(gpsSideEffects);
        }
    }
}
