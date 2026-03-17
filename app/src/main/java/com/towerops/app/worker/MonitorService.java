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
 * 前台服务 —— 定时轮询（精确可靠版 v3）
 *
 * ══════════════ 已修复的 Bug 清单 ══════════════
 *
 * [BUG-1] executor 双重 shutdown
 *   原代码：onAllDone/onError 回调里各 shutdown 一次，外面 execute() 后还多一次。
 *   后果：executor 提前关闭，MonitorTask 内部任务被 RejectedExecutionException 杀死，
 *         后台接单线程直接崩溃、静默退出。
 *   修复：executor.shutdown() 只在 onAllDone / onError 回调里各调一次，外面不再调。
 *
 * [BUG-2] callback 无快照 → NPE
 *   原代码：onOrdersReady/onStatusUpdate 直接用 `callback`，
 *           Activity onPause 把 callback 置 null 后，工作线程回调主线程时
 *           `callback.onXxx()` 抛 NullPointerException。
 *   后果：后台运行期间 callback=null，接单结果无法回传，还可能崩溃。
 *   修复：runOnce() 入口一次性取快照 `final ServiceCallback snap = this.callback`，
 *         所有回调用 snap，snap 为 null 时跳过 UI 更新，网络操作照常执行。
 *
 * [BUG-3] setInterval 只写 prefs，不重新调度
 *   原代码：setInterval() 只更新 SharedPreferences，不取消已排队的 timerRunnable，
 *           导致修改轮询间隔后要等当前倒计时跑完才生效。
 *   修复：改为 setIntervalAndReschedule()，立即 removeCallbacks + 用新间隔重新 post。
 *
 * [BUG-4] WakeLock 先 release 再 acquire 次序错乱
 *   原代码：scheduleNext() 里先 release，timerRunnable 触发 runOnce() 再 acquire，
 *           若服务被系统 kill 后 START_STICKY 重建，wakeLock 未 held 直接 release → 崩溃。
 *   修复：统一用 safeAcquireWakeLock / safeReleaseWakeLock，加 isHeld() 守卫。
 *
 * [BUG-5] onPause 置 callback=null，后台完成后 UI 感知慢
 *   原代码：onPause → setCallback(null)，Activity 重新进前台要重新 onResume 才能更新 UI。
 *   修复：改为静默模式 silentMode=true，保留 callback 引用，
 *         后台时跳过 UI 更新但网络操作全速运行，onResume 取消静默立即恢复显示。
 */
public class MonitorService extends Service {

    // ── 常量 ──────────────────────────────────────────────────────────────
    public static final String PREF_NAME         = "monitor_prefs";
    public static final String PREF_RUNNING      = "is_running";
    public static final String PREF_INT_MIN      = "interval_min";
    public static final String PREF_INT_MAX      = "interval_max";
    /**
     * 用户是否主动停止了监控。
     * true  = 用户点了"停止监控"按钮，重新打开 App 不自动恢复。
     * false = 正常运行中被系统杀死（START_STICKY 重建）或开机恢复，应继续轮询。
     * 首次安装默认 true，即首次打开不自动启动，必须人工点"开始监控"。
     */
    public static final String PREF_USER_STOPPED = "user_stopped";

    private static final String CHANNEL_ID  = "tower_ops_monitor";
    private static final int    NOTIF_ID    = 1001;
    private static final long   WAKELOCK_MS = 47 * 60 * 1000L; // 比最大任务超时（45分钟）多2分钟
    private static final long   WATCHDOG_MS = 46 * 60 * 1000L; // 看门狗：46分钟（与动态超时上限45分钟对齐）

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

    /**
     * UI 回调。
     * [BUG-5 修复] 后台时（Activity onPause）不置 null，
     * 改用 silentMode 旗标跳过 UI 更新，Activity onResume 后取消静默即可恢复。
     */
    private volatile ServiceCallback callback;
    private volatile boolean         silentMode = false;

    // ── 状态 ───────────────────────────────────────────────────────────────
    private volatile boolean taskRunning = false;
    private volatile long    taskStartAt = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random  random      = new Random();

    private PowerManager.WakeLock wakeLock;
    private SharedPreferences     prefs;

    // 定时 Runnable
    private final Runnable timerRunnable = () -> {
        if (isRunning()) runOnce();
    };

    // 看门狗：每30秒检测，超8分钟强制重置
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (taskRunning && taskStartAt > 0
                    && System.currentTimeMillis() - taskStartAt > WATCHDOG_MS) {
                taskRunning = false;
                taskStartAt = 0;
                safeReleaseWakeLock();
                updateNotification("任务超时已重置，等待下次轮询...");
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
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "TowerOps:MonitorWakeLock");
        wakeLock.setReferenceCounted(false);

        mainHandler.postDelayed(watchdog, 30_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("监控运行中，自动处理工单..."));

        // 服务重建时恢复登录凭据和配置（START_STICKY 进程重建后内存清空，必须从 prefs 恢复）
        Session.get().loadConfig(this);

        // 恢复逻辑：区分"用户主动停止"和"系统杀死重建"两种情况。
        // PREF_USER_STOPPED=true  → 用户手动停止过，不自动恢复，等用户点"开始监控"
        // PREF_USER_STOPPED=false → 系统 kill 后 START_STICKY 重建 / AlarmReceiver 拉起，
        //                           用户没有手动停，继续轮询。
        // 首次安装 PREF_USER_STOPPED 默认 true，打开 App 必须人工点按钮才能启动。
        boolean userStopped = prefs.getBoolean(PREF_USER_STOPPED, true);
        if (prefs.getBoolean(PREF_RUNNING, false) && !userStopped) {
            safeAcquireWakeLock();
            if (!taskRunning) {
                runOnce();
            }
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
        safeReleaseWakeLock();
    }

    // ── 对外接口（MainActivity 调用）───────────────────────────────────────

    /**
     * 注册 UI 回调。
     * [BUG-5 修复] silent=true 表示后台模式：保留引用但不推送 UI 更新。
     *
     * @param cb     回调对象（可为 null，null 时等同于 silent=true）
     * @param silent true=静默后台，false=前台正常显示
     */
    public void setCallback(ServiceCallback cb, boolean silent) {
        this.callback   = cb;
        this.silentMode = silent;
    }

    /**
     * [BUG-3 修复] 修改轮询间隔并立即重新调度。
     * 旧版 setInterval() 只写 prefs，不取消已排队的 timerRunnable，
     * 必须等当前倒计时跑完才生效——改为立即 removeCallbacks + 重新 post。
     */
    public void setIntervalAndReschedule(int minSec, int maxSec) {
        prefs.edit().putInt(PREF_INT_MIN, minSec).putInt(PREF_INT_MAX, maxSec).apply();
        if (!taskRunning && isRunning()) {
            mainHandler.removeCallbacks(timerRunnable);
            scheduleNext(randInterval());
        }
    }

    public boolean isRunning() {
        return prefs.getBoolean(PREF_RUNNING, false);
    }

    /** 返回下次轮询的时间戳（毫秒），供 MainActivity 恢复倒计时用 */
    public long getNextRunAt() {
        return prefs.getLong("next_run_at", 0);
    }

    public void startMonitor() {
        if (isRunning()) return;
        // 用户主动点击"开始监控"：清除手动停止标志，允许后续 START_STICKY 重建时自动恢复
        prefs.edit()
                .putBoolean(PREF_RUNNING, true)
                .putBoolean(PREF_USER_STOPPED, false)
                .apply();
        updateNotification("监控运行中，自动处理工单...");
        if (!taskRunning) {
            safeAcquireWakeLock();
            runOnce();
        }
    }

    public void stopMonitor() {
        // 用户主动点击"停止监控"：写入手动停止标志，重新打开 App 不自动恢复
        prefs.edit()
                .putBoolean(PREF_RUNNING, false)
                .putBoolean(PREF_USER_STOPPED, true)
                .apply();
        mainHandler.removeCallbacks(timerRunnable);
        cancelAlarm(); // 取消已排队的精确闹钟，防止停止后仍被唤醒
        // 取消 JobScheduler，防止停止后 Job 触发再把服务拉起来
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MonitorJobService.cancel(this);
        }
        taskRunning = false;
        taskStartAt = 0;
        safeReleaseWakeLock();
        updateNotification("监控已暂停");
    }

    // ── 核心：执行一轮任务 ─────────────────────────────────────────────────

    private void runOnce() {
        if (!isRunning()) return;
        if (taskRunning)  return;   // 防重入
        taskRunning = true;
        taskStartAt = System.currentTimeMillis();
        safeAcquireWakeLock();

        // [BUG-2 修复] 入口处一次性取快照，避免回调期间 callback 被置 null 引发 NPE
        final ServiceCallback snap = this.callback;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(new MonitorTask(new MonitorTask.MonitorCallback() {

            @Override
            public void onOrdersReady(List<WorkOrder> orders) {
                updateNotification("处理中：共 " + orders.size() + " 条工单");
                if (snap != null && !silentMode) {
                    mainHandler.post(() -> snap.onOrdersReady(orders));
                }
            }

            @Override
            public void onStatusUpdate(int rowIndex, String billsn, String content) {
                // 后台静默时：接单/反馈/回单的最终结果仍通过通知栏显示，过程状态静默
                if (silentMode) {
                    if (content.contains("接单成功") || content.contains("接单失败")
                            || content.contains("反馈成功") || content.contains("反馈完毕")
                            || content.contains("回单成功") || content.contains("回单失败")
                            || content.contains("服务器响应")) {
                        updateNotification(billsn + " " + content);
                    }
                } else if (snap != null) {
                    mainHandler.post(() -> snap.onStatusUpdate(rowIndex, billsn, content));
                }
            }

            @Override
            public void onAllDone() {
                // [BUG-1 修复] executor 只在这里 shutdown 一次
                executor.shutdown();
                int done  = Session.get().getFinished();
                int total = Session.get().getTotal();
                taskRunning = false;
                taskStartAt = 0;
                if (snap != null && !silentMode) {
                    mainHandler.post(() -> snap.onAllDone(done, total));
                }
                if (isRunning()) {
                    scheduleNext(randInterval());
                } else {
                    safeReleaseWakeLock();
                }
            }

            @Override
            public void onError(String msg) {
                // [BUG-1 修复] executor 只在这里 shutdown 一次
                executor.shutdown();
                updateNotification("错误：" + msg);
                taskRunning = false;
                taskStartAt = 0;
                if (snap != null && !silentMode) {
                    mainHandler.post(() -> snap.onError(msg));
                }
                if (isRunning()) {
                    scheduleNext(60); // 出错60秒后重试
                } else {
                    safeReleaseWakeLock();
                }
            }
        }));
        // ★ 不在这里 shutdown executor，让回调自己负责 shutdown ★
    }

    /**
     * 调度下次轮询。
     * 三保险策略：
     *   1. AlarmManager 精确唤醒（WAKE_UP 类型）—— 息屏/Doze 模式下也能按时触发，
     *      触发后由 AlarmReceiver 拉起服务并执行 runOnce()
     *   2. mainHandler.postDelayed 兜底 —— App 在前台时 Handler 更及时，两者取先到的
     *   3. JobScheduler 兜底 —— 系统级调度，不受 ROM 省电策略限制，精度 ±30s
     * WakeLock 在等待期间释放省电，各触发器触发时 runOnce 入口重新 acquire。
     */
    private void scheduleNext(int delaySec) {
        long nextAt = System.currentTimeMillis() + delaySec * 1000L;
        prefs.edit().putLong("next_run_at", nextAt).apply();
        updateNotification("下次轮询：" + delaySec + "秒后");
        dispatchNextRun(delaySec);
        safeReleaseWakeLock(); // 等待期间释放，省电

        // ── 方案1：AlarmManager 精确唤醒（息屏/Doze 下也能按时触发）──
        scheduleAlarm(delaySec);

        // ── 方案2：Handler 兜底（App 在前台时更及时）──
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.postDelayed(timerRunnable, delaySec * 1000L);

        // ── 方案3：JobScheduler 兜底（系统级调度，ROM 不会屏蔽）──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MonitorJobService.schedule(this, delaySec);
        }
    }

    /** 设置 AlarmManager 精确唤醒闹钟，由 AlarmReceiver 触发服务重新 runOnce */
    private void scheduleAlarm(int delaySec) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.setAction("com.towerops.app.ALARM_TRIGGER");
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, piFlags);

        long triggerAt = System.currentTimeMillis() + delaySec * 1000L;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setExactAndAllowWhileIdle：在 Doze 模式下也能精确触发
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) {
            // 部分设备/ROM 可能抛异常，Handler 兜底会顶上
            e.printStackTrace();
        }
    }

    /** 取消已排队的 AlarmManager 闹钟（停止监控时调用） */
    private void cancelAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.setAction("com.towerops.app.ALARM_TRIGGER");
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, piFlags);
        am.cancel(pi);
    }

    private void dispatchNextRun(int delaySec) {
        ServiceCallback snap = this.callback;
        if (snap != null && !silentMode) {
            mainHandler.post(() -> snap.onNextRun(delaySec));
        }
    }

    private int randInterval() {
        int min = prefs.getInt(PREF_INT_MIN, 90);
        int max = prefs.getInt(PREF_INT_MAX, 120);
        if (min <= 0) min = 90;
        if (max <= 0) max = 120;
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    // ── WakeLock 安全操作 ─────────────────────────────────────────────────

    private void safeAcquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_MS);
        }
    }

    private void safeReleaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
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
