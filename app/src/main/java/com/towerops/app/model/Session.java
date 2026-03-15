package com.towerops.app.model;

/**
 * 全局会话信息 —— 登录成功后持久保存，供所有线程使用（等价于易语言全局变量）
 */
public class Session {

    private static volatile Session instance;

    private Session() {}

    public static Session get() {
        if (instance == null) {
            synchronized (Session.class) {
                if (instance == null) instance = new Session();
            }
        }
        return instance;
    }

    // ---------- 登录后写入 ----------
    public volatile String userid       = "";
    public volatile String token        = "";
    public volatile String mobilephone  = "";
    public volatile String username     = "";
    public volatile String authHeader   = ""; // 对应 协议头（含 Authorization: token）

    // ---------- 运行时配置（主线程写，工作线程读）----------
    public volatile String appConfig    = ""; // 选1|选2|选5|阈值反馈|阈值接单 用 \u0001 分隔
    public volatile String[] taskArray  = new String[0];

    // ---------- 并发计数（用 synchronized 保护）----------
    private int runningThreads = 0;
    private int finishedCount  = 0;
    private int totalCount     = 0;

    public synchronized void resetProgress(int total) {
        this.totalCount     = total;
        this.runningThreads = 0;
        this.finishedCount  = 0;
    }

    public synchronized boolean tryAcquireSlot(int maxSlots) {
        if (runningThreads < maxSlots) { runningThreads++; return true; }
        return false;
    }

    public synchronized void releaseSlot() {
        runningThreads--;
        finishedCount++;
    }

    public synchronized boolean allDone() {
        return finishedCount >= totalCount;
    }

    public synchronized int getFinished() { return finishedCount; }
    public synchronized int getTotal()    { return totalCount; }
}
