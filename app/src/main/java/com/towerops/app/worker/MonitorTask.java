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
 * 关键修复：
 *   1. 接单的 taskId 直接从工单列表字段 taskid 取，不再单独请求详情页。
 *      对应易语言：taskId = json.取通用属性(basePath + "taskid")
 *   2. timeDiff1（反馈时差）从未反馈时 = 0，不满足阈值，不误触发。
 *   3. 每轮独立线程池，不复用外部 pool，彻底避免 RejectedExecutionException。
 */
public class MonitorTask implements Runnable {

    private static final int MAX_THREADS = 5; // 并发数降低，减少服务器压力

    public interface MonitorCallback {
        void onOrdersReady(List<WorkOrder> orders);
        void onStatusUpdate(int rowIndex, String billsn, String content);
        void onAllDone();
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
        String jsonStr = WorkOrderApi.getBillMonitorList();
        JSONObject root;
        try {
            root = new JSONObject(jsonStr);
            if (!"OK".equals(root.optString("status"))) {
                mainHandler.post(() -> callback.onError("获取工单列表失败：" + jsonStr));
                return;
            }
        } catch (Exception e) {
            mainHandler.post(() -> callback.onError("JSON解析失败：" + jsonStr));
            return;
        }

        JSONArray billList;
        try { billList = root.getJSONArray("billList"); }
        catch (Exception e) { billList = new JSONArray(); }

        int count = billList.length();
        if (count == 0) {
            mainHandler.post(() -> {
                callback.onOrdersReady(new ArrayList<>());
                callback.onAllDone();
            });
            return;
        }

        // ---- 2. 解析每条工单 ----
        List<WorkOrder> orders    = new ArrayList<>();
        String[]        taskPacks = new String[count + 1]; // 1-based

        for (int i = 0; i < count; i++) {
            try {
                JSONObject item = billList.getJSONObject(i);
                WorkOrder wo = new WorkOrder();
                wo.index       = i + 1;
                wo.billsn      = item.optString("billsn", "");
                wo.replyTime   = item.optString("reply_time", "");
                wo.createTime  = item.optString("createtime", "");
                wo.stationname = item.optString("stationname", "");
                wo.billtitle   = item.optString("billtitle", "");
                wo.billid      = item.optString("billid", "");
                // ★ 直接从列表取 taskid（对应易语言 taskId = json.取通用属性(basePath + "taskid")）★
                wo.taskId      = item.optString("taskid", "");
                if (wo.taskId.isEmpty() || "null".equalsIgnoreCase(wo.taskId)) {
                    wo.taskId = item.optString("taskId", ""); // 兼容大小写
                }

                wo.acceptOperator  = "";
                wo.dealInfo        = "";
                wo.lastOperateTime = "";

                // 解析 actionlist（接单人、反馈信息）
                JSONArray actionList;
                try { actionList = item.getJSONArray("actionlist"); }
                catch (Exception ex) { actionList = new JSONArray(); }

                for (int j = 0; j < actionList.length(); j++) {
                    JSONObject act = actionList.getJSONObject(j);
                    String taskStatusVal = act.optString("task_status_dictvalue", "");

                    // 接单人：ACCEPT 状态
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

                    // 最新反馈信息（追加描述 / 故障反馈）
                    String rawDeal = act.optString("deal_info", "");
                    if (rawDeal.contains("追加描述：") || rawDeal.contains("故障反馈：")) {
                        String marker = rawDeal.contains("追加描述：") ? "追加描述：" : "故障反馈：";
                        int start = rawDeal.indexOf(marker) + marker.length();
                        int end   = rawDeal.indexOf("。", start);
                        wo.dealInfo        = end > start ? rawDeal.substring(start, end) : rawDeal.substring(start);
                        wo.lastOperateTime = act.optString("operate_end_time", "");
                    }
                }

                // 告警状态（需要单独请求）
                String alarmStr = WorkOrderApi.getBillAlarmList(wo.billsn);
                wo.alertStatus = alarmStr.contains("alarmname") ? "告警中" : "已恢复";
                wo.alertTime   = "";
                try {
                    JSONObject alarmRoot = new JSONObject(alarmStr);
                    JSONArray alarmList  = alarmRoot.optJSONArray("alarmList");
                    if (alarmList == null) alarmList = alarmRoot.optJSONArray("list");
                    if (alarmList != null && alarmList.length() > 0) {
                        JSONObject first = alarmList.getJSONObject(alarmList.length() - 1);
                        String at = first.optString("alarm_time", "");
                        if (at.isEmpty()) at = first.optString("alarmTime", "");
                        if (at.isEmpty()) at = first.optString("occur_time", "");
                        if (at.isEmpty()) at = first.optString("occurTime", "");
                        if (!at.isEmpty()) wo.alertTime = at;
                    }
                } catch (Exception ignored) {}

                // ★ timeDiff 计算
                // timeDiff2：工单创建 → 现在（接单阈值判断用）
                // timeDiff1：上次反馈 → 现在（反馈阈值判断用），从未反馈=0
                wo.timeDiff2 = WorkOrderApi.minutesDiff(wo.createTime);
                wo.timeDiff1 = wo.lastOperateTime.isEmpty()
                        ? 0
                        : WorkOrderApi.minutesDiff(wo.lastOperateTime);

                wo.statusCol = "--排队等待处理中...";
                orders.add(wo);

                taskPacks[i + 1] = wo.billsn        + "\u0001"
                        + wo.stationname             + "\u0001"
                        + wo.billtitle               + "\u0001"
                        + wo.billid                  + "\u0001"
                        + wo.taskId                  + "\u0001"
                        + wo.acceptOperator          + "\u0001"
                        + wo.dealInfo                + "\u0001"
                        + wo.alertStatus             + "\u0001"
                        + wo.timeDiff1               + "\u0001"
                        + wo.timeDiff2               + "\u0001"
                        + wo.alertTime               + "\u0001"
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
        AtomicInteger remaining = new AtomicInteger(count);
        s.resetProgress(count);

        // ---- 5. 派发工作线程（本轮独立线程池）----
        ExecutorService localPool = Executors.newFixedThreadPool(MAX_THREADS);
        WorkerTask.UiCallback uiCb = (rowIndex, billsn, content) ->
                mainHandler.post(() -> { if (callback != null) callback.onStatusUpdate(rowIndex, billsn, content); });

        for (int i = 1; i <= count; i++) {
            final int idx = i;
            localPool.execute(() -> {
                try {
                    new WorkerTask(idx, uiCb).run();
                } finally {
                    remaining.decrementAndGet();
                }
            });
        }

        // ---- 6. 等待全部完成（最长 6 分钟兜底）----
        localPool.shutdown();
        try {
            boolean finished = localPool.awaitTermination(6, TimeUnit.MINUTES);
            if (!finished) localPool.shutdownNow();
        } catch (InterruptedException e) {
            localPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // ---- 7. 智能时间段控制（夜间02-05点关闭自动操作）----
        applyTimeSchedule();

        mainHandler.post(callback::onAllDone);
    }

    /**
     * 夜间时段（02:00~05:59）自动关闭反馈/接单，保留回单。
     */
    private void applyTimeSchedule() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        Session s = Session.get();
        if (s.appConfig.isEmpty()) return;
        String[] parts = s.appConfig.split("\u0001", -1);
        if (parts.length < 3) return;

        boolean nightMode = (hour >= 2 && hour < 6);

        if (nightMode) {
            parts[0] = "false"; // 关反馈
            parts[1] = "false"; // 关接单
        } else {
            parts[0] = "true";
            parts[1] = "true";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i]);
            if (i < parts.length - 1) sb.append("\u0001");
        }
        s.appConfig = sb.toString();
    }
}
