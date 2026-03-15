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
 * 前台服务 —— 保活核心（终极版）
 *
 * 保活方案：
 *  1. 前台服务（通知栏常驻） → 系统轻易不杀
 *  2. AlarmManager.setExactAndAllowWhileIdle → 息屏/打盹模式下也能精准触发
 *  3. WakeLock（带超时保护）→ 任务期间 CPU 不睡眠
 *  4. START_STICKY → 被杀后自动重启
 *  5. BootReceiver → 开机后自动拉起
 *  6. SharedPreferences 持久化运行状态 → 服务重启后自动恢复轮询
 */
public class MonitorService extends Service {

    // ── 常量 ──────────────────────────────────────────────────────────────
    public static final String ACTION_ALARM_TRIGGER = "com.towerops.app.ALARM_TRIGGER";
    public static final String PREF_NAME            = "monitor_prefs";
    public static final String PREF_RUNNING         = "is_running";
    public static final String PREF_INT_MIN         = "interval_min";
    public static final String PREF_INT_MAX         = "interval_max";

    private static final String CHANNEL_ID  = "tower_ops_monitor";
    private static final int    NOTIF_ID    = 1001;
    // WakeLock 最长持有 10 分钟，防止任务卡死导致永远不释放
    private static final long   WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L;

    // ── Binder ─────────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public MonitorService getService() { return MonitorService.this; }
    }
    private final IBinder binder = new LocalBinder();

    // ── 回调接口（绑定后由 MainActivity 设置，退到后台置 null）─────────────
    public interface ServiceCallback {
        void onOrdersReady(List<WorkOrder> orders);
        void onStatusUpdate(int rowIndex, String billsn, String content);
        void onAllDone(int done, int total);
        void onError(String msg);
        void onNextRun(int delaySec);
    }
    private volatile ServiceCallback callback;

    // ── 状态 ───────────────────────────────────────────────────────────────
    private volatile boolean  taskRunning  = false;  // 当前是否正在跑任务
    private volatile boolean  monitorOn    = false;  // 是否开启了轮询
    private final Handler     mainHandler  = new Handler(Looper.getMainLooper());
    private final ExecutorService pool     = Executors.newCachedThreadPool();
    private final Random      random       = new Random();

    private PowerManager.WakeLock wakeLock;
    private AlarmManager          alarmManager;
    private SharedPreferences     prefs;

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        prefs        = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        // 初始化通知渠道
        createNotificationChannel();

        // 初始化 WakeLock（带超时）
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TowerOps:MonitorWakeLock");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 立即提升为前台服务，防止被杀
        startForeground(NOTIF_ID, buildNotification("监控运行中，自动处理工单..."));

        // 确保 WakeLock 持有（超时自动释放兜底）
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }

        // 处理 AlarmManager 触发的轮询
        if (ACTION_ALARM_TRIGGER.equals(intent != null ? intent.getAction() : null)) {
            // 闹钟触发：执行一次任务，完成后再设置下一个闹钟
            if (monitorOn && !taskRunning) {
                runOnce();
            }
        } else {
            // 普通启动 or START_STICKY 重启：恢复上次的运行状态
            boolean wasRunning = prefs.getBoolean(PREF_RUNNING, false);
            if (wasRunning && !monitorOn) {
                monitorOn = true;
                if (!taskRunning) runOnce();
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelAlarm();
        pool.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ── 对外接口（MainActivity 调用）───────────────────────────────────────

    public void setCallback(ServiceCallback cb) {
        this.callback = cb;
    }

    public void setInterval(int minSec, int maxSec) {
        prefs.edit()
                .putInt(PREF_INT_MIN, minSec)
                .putInt(PREF_INT_MAX, maxSec)
                .apply();
    }

    public boolean isRunning() { return monitorOn; }

    public void startMonitor() {
        if (monitorOn) return;
        monitorOn = true;
        // 持久化：服务重启后自动恢复
        prefs.edit().putBoolean(PREF_RUNNING, true).apply();
        updateNotification("监控运行中，自动处理工单...");
        if (!taskRunning) runOnce();
    }

    public void stopMonitor() {
        monitorOn = false;
        prefs.edit().putBoolean(PREF_RUNNING, false).apply();
        cancelAlarm();
        updateNotification("监控已暂停");
    }

    // ── 核心轮询逻辑 ────────────────────────────────────────────────────────

    private void runOnce() {
        if (!monitorOn) return;
        taskRunning = true;

        // 确保 WakeLock 持有
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }

        pool.execute(new MonitorTask(new MonitorTask.MonitorCallback() {
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
                // 任务完成后，通过 AlarmManager 安排下次执行
                if (monitorOn) scheduleNextAlarm();
            }

            @Override
            public void onError(String msg) {
                updateNotification("错误：" + msg);
                if (callback != null) callback.onError(msg);
                taskRunning = false;
                // 出错也安排下次重试
                if (monitorOn) scheduleNextAlarm();
            }
        }));
    }

    /**
     * 用 AlarmManager 设置下次触发时间。
     * setExactAndAllowWhileIdle 在 Doze 模式（打盹/息屏）下也能准时触发。
     */
    private void scheduleNextAlarm() {
        int minSec = prefs.getInt(PREF_INT_MIN, 30);
        int maxSec = prefs.getInt(PREF_INT_MAX, 60);
        int delaySec = minSec + random.nextInt(Math.max(1, maxSec - minSec + 1));

        updateNotification("下次轮询：" + delaySec + "秒后");
        if (callback != null) {
            mainHandler.post(() -> {
                if (callback != null) callback.onNextRun(delaySec);
            });
        }

        // 释放 WakeLock，等待下次触发
        if (wakeLock.isHeld()) wakeLock.release();

        PendingIntent pi = buildAlarmPendingIntent();
        long triggerAt = SystemClock.elapsedRealtime() + delaySec * 1000L;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6+：setExactAndAllowWhileIdle 在 Doze 模式下也能触发
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
        Intent i = new Intent(this, MonitorService.class);
        i.setAction(ACTION_ALARM_TRIGGER);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 0, i, flags);
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

    // ── 静态工具：供外部组件启动服务 ─────────────────────────────────────────

    /** 从任意 Context 启动/唤醒监控服务 */
    public static void startSelf(Context ctx) {
        Intent i = new Intent(ctx, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
