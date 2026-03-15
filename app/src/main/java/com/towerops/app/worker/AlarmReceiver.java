package com.towerops.app.worker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * AlarmManager 触发接收器
 *
 * 为什么要用 BroadcastReceiver 而不是直接 startService？
 * ──────────────────────────────────────────────────────
 * 国内厂商 ROM（小米/华为/OPPO/vivo）对后台服务有额外限制，
 * 直接 PendingIntent.getService() 在 Doze 模式下可能被延迟或吞掉。
 *
 * BroadcastReceiver.onReceive() 由系统广播调度器直接调用，
 * 优先级高于普通服务启动，且可以在 onReceive 里立刻拿 WakeLock，
 * 确保 CPU 唤醒后再去启动服务，不会因为服务启动延迟而让 CPU 重新睡眠。
 */
public class AlarmReceiver extends BroadcastReceiver {

    // 静态 WakeLock：onReceive 结束后 Context 可能销毁，用静态保持
    private static volatile PowerManager.WakeLock sWakeLock;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!MonitorService.ACTION_ALARM_TRIGGER.equals(intent.getAction())) return;

        // 立刻拿 WakeLock，防止 CPU 在服务启动前再次睡眠
        acquireWakeLock(context);

        // 启动/唤醒前台服务，服务里执行完任务后会安排下一次 Alarm 并释放 WakeLock
        MonitorService.startSelf(context);
    }

    private static synchronized void acquireWakeLock(Context ctx) {
        if (sWakeLock == null) {
            PowerManager pm = (PowerManager) ctx.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            sWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "TowerOps:AlarmReceiverWakeLock");
            sWakeLock.setReferenceCounted(false);
        }
        // 最多持有 60 秒，服务启动后会接管 WakeLock
        if (!sWakeLock.isHeld()) {
            sWakeLock.acquire(60 * 1000L);
        }
    }

    /** 由 MonitorService 在任务开始后调用，释放接收器的临时 WakeLock */
    public static synchronized void releaseWakeLock() {
        if (sWakeLock != null && sWakeLock.isHeld()) {
            sWakeLock.release();
        }
    }
}
