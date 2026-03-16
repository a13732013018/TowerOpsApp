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

/**
 * 主控线程 —— 对应易语言 子程序_主控_APP工单分配
 * 负责：拉取工单列表 → 解析 → 分配工作线程
 */
public class MonitorTask implements Runnable {

    private static final int MAX_THREADS = 30;

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
    // 线程池：最多 MAX_THREADS 条并发
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_THREADS);

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
        List<WorkOrder> orders = new ArrayList<>();
        String[] taskPacks = new String[count + 1]; // 1-based

        for (int i = 0; i < count; i++) {
            try {
                JSONObject item = billList.getJSONObject(i);
                WorkOrder wo = new WorkOrder();
                wo.index          = i + 1;
                wo.billsn         = item.optString("billsn");
                wo.replyTime      = item.optString("reply_time");
                wo.createTime     = item.optString("createtime");
                wo.stationname    = item.optString("stationname");
                wo.billtitle      = item.optString("billtitle");
                wo.billid         = item.optString("billid");
                wo.taskId         = item.optString("taskid");

                wo.acceptOperator  = "";
                wo.dealInfo        = "";
                wo.lastOperateTime = "";

                JSONArray actionList;
                try { actionList = item.getJSONArray("actionlist"); }
                catch (Exception ex) { actionList = new JSONArray(); }

                for (int j = 0; j < actionList.length(); j++) {
                    JSONObject act = actionList.getJSONObject(j);
                    String taskStatusVal = act.optString("task_status_dictvalue", "");

                    // ── 接单人：识别 ACCEPT 状态 ──────────────────────────────
                    // 多字段名兼容，trim()去除服务器可能返回的前后空格
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

                    // ── 最新反馈信息 ─────────────────────────────────────────
                    String rawDeal = act.optString("deal_info", "");
                    if (rawDeal.contains("追加描述：") || rawDeal.contains("故障反馈：")) {
                        String marker = rawDeal.contains("追加描述：") ? "追加描述：" : "故障反馈：";
                        int start = rawDeal.indexOf(marker) + marker.length();
                        int end   = rawDeal.indexOf("。", start);
                        wo.dealInfo        = end > start ? rawDeal.substring(start, end) : rawDeal.substring(start);
                        wo.lastOperateTime = act.optString("operate_end_time", "");
                    }
                }

                // ── 接单人兜底：若actionList里没有ACCEPT，尝试工单顶层字段 ──
                if (wo.acceptOperator.isEmpty()) {
                    String op = item.optString("accept_user_name", "").trim();
                    if (op.isEmpty()) op = item.optString("acceptUserName", "").trim();
                    if (op.isEmpty()) op = item.optString("acceptOperator", "").trim();
                    if (!op.isEmpty()) wo.acceptOperator = op;
                }

                // 告警状态 + 解析最早告警时间
                String alarmStr = WorkOrderApi.getBillAlarmList(wo.billsn);
                wo.alertStatus = alarmStr.contains("alarmname") ? "告警中" : "已恢复";
                wo.alertTime   = "";
                try {
                    JSONObject alarmRoot = new JSONObject(alarmStr);
                    JSONArray alarmList = alarmRoot.optJSONArray("alarmList");
                    if (alarmList == null) alarmList = alarmRoot.optJSONArray("list");
                    if (alarmList != null && alarmList.length() > 0) {
                        // 取最早一条告警的发生时间
                        JSONObject firstAlarm = alarmList.getJSONObject(alarmList.length() - 1);
                        String at = firstAlarm.optString("alarm_time", "");
                        if (at.isEmpty()) at = firstAlarm.optString("alarmTime", "");
                        if (at.isEmpty()) at = firstAlarm.optString("occur_time", "");
                        if (at.isEmpty()) at = firstAlarm.optString("occurTime", "");
                        if (!at.isEmpty()) wo.alertTime = at;
                    }
                } catch (Exception ignored) {}

                // 时间差
                wo.timeDiff2 = WorkOrderApi.minutesDiff(wo.createTime);
                wo.timeDiff1 = wo.lastOperateTime.isEmpty()
                        ? wo.timeDiff2
                        : WorkOrderApi.minutesDiff(wo.lastOperateTime);

                wo.statusCol = "--排队等待处理中...";
                orders.add(wo);

                // 打包任务参数（\u0001 分隔，1-based 索引）
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
        s.resetProgress(count);

        // ---- 5. 派发工作线程 ----
        // ★ 必须对 callback 做 null 检查：APP 退到后台时 callback==null，
        //   若直接调用会在 Handler Runnable 里抛 NPE，虽不影响 NET_LOCK 释放，
        //   但会导致后续所有 postUi 静默失败，接单状态无法更新到 UI ★
        WorkerTask.UiCallback uiCb = (rowIndex, billsn, content) ->
                mainHandler.post(() -> { if (callback != null) callback.onStatusUpdate(rowIndex, billsn, content); });

        for (int i = 1; i <= count; i++) {
            final int idx = i;
            // 等待槽位
            while (!s.tryAcquireSlot(MAX_THREADS)) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }
            pool.execute(new WorkerTask(idx, uiCb));
        }

        // ---- 6. 等待全部完成（最长等 8 分钟，防止 WorkerTask 异常后永久阻塞）----
        long deadline = System.currentTimeMillis() + 8 * 60 * 1000L;
        while (!s.allDone() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        pool.shutdown();

        // ---- 7. 智能时间段自动控制 ----
        applyTimeSchedule();

        mainHandler.post(callback::onAllDone);
    }

    /**
     * 智能时间段控制（完全对应原易语言逻辑）：
     *
     * 原代码含义：
     *   if (1 < hour && hour < 6)  → hour == 2,3,4,5  → 关闭自动接单、自动反馈
     *   if (5 < hour && hour < 24) → hour == 6..23    → 开启自动接单、自动反馈
     *
     * 即：北京时间 02:00 ~ 05:59 夜间停止；06:00 ~ 次日 01:59 正常执行。
     *
     * 注意：每轮执行完都会重新判断，所以 06:00 后下一轮自动恢复。
     * 回单（cbRevert）不受时段控制，用户手动设置保持不变。
     */
    private void applyTimeSchedule() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        Session s = Session.get();
        if (s.appConfig.isEmpty()) return;
        String[] parts = s.appConfig.split("\u0001", -1);
        if (parts.length < 3) return;

        boolean nightMode = (hour > 1 && hour < 6); // 2,3,4,5 点 → 夜间
        boolean dayMode   = (hour > 5 && hour < 24); // 6~23 点 → 白天/傍晚

        if (nightMode) {
            // 夜间：关闭自动反馈 + 自动接单，回单不动
            parts[0] = "false";
            parts[1] = "false";
        } else if (dayMode) {
            // 白天（含 0、1 点不在上面两个区间，保持用户设置不变；
            //        6~23 点恢复开启，与原逻辑一致）
            parts[0] = "true";
            parts[1] = "true";
        }
        // hour == 0 或 1：两个条件都不满足，不修改用户配置（与原逻辑一致）

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i]);
            if (i < parts.length - 1) sb.append("\u0001");
        }
        s.appConfig = sb.toString();
    }
}
