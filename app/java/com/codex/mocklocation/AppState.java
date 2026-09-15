package com.codex.mocklocation;

/** 进程内共享状态：Activity 与 Service（同进程）都读写这里 */
public final class AppState {

    public static final MotionEngine engine = new MotionEngine();

    /** 模拟服务是否已启动 */
    public static volatile boolean running = false;

    /** 模拟位置提供者是否创建成功 */
    public static volatile boolean mockReady = false;

    /** 悬浮摇杆是否正在显示 */
    public static volatile boolean overlayActive = false;

    /** 最近一次错误信息（界面提示用） */
    public static volatile String lastError = "";

    private AppState() {
    }
}
