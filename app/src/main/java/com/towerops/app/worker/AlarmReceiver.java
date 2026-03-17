package com.towerops.app.worker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;

/**
 * AlarmReceiver —— 接收 AlarmManager 精确唤醒广播，保证息屏/Doze 模式下轮询按时触发。
 *
 * 工作流程：
 *   scheduleNext() 设定精确闹钟 → 系统唤醒 CPU → onReceive() 先拿 WakeLock → 再启动服务
 *
 * 防重入：只有 PREF_RUNNING=true 且服务确实需要继续时才拉起，
 *   Service 内部的 runOnce() 有 taskRunning 守卫，不会真正双触发。
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static volatile PowerManager.WakeLock sWakeLock;

    @Override
    public void onReceive(Context context, Intent intent) {
        // 先拿 WakeLock，防止 CPU 在 startForegroundService 完成前再次休眠
        acquireWakeLock(context);

        // 双重检查：PREF_RUNNING=true 且用户没有主动停止，才拉起服务继续轮询
        // PREF_USER_STOPPED=true 表示用户手动点了停止，不自动恢复
        SharedPreferences prefs = context.getSharedPreferences(
                MonitorService.PREF_NAME, Context.MODE_PRIVATE);
        boolean running     = prefs.getBoolean(MonitorService.PREF_RUNNING,      false);
        boolean userStopped = prefs.getBoolean(MonitorService.PREF_USER_STOPPED, true);
        if (running && !userStopped) {
            MonitorService.startSelf(context);
        } else {
            // 监控已停止或用户主动停止，释放 WakeLock 即可
            releaseWakeLock();
        }
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
        if (!sWakeLock.isHeld()) {
            sWakeLock.acquire(60 * 1000L); // 最多持有60秒，服务启动后自己管理 WakeLock
        }
    }

    public static synchronized void releaseWakeLock() {
        if (sWakeLock != null && sWakeLock.isHeld()) {
            sWakeLock.release();
        }
    }
}
