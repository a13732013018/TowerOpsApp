package com.towerops.app.worker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import com.towerops.app.R;
import com.towerops.app.model.Session;
import com.towerops.app.model.WorkOrder;
import com.towerops.app.ui.MainActivity;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务 —— 保活核心（终极双引擎版）
 *
 * 修复的根本问题：
 *   原版 monitorOn 是内存 volatile 变量，服务被 START_STICKY 重建后恢复为 false，
 *   导致 AlarmManager 触发时因 monitorOn==false 而跳过执行，彻底停止轮询。
 *
 * 解决方案：
 *   1. monitorOn 状态以 SharedPreferences 为唯一权威来源，内存变量只作缓存
 *   2. 每次 onStartCommand 都重新从 prefs 读取状态
 *   3. 双引擎保活：AlarmManager（主引擎）+ Handler 心跳（兜底引擎）
 *      - AlarmManager：setExactAndAllowWhileIdle，息屏 Doze 下也能准时唤醒
 *      - Handler 心跳：APP 在前台时每 5 秒检测一次，防止 Alarm 被系统吞掉
 *   4. 任务执行完成后无论如何都重新调度下一轮
 */
public class MonitorService extends Service {

    // ── 常量 ──────────────────────────────────────────────────────────────
    public static final String ACTION_ALARM_TRIGGER = "com.towerops.app.ALARM_TRIGGER";
    public static final String PREF_NAME            = "monitor_prefs";
    public static final String PREF_RUNNING         = "is_running";
    public static final String PREF_INT_MIN         = "interval_min";
    public static final String PREF_INT_MAX         = "interval_max";
    public static final String PREF_NEXT_RUN_AT     = "next_run_at";   // 下次执行时间戳(ms)

    private static final String CHANNEL_ID          = "tower_ops_monitor";
    private static final int    NOTIF_ID            = 1001;
    private static final long   WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L; // 10分钟
    private static final long   HEARTBEAT_MS        = 5_000L;           // 心跳间隔5秒

    // ── Binder ─────────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public MonitorService getService() { return MonitorService.this; }
    }
    private final IBinder binder = new LocalBinder();

    // ── 回调接口 ────────────────────────────────────────────────────────────
    public interface ServiceCallback {
        void onOrdersReady(List<WorkOrder> orders);
        void onStatusUpdate(int rowIndex, String billsn, String content);
        void onAllDone(int done, int total);
        void onError(String msg);
        void onNextRun(int delaySec);
    }
    private volatile ServiceCallback callback;

    // ── 状态 ───────────────────────────────────────────────────────────────
    private volatile boolean  taskRunning  = false;
    private volatile long     taskStartAt  = 0;           // 当前任务开始时间（毫秒），用于超时检测
    private static final long TASK_TIMEOUT_MS = 6 * 60 * 1000L; // 任务超时 6 分钟
    private final Handler     mainHandler  = new Handler(Looper.getMainLooper());
    private final Random      random       = new Random();

    private PowerManager.WakeLock wakeLock;
    private AlarmManager          alarmManager;
    private SharedPreferences     prefs;

    // 心跳 Runnable：每 5 秒检查一次，如果应该跑但没跑就立刻启动
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (isRunning()) {
                // ★ 超时看门狗：任务运行超过 6 分钟，强制重置 taskRunning，
                //   防止 WorkerTask 异常/网络卡死导致 taskRunning 永久为 true（假死）★
                if (taskRunning && taskStartAt > 0
                        && System.currentTimeMillis() - taskStartAt > TASK_TIMEOUT_MS) {
                    taskRunning = false;
                    taskStartAt = 0;
                    updateNotification("任务超时，已重置，等待下次轮询...");
                }

                if (!taskRunning) {
                    long nextAt = prefs.getLong(PREF_NEXT_RUN_AT, 0);
                    if (System.currentTimeMillis() >= nextAt) {
                        // 超时未触发（Alarm 被吞），心跳兜底启动
                        runOnce();
                    }
                }
            }
            // 只要服务存在就持续心跳
            mainHandler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        prefs        = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TowerOps:MonitorWakeLock");
        wakeLock.setReferenceCounted(false);

        // 启动心跳
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 立即提升为前台服务
        startForeground(NOTIF_ID, buildNotification("监控运行中，自动处理工单..."));

        // 确保 WakeLock 持有
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }

        // ★ 关键：服务重建时 Session.appConfig 会丢失（内存变量重置为""），
        //   必须从 SharedPreferences 恢复，否则 WorkerTask 因 cfg.length<5 直接 return ★
        Session.get().loadConfig(this);

        // ★ 关键修复：不再依赖内存 monitorOn，直接读 prefs 判断是否应该运行 ★
        boolean shouldRun = prefs.getBoolean(PREF_RUNNING, false);

        if (shouldRun && !taskRunning) {
            // 无论是 AlarmManager 触发、START_STICKY 重建、还是 BootReceiver 拉起
            // 只要 prefs 里记录的是运行状态就直接执行
            runOnce();
        } else if (!shouldRun) {
            // 已停止状态，释放 WakeLock
            if (wakeLock.isHeld()) wakeLock.release();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(heartbeat);
        cancelAlarm();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ── 对外接口（MainActivity 调用）───────────────────────────────────────

    public void setCallback(ServiceCallback cb) {
        this.callback = cb;
        // 回调重新注册时，如果正在运行就刷新一次按钮/进度状态
        if (cb != null && isRunning()) {
            int delaySec = getRemainingSeconds();
            if (delaySec > 0) {
                mainHandler.post(() -> { if (callback != null) callback.onNextRun(delaySec); });
            }
        }
    }

    public void setInterval(int minSec, int maxSec) {
        prefs.edit()
                .putInt(PREF_INT_MIN, minSec)
                .putInt(PREF_INT_MAX, maxSec)
                .apply();
    }

    /** 是否正在轮询（以 prefs 为准，不依赖内存变量）*/
    public boolean isRunning() {
        return prefs.getBoolean(PREF_RUNNING, false);
    }

    public void startMonitor() {
        if (isRunning()) return;
        // ★ 先写 prefs，再执行，确保重建后状态一致 ★
        prefs.edit().putBoolean(PREF_RUNNING, true).apply();
        updateNotification("监控运行中，自动处理工单...");
        if (!taskRunning) runOnce();
    }

    public void stopMonitor() {
        prefs.edit()
                .putBoolean(PREF_RUNNING, false)
                .putLong(PREF_NEXT_RUN_AT, 0)
                .apply();
        cancelAlarm();
        taskRunning = false;
        updateNotification("监控已暂停");
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ── 核心轮询逻辑 ────────────────────────────────────────────────────────

    private void runOnce() {
        if (!isRunning()) return;          // 双重检查
        if (taskRunning) return;           // 防重入
        taskRunning = true;
        taskStartAt = System.currentTimeMillis(); // ★ 记录任务开始时间，供超时看门狗使用 ★

        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }
        AlarmReceiver.releaseWakeLock();

        // ★ 每轮新建独立线程执行 MonitorTask（MonitorTask 内部自己管理线程池）★
        // 不再复用 pool，避免 pool.shutdown() 后 execute() 抛 RejectedExecutionException
        ExecutorService taskRunner = Executors.newSingleThreadExecutor();
        taskRunner.execute(new MonitorTask(new MonitorTask.MonitorCallback() {
            @Override
            public void onOrdersReady(List<WorkOrder> orders) {
                updateNotification("处理中：共 " + orders.size() + " 条工单");
                if (callback != null) callback.onOrdersReady(orders);
            }

            @Override
            public void onStatusUpdate(int rowIndex, String billsn, String content) {
                if (callback != null) callback.onStatusUpdate(rowIndex, billsn, content);
            }

            @Override
            public void onAllDone() {
                int done  = Session.get().getFinished();
                int total = Session.get().getTotal();
                if (callback != null) callback.onAllDone(done, total);
                taskRunning = false;
                taskStartAt = 0;
                taskRunner.shutdown(); // 释放本轮线程
                if (isRunning()) scheduleNextRun();
            }

            @Override
            public void onError(String msg) {
                updateNotification("错误：" + msg);
                if (callback != null) callback.onError(msg);
                taskRunning = false;
                taskStartAt = 0;
                taskRunner.shutdown();
                if (isRunning()) scheduleNextRun();
            }
        }));
        taskRunner.shutdown(); // 允许任务完成后自动退出，不阻塞
    }

    /**
     * 安排下次执行：
     * - 记录下次执行时间到 prefs（心跳引擎用于超时检测）
     * - 设置 AlarmManager（主引擎）
     */
    private void scheduleNextRun() {
        int minSec   = prefs.getInt(PREF_INT_MIN, 90);
        int maxSec   = prefs.getInt(PREF_INT_MAX, 120);
        int delaySec = minSec + random.nextInt(Math.max(1, maxSec - minSec + 1));

        // ★ 记录下次执行绝对时间，心跳兜底用 ★
        long nextAt = System.currentTimeMillis() + delaySec * 1000L;
        prefs.edit().putLong(PREF_NEXT_RUN_AT, nextAt).apply();

        updateNotification("下次轮询：" + delaySec + "秒后");

        // 通知 UI 刷新倒计时（即使 callback 当前为 null 也不影响调度）
        final int ds = delaySec;
        mainHandler.post(() -> { if (callback != null) callback.onNextRun(ds); });

        // 释放 WakeLock，等待下次触发
        if (wakeLock.isHeld()) wakeLock.release();

        // 设置 AlarmManager
        PendingIntent pi = buildAlarmPendingIntent();
        long triggerAt = SystemClock.elapsedRealtime() + delaySec * 1000L;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        } else {
            alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }
    }

    private void cancelAlarm() {
        alarmManager.cancel(buildAlarmPendingIntent());
    }

    private PendingIntent buildAlarmPendingIntent() {
        Intent i = new Intent(this, AlarmReceiver.class);
        i.setAction(ACTION_ALARM_TRIGGER);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, 0, i, flags);
    }

    /** 获取距下次执行的剩余秒数（供 UI 恢复显示用）*/
    private int getRemainingSeconds() {
        long nextAt = prefs.getLong(PREF_NEXT_RUN_AT, 0);
        long diff   = nextAt - System.currentTimeMillis();
        return diff > 0 ? (int)(diff / 1000) : 0;
    }

    // ── 通知相关 ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "工单监控", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台自动接单/反馈/回单");
            ch.setSound(null, null);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("铁塔工单监控")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(String text) {
        Notification n = buildNotification(text);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    // ── 静态工具 ──────────────────────────────────────────────────────────

    public static void startSelf(Context ctx) {
        Intent i = new Intent(ctx, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
