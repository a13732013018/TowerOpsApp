package com.towerops.app.worker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 开机自启接收器
 * 手机重启后，如果上次监控是开启状态，自动拉起 MonitorService
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences(
                    MonitorService.PREF_NAME, Context.MODE_PRIVATE);
            boolean wasRunning = prefs.getBoolean(MonitorService.PREF_RUNNING, false);

            if (wasRunning) {
                MonitorService.startSelf(context);
            }
        }
    }
}
