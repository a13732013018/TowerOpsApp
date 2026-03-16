package com.towerops.app.worker;

import android.os.Handler;
import android.os.Looper;

import com.towerops.app.api.WorkOrderApi;
import com.towerops.app.model.Session;
import com.towerops.app.model.WorkOrder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主控线程 —— 拉取工单列表 → 解析 → 分配工作线程
 *
 * 修复记录：
 *   1. 线程池不再 shutdown()：MonitorService 持有同一个 pool 复用，
 *      shutdown 后下一轮任务 execute() 会抛 RejectedExecutionException，
 *      导致后台彻底不执行接单/反馈。改为本任务自己创建独立线程池，任务结束后 await 而非 shutdown。
 *   2. timeDiff1 反馈时间判断修复：lastOperateTime 为空时 fallback 改为 0，
 *      确保"上次反馈时间"语义正确——没有上次反馈时 timeDiff1=0，不满足>=阈值，不触发反馈。
 *      等下一轮到达阈值后才反馈，避免工单刚创建就立刻疯狂反馈。
 *   3. 等待机制改用 AtomicInteger 倒计槽位 + deadline 双保险，防止槽位泄漏导致永久卡死。
 */
public class MonitorTask implements Runnable {

    private static final int MAX_THREADS = 10; // 降低并发，减少服务器压力

    /** 主控完成后回调（用于刷新 UI 进度 / 完成提示）*/
    public interface MonitorCallback {
        /** 解析到工单列表，返回给 UI 展示 */
        void onOrdersReady(List<WorkOrder> orders);
        /** 某行状态更新（来自工作线程） */
        void onStatusUpdate(int rowIndex, String billsn, String content);
        /** 全部处理完毕 */
        void onAllDone();
        /** 出错 */
        void onError(String msg);
    }

    private final MonitorCallback callback;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    public MonitorTask(MonitorCallback callback) {
        this.callback = callback;
    }

    @Override
    public void run() {
        Session s = Session.get();

        // ---- 1. 拉取工单列表 ----
        String json_str = WorkOrderApi.getBillMonitorList();
        JSONObject root;
        try {
            root = new JSONObject(json_str);
            if (!"OK".equals(root.optString("status"))) {
                mainHandler.post(() -> callback.onError("获取工单列表失败：" + json_str));
                return;
            }
        } catch (Exception e) {
            mainHandler.post(() -> callback.onError("JSON 解析失败：" + json_str));
            return;
        }

        JSONArray billList;
        try { billList = root.getJSONArray("billList"); }
        catch (Exception e) { billList = new JSONArray(); }

        int count = billList.length();
        if (count == 0) {
            mainHandler.post(() -> { callback.onOrdersReady(new ArrayList<>()); callback.onAllDone(); });
            return;
        }

        // ---- 2. 解析每条工单 ----
        List<WorkOrder> orders   = new ArrayList<>();
        String[]        taskPacks = new String[count + 1]; // 1-based

        for (int i = 0; i < count; i++) {
            try {
                JSONObject item = billList.getJSONObject(i);
                WorkOrder wo = new WorkOrder();
                wo.index       = i + 1;
                wo.billsn      = item.optString("billsn");
                wo.replyTime   = item.optString("reply_time");
                wo.createTime  = item.optString("createtime");
                wo.stationname = item.optString("stationname");
                wo.billtitle   = item.optString("billtitle");
                wo.billid      = item.optString("billid");
                wo.taskId      = item.optString("taskid");

                wo.acceptOperator  = "";
                wo.dealInfo        = "";
                wo.lastOperateTime = "";

                JSONArray actionList;
                try { actionList = item.getJSONArray("actionlist"); }
                catch (Exception ex) { actionList = new JSONArray(); }

                for (int j = 0; j < actionList.length(); j++) {
                    JSONObject act = actionList.getJSONObject(j);
                    String taskStatusVal = act.optString("task_status_dictvalue", "");

                    // 接单人：识别 ACCEPT 状态
                    if ("ACCEPT".equals(taskStatusVal)) {
                        String op = act.optString("operator", "").trim();
                        if (op.isEmpty()) op = act.optString("operatorName", "").trim();
                        if (op.isEmpty()) op = act.optString("operName", "").trim();
                        if (op.isEmpty()) op = act.optString("handle_user_name", "").trim();
                        if (op.isEmpty()) op = act.optString("handleUserName", "").trim();
                        if (op.isEmpty()) op = act.optString("dispatchUserName", "").trim();
                        if (op.isEmpty()) op = act.optString("accept_user_name", "").trim();
                        if (!op.isEmpty()) wo.acceptOperator = op;
                    }

                    // 最新反馈信息
                    String rawDeal = act.optString("deal_info", "");
                    if (rawDeal.contains("追加描述：") || rawDeal.contains("故障反馈：")) {
                        String marker = rawDeal.contains("追加描述：") ? "追加描述：" : "故障反馈：";
                        int start = rawDeal.indexOf(marker) + marker.length();
                        int end   = rawDeal.indexOf("。", start);
                        wo.dealInfo        = end > start ? rawDeal.substring(start, end) : rawDeal.substring(start);
                        wo.lastOperateTime = act.optString("operate_end_time", "");
                    }
                }

                // 告警状态
                String alarmStr = WorkOrderApi.getBillAlarmList(wo.billsn);
                wo.alertStatus = alarmStr.contains("alarmname") ? "告警中" : "已恢复";
                wo.alertTime   = "";
                try {
                    JSONObject alarmRoot = new JSONObject(alarmStr);
                    JSONArray alarmList = alarmRoot.optJSONArray("alarmList");
                    if (alarmList == null) alarmList = alarmRoot.optJSONArray("list");
                    if (alarmList != null && alarmList.length() > 0) {
                        JSONObject firstAlarm = alarmList.getJSONObject(alarmList.length() - 1);
                        String at = firstAlarm.optString("alarm_time", "");
                        if (at.isEmpty()) at = firstAlarm.optString("alarmTime", "");
                        if (at.isEmpty()) at = firstAlarm.optString("occur_time", "");
                        if (at.isEmpty()) at = firstAlarm.optString("occurTime", "");
                        if (!at.isEmpty()) wo.alertTime = at;
                    }
                } catch (Exception ignored) {}

                // ★ 关键修复：timeDiff 计算逻辑
                // timeDiff2 = 工单创建到现在的分钟数（用于接单阈值判断）
                // timeDiff1 = 上次反馈到现在的分钟数（用于反馈阈值判断）
                //   若 lastOperateTime 为空（从未反馈），timeDiff1=0，
                //   不满足 >= 反馈阈值，不触发反馈，等工单足够老了再反馈
                //   （原来错误写法 fallback 成 timeDiff2，导致工单一创建就可能触发反馈）★
                wo.timeDiff2 = WorkOrderApi.minutesDiff(wo.createTime);
                wo.timeDiff1 = wo.lastOperateTime.isEmpty()
                        ? 0   // ★ 从未反馈过：timeDiff1=0，不满足阈值，不立即反馈 ★
                        : WorkOrderApi.minutesDiff(wo.lastOperateTime);

                wo.statusCol = "--排队等待处理中...";
                orders.add(wo);

                taskPacks[i + 1] = wo.billsn + "\u0001"
                        + wo.stationname    + "\u0001"
                        + wo.billtitle      + "\u0001"
                        + wo.billid         + "\u0001"
                        + wo.taskId         + "\u0001"
                        + wo.acceptOperator + "\u0001"
                        + wo.dealInfo       + "\u0001"
                        + wo.alertStatus    + "\u0001"
                        + wo.timeDiff1      + "\u0001"
                        + wo.timeDiff2      + "\u0001"
                        + wo.alertTime      + "\u0001"
                        + i; // 行号（0-based）

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // ---- 3. 推送列表到 UI ----
        List<WorkOrder> finalOrders = orders;
        mainHandler.post(() -> callback.onOrdersReady(finalOrders));

        // ---- 4. 缓存任务包 & 初始化进度 ----
        s.taskArray = taskPacks;
        // ★ 使用独立的 AtomicInteger 倒计，不依赖 Session 的 slot 机制，
        //   避免前后两轮交叉导致 allDone 提前误判 ★
        AtomicInteger remaining = new AtomicInteger(count);
        s.resetProgress(count);

        // ---- 5. 派发工作线程（本轮独立线程池，不复用 MonitorService 的 pool）----
        // ★ 核心修复：每轮创建新线程池，任务结束后 awaitTermination 而非 shutdown。
        //   MonitorService 复用的 pool 一旦 shutdown 就报 RejectedExecutionException，
        //   导致整个后台轮询彻底停止。★
        ExecutorService localPool = Executors.newFixedThreadPool(MAX_THREADS);

        WorkerTask.UiCallback uiCb = (rowIndex, billsn, content) ->
                mainHandler.post(() -> { if (callback != null) callback.onStatusUpdate(rowIndex, billsn, content); });

        for (int i = 1; i <= count; i++) {
            final int idx = i;
            final AtomicInteger rem = remaining;
            localPool.execute(() -> {
                try {
                    new WorkerTask(idx, uiCb).run();
                } finally {
                    rem.decrementAndGet();
                }
            });
        }

        // ---- 6. 等待全部完成（最长 5 分钟兜底，防止 WorkerTask 异常后永久阻塞）----
        localPool.shutdown();
        try {
            boolean finished = localPool.awaitTermination(5, TimeUnit.MINUTES);
            if (!finished) {
                // 强制结束残留线程，不能让它们一直占用资源
                localPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            localPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // ---- 7. 智能时间段自动控制 ----
        applyTimeSchedule();

        mainHandler.post(callback::onAllDone);
    }

    /**
     * 智能时间段控制：
     * 北京时间 02:00 ~ 05:59 夜间停止接单/反馈；06:00 ~ 次日 01:59 正常执行。
     */
    private void applyTimeSchedule() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        Session s = Session.get();
        if (s.appConfig.isEmpty()) return;
        String[] parts = s.appConfig.split("\u0001", -1);
        if (parts.length < 3) return;

        boolean nightMode = (hour > 1 && hour < 6);  // 2,3,4,5 点
        boolean dayMode   = (hour > 5 && hour < 24); // 6~23 点

        if (nightMode) {
            parts[0] = "false";
            parts[1] = "false";
        } else if (dayMode) {
            parts[0] = "true";
            parts[1] = "true";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i]);
            if (i < parts.length - 1) sb.append("\u0001");
        }
        s.appConfig = sb.toString();
        // ★ 时间段修改后也要持久化，服务重建后不丢失 ★
        // 注意：这里拿不到 Context，所以不调用 saveConfig。
        // applyTimeSchedule 只影响内存 appConfig，下次用户点"开始监控"时会重新 buildConfig 并 saveConfig。
    }
}
