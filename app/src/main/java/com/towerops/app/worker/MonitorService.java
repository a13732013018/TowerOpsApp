package com.towerops.app.worker;

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
 * 前台服务 —— 定时轮询（简洁可靠版）
 *
 * 设计原则：
 *   用 Handler.postDelayed 做定时循环，彻底去掉 AlarmManager / 双引擎 / WakeLock 超时等复杂机制。
 *   逻辑：startMonitor → runOnce → (任务完成后) → postDelayed(runOnce, interval)
 *   stopMonitor → removeCallbacks → 停止循环
 *
 * 关键修复：
 *   - 不依赖系统广播/Alarm，不受 Doze/省电策略影响循环逻辑
 *   - 每轮用独立 ExecutorService，执行完毕 shutdown，不存在 RejectedExecutionException
 *   - taskRunning 防重入 + 看门狗超时（8分钟），彻底消灭假死
 */
public class MonitorService extends Service {

    // ── 常量 ──────────────────────────────────────────────────────────────
    public static final String PREF_NAME        = "monitor_prefs";
    public static final String PREF_RUNNING     = "is_running";
    public static final String PREF_INT_MIN     = "interval_min";
    public static final String PREF_INT_MAX     = "interval_max";

    private static final String CHANNEL_ID      = "tower_ops_monitor";
    private static final int    NOTIF_ID        = 1001;
    private static final long   WAKELOCK_MS     = 10 * 60 * 1000L; // 10分钟
    private static final long   WATCHDOG_MS     = 8 * 60 * 1000L;  // 看门狗：8分钟超时重置

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
    private volatile boolean taskRunning = false;
    private volatile long    taskStartAt = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random  random      = new Random();

    private PowerManager.WakeLock wakeLock;
    private SharedPreferences     prefs;

    // 定时触发 runOnce 的 Runnable（postDelayed 用）
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning()) {
                runOnce();
            }
        }
    };

    // 看门狗 Runnable：每30秒检测一次是否假死
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (taskRunning && taskStartAt > 0
                    && System.currentTimeMillis() - taskStartAt > WATCHDOG_MS) {
                // 超过8分钟还在跑，强制重置
                taskRunning = false;
                taskStartAt = 0;
                updateNotification("任务超时已重置，等待下次轮询...");
                // 立即触发下一轮
                if (isRunning()) scheduleNext(30);
            }
            mainHandler.postDelayed(this, 30_000L);
        }
    };

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TowerOps:MonitorWakeLock");
        wakeLock.setReferenceCounted(false);

        // 启动看门狗
        mainHandler.postDelayed(watchdog, 30_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("监控运行中，自动处理工单..."));

        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_MS);
        }

        // 服务重建时恢复配置
        Session.get().loadConfig(this);

        // 若 prefs 记录为运行状态，且当前没有任务跑，就立即触发一次
        if (prefs.getBoolean(PREF_RUNNING, false) && !taskRunning) {
            runOnce();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.removeCallbacks(watchdog);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ── 对外接口（MainActivity 调用）───────────────────────────────────────

    public void setCallback(ServiceCallback cb) {
        this.callback = cb;
        if (cb != null && isRunning()) {
            long nextAt  = prefs.getLong("next_run_at", 0);
            int delaySec = (int) Math.max(0, (nextAt - System.currentTimeMillis()) / 1000);
            if (delaySec > 0) {
                mainHandler.post(() -> { if (callback != null) callback.onNextRun(delaySec); });
            }
        }
    }

    public void setInterval(int minSec, int maxSec) {
        prefs.edit().putInt(PREF_INT_MIN, minSec).putInt(PREF_INT_MAX, maxSec).apply();
    }

    public boolean isRunning() {
        return prefs.getBoolean(PREF_RUNNING, false);
    }

    public void startMonitor() {
        if (isRunning()) return;
        prefs.edit().putBoolean(PREF_RUNNING, true).apply();
        updateNotification("监控运行中，自动处理工单...");
        if (!taskRunning) runOnce();
    }

    public void stopMonitor() {
        prefs.edit().putBoolean(PREF_RUNNING, false).apply();
        mainHandler.removeCallbacks(timerRunnable);
        taskRunning = false;
        taskStartAt = 0;
        updateNotification("监控已暂停");
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ── 核心：执行一轮任务 ─────────────────────────────────────────────────

    private void runOnce() {
        if (!isRunning()) return;
        if (taskRunning) return;          // 防重入
        taskRunning = true;
        taskStartAt = System.currentTimeMillis();

        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_MS);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(new MonitorTask(new MonitorTask.MonitorCallback() {
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
                executor.shutdown();
                if (isRunning()) {
                    int delaySec = randInterval();
                    scheduleNext(delaySec);
                }
            }

            @Override
            public void onError(String msg) {
                updateNotification("错误：" + msg);
                if (callback != null) callback.onError(msg);
                taskRunning = false;
                taskStartAt = 0;
                executor.shutdown();
                if (isRunning()) {
                    // 出错后60秒重试
                    scheduleNext(60);
                }
            }
        }));
        executor.shutdown(); // 允许任务跑完后线程自动退出
    }

    /**
     * 在主线程延迟 delaySec 秒后执行下一轮
     */
    private void scheduleNext(int delaySec) {
        long nextAt = System.currentTimeMillis() + delaySec * 1000L;
        prefs.edit().putLong("next_run_at", nextAt).apply();
        updateNotification("下次轮询：" + delaySec + "秒后");
        mainHandler.post(() -> { if (callback != null) callback.onNextRun(delaySec); });
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        // 取消旧的，重新调度
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.postDelayed(timerRunnable, delaySec * 1000L);
    }

    private int randInterval() {
        int min = prefs.getInt(PREF_INT_MIN, 90);
        int max = prefs.getInt(PREF_INT_MAX, 120);
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
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
