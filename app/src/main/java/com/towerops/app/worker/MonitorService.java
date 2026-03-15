package com.towerops.app.worker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
 * 前台服务 —— 保活核心
 * 在通知栏常驻，持有 WakeLock，保证 CPU 不休眠，24 小时自动轮询工单
 */
public class MonitorService extends Service {

    private static final String CHANNEL_ID   = "tower_ops_monitor";
    private static final int    NOTIF_ID     = 1001;

    // 对外暴露的 Binder，供 MainActivity 绑定后调用
    public class LocalBinder extends Binder {
        public MonitorService getService() { return MonitorService.this; }
    }
    private final IBinder binder = new LocalBinder();

    // 状态回调（绑定后由 MainActivity 设置）
    public interface ServiceCallback {
        void onOrdersReady(List<WorkOrder> orders);
        void onStatusUpdate(int rowIndex, String billsn, String content);
        void onAllDone(int done, int total);
        void onError(String msg);
        void onNextRun(int delaySec);
    }
    private volatile ServiceCallback callback;

    private volatile boolean  running = false;
    private final Handler     handler = new Handler(Looper.getMainLooper());
    private Runnable          nextRunnable;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Random      random  = new Random();

    // WakeLock：防止 CPU 休眠
    private PowerManager.WakeLock wakeLock;

    // 轮询间隔（秒），由 MainActivity 通过 setInterval 传入
    private int intervalMinSec = 30;
    private int intervalMaxSec = 60;

    // ─────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 申请 WakeLock（PARTIAL：只保持 CPU，不保持屏幕）
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TowerOps:MonitorWakeLock");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 启动为前台服务，显示常驻通知
        startForeground(NOTIF_ID, buildNotification("监控运行中，自动处理工单..."));
        // 获取 WakeLock
        if (!wakeLock.isHeld()) wakeLock.acquire();
        return START_STICKY;   // 被杀后系统自动重启
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (nextRunnable != null) handler.removeCallbacks(nextRunnable);
        pool.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ─────────────────────────────────────────────
    // 对外接口（MainActivity 调用）
    // ─────────────────────────────────────────────

    public void setCallback(ServiceCallback cb) {
        this.callback = cb;
    }

    public void setInterval(int minSec, int maxSec) {
        this.intervalMinSec = minSec;
        this.intervalMaxSec = maxSec;
    }

    public boolean isRunning() {
        return running;
    }

    public void startMonitor() {
        if (running) return;
        running = true;
        updateNotification("监控运行中，自动处理工单...");
        runOnce();
    }

    public void stopMonitor() {
        running = false;
        if (nextRunnable != null) handler.removeCallbacks(nextRunnable);
        updateNotification("监控已暂停");
    }

    // ─────────────────────────────────────────────
    // 轮询逻辑（从 MainActivity 移过来）
    // ─────────────────────────────────────────────

    private void runOnce() {
        if (!running) return;

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
                syncConfigFromSession();
                int done  = Session.get().getFinished();
                int total = Session.get().getTotal();
                if (callback != null) callback.onAllDone(done, total);
                if (running) scheduleNext();
            }

            @Override
            public void onError(String msg) {
                updateNotification("错误：" + msg);
                if (callback != null) callback.onError(msg);
                if (running) scheduleNext();
            }
        }));
    }

    private void scheduleNext() {
        int delaySec = intervalMinSec + random.nextInt(
                Math.max(1, intervalMaxSec - intervalMinSec + 1));
        updateNotification("下次轮询：" + delaySec + "秒后");
        if (callback != null) callback.onNextRun(delaySec);

        nextRunnable = this::runOnce;
        handler.postDelayed(nextRunnable, delaySec * 1000L);
    }

    private void syncConfigFromSession() {
        // 智能时段逻辑已在 MonitorTask 内部处理，这里无需重复
    }

    // ─────────────────────────────────────────────
    // 通知相关
    // ─────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "工单监控", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台自动接单/反馈/回单");
            ch.setSound(null, null);          // 静音，不打扰
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("铁塔工单监控")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)          // 不可手动划掉
                .setSilent(true)
                .build();
    }

    private void updateNotification(String text) {
        Notification n = buildNotification(text);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, n);
    }
}
