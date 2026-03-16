package com.towerops.app.worker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * AlarmReceiver —— 保留兼容（新版已不使用 AlarmManager，此类仅作备用）
 * 如果系统因某种原因触发了旧广播，直接拉起服务即可。
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static volatile PowerManager.WakeLock sWakeLock;

    @Override
    public void onReceive(Context context, Intent intent) {
        acquireWakeLock(context);
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
        if (!sWakeLock.isHeld()) {
            sWakeLock.acquire(60 * 1000L);
        }
    }

    public static synchronized void releaseWakeLock() {
        if (sWakeLock != null && sWakeLock.isHeld()) {
            sWakeLock.release();
        }
    }
}
