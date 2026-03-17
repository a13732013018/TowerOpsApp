package com.towerops.app.worker;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * JobScheduler 保活兜底 —— 作为 AlarmManager 的第三道保险。
 *
 * 调度策略：
 *   - AlarmManager（第1道）：精确唤醒，息屏/Doze 下触发，部分 ROM 会延迟
 *   - Handler.postDelayed（第2道）：App 在前台时更及时
 *   - JobScheduler（第3道）：系统级调度，不受省电策略限制，但触发时间不精确（±30s 内）
 *
 * 使用方式：
 *   MonitorJobService.schedule(context, delaySec) 替代或补充 AlarmManager
 *   MonitorJobService.cancel(context)            停止监控时取消
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MonitorJobService extends JobService {

    private static final int JOB_ID = 10086;

    /**
     * 调度一个延迟执行的 Job，触发时拉起 MonitorService。
     *
     * @param context  上下文
     * @param delaySec 最少延迟秒数（JobScheduler 会在 delaySec ~ delaySec+30 秒内触发）
     */
    public static void schedule(Context context, int delaySec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        long minDelayMs = delaySec * 1000L;
        // overrideDeadline：最多多等30秒，让系统批量调度省电
        long deadlineMs = minDelayMs + 30_000L;

        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, MonitorJobService.class))
                .setMinimumLatency(minDelayMs)
                .setOverrideDeadline(deadlineMs)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) // 需要有网络
                .setPersisted(true)           // 重启后自动恢复（需要 RECEIVE_BOOT_COMPLETED 权限）
                .build();

        js.schedule(job);
    }

    /**
     * 取消已调度的 Job（停止监控时调用）。
     */
    public static void cancel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // Job 触发：检查用户是否主动停止，没有则拉起 MonitorService
        SharedPreferences prefs = getSharedPreferences(
                MonitorService.PREF_NAME, Context.MODE_PRIVATE);
        boolean running     = prefs.getBoolean(MonitorService.PREF_RUNNING,      false);
        boolean userStopped = prefs.getBoolean(MonitorService.PREF_USER_STOPPED, true);

        if (running && !userStopped) {
            MonitorService.startSelf(this);
        }

        // 返回 false 表示任务已在 onStartJob 里完成，不需要异步回调
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 系统取消 Job（如网络断开），返回 true 表示希望重新调度
        return true;
    }
}
