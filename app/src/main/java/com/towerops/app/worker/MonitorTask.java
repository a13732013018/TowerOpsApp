package com.towerops.app.worker;

import android.os.Handler;
import android.os.Looper;

import com.towerops.app.api.WorkOrderApi;
import com.towerops.app.model.Session;
import com.towerops.app.model.WorkOrder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 主控线程 —— 拉取工单列表 → 解析 → 并发派发工作线程
 *
 * ══════════════ 已修复的 Bug 清单 ══════════════
 *
 * [BUG-6] applyTimeSchedule 在白天强制把 parts[0/1] 改为 "true"
 *   原代码：夜间 02~06 点设为 false，白天无条件恢复为 true，
 *           用户手动关掉反馈/接单后，下一轮完成时被强制打开，完全无视用户意图。
 *   修复：彻底删除 applyTimeSchedule()，夜间静默逻辑移到 WorkerTask 里按需判断。
 *
 * [BUG-7] getBillAlarmList 在解析循环里串行调用
 *   原代码：for (int i=0; i<count; i++) { ... getBillAlarmList(billsn) ... }
 *           N 条工单 = N 次串行网络请求，100条工单可能需要100×2s=200s，极易 ANR。
 *           更严重：告警信息在解析阶段（主控线程）同步获取，占用了 MonitorTask 的执行时间，
 *           导致 awaitTermination 超时、工单被批量丢弃。
 *   修复：告警信息移到 WorkerTask 里按需（回单场景才需要）并发获取，
 *         MonitorTask 只做本地 JSON 解析，默认 alertStatus="未知"。
 *
 * [BUG-8] remaining AtomicInteger 只减不等
 *   原代码：remaining.decrementAndGet() 在 finally 里，但从未被 wait/检查，
 *           是彻底无效的死代码，且和 Session.releaseSlot() 计数冲突（双重计数）。
 *   修复：删除 remaining，改用 localPool.awaitTermination() 等待全部线程完成。
 *
 * [BUG-10] releaseSlot 双重调用
 *   原代码：MonitorTask 的 localPool.execute(lambda) 里包着 finally { s.releaseSlot() }，
 *           WorkerTask.run() 的 finally 里也有 s.releaseSlot()，
 *           同一个 slot 被释放两次，finishedCount 计数翻倍，allDone() 提前返回 true。
 *   修复：删除 MonitorTask lambda 里的 releaseSlot()，只保留 WorkerTask.run() 里的。
 */
public class MonitorTask implements Runnable {

    private static final int MAX_THREADS = 5;

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

        // ── Step 1：拉取工单监控列表 ─────────────────────────────────────
        String jsonStr;
        JSONArray billList;
        try {
            jsonStr = WorkOrderApi.getBillMonitorList();
            JSONObject root = new JSONObject(jsonStr);
            if (!"OK".equals(root.optString("status"))) {
                String msg = "获取工单列表失败：" + jsonStr;
                mainHandler.post(() -> callback.onError(msg));
                return;
            }
            billList = root.optJSONArray("billList");
            if (billList == null) billList = new JSONArray();
        } catch (Exception e) {
            String msg = "JSON解析失败：" + e.getMessage();
            mainHandler.post(() -> callback.onError(msg));
            return;
        }

        int count = billList.length();
        if (count == 0) {
            mainHandler.post(() -> {
                callback.onOrdersReady(new ArrayList<>());
                callback.onAllDone();
            });
            return;
        }

        // ── Step 2：本地解析工单（不发网络请求）────────────────────────
        List<WorkOrder> orders    = new ArrayList<>(count);
        String[]        taskPacks = new String[count + 1]; // 1-based

        final JSONArray finalBillList = billList;
        for (int i = 0; i < count; i++) {
            try {
                JSONObject item = finalBillList.getJSONObject(i);
                WorkOrder wo = parseWorkOrder(item, i);
                orders.add(wo);
                taskPacks[i + 1] = packTask(wo, i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // ── Step 3：推送工单列表到 UI ────────────────────────────────────
        final List<WorkOrder> finalOrders = new ArrayList<>(orders);
        mainHandler.post(() -> callback.onOrdersReady(finalOrders));

        // ── Step 4：写入 Session，重置进度计数 ──────────────────────────
        s.taskArray = taskPacks;
        s.resetProgress(count);

        // ── Step 5：并发派发工作线程 ─────────────────────────────────────
        ExecutorService localPool = Executors.newFixedThreadPool(
                Math.min(MAX_THREADS, count));

        WorkerTask.UiCallback uiCb = (rowIndex, billsn, content) ->
                mainHandler.post(() -> callback.onStatusUpdate(rowIndex, billsn, content));

        for (int i = 1; i <= count; i++) {
            final int idx = i;
            // [BUG-10 修复] 不在这里再调 releaseSlot()，WorkerTask.run() 的 finally 里已经调用
            localPool.execute(() -> new WorkerTask(idx, uiCb).run());
        }

        // ── Step 6：等待全部完成（最长7分钟兜底）────────────────────────
        localPool.shutdown();
        try {
            boolean finished = localPool.awaitTermination(7, TimeUnit.MINUTES);
            if (!finished) localPool.shutdownNow();
        } catch (InterruptedException e) {
            localPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // [BUG-6 修复] 删除 applyTimeSchedule()，不再强制覆盖用户开关设置
        mainHandler.post(callback::onAllDone);
    }

    // ── 解析单条工单 JSON ──────────────────────────────────────────────────

    private WorkOrder parseWorkOrder(JSONObject item, int index) {
        WorkOrder wo = new WorkOrder();
        wo.index       = index + 1;
        wo.billsn      = item.optString("billsn",      "");
        wo.replyTime   = item.optString("reply_time",  "");
        wo.createTime  = item.optString("createtime",  "");
        wo.stationname = item.optString("stationname", "");
        wo.billtitle   = item.optString("billtitle",   "");
        wo.billid      = item.optString("billid",      "");

        // taskId：直接从列表字段取（兼容 taskid / taskId 大小写）
        wo.taskId = normalizeStr(item.optString("taskid", ""));
        if (wo.taskId.isEmpty()) {
            wo.taskId = normalizeStr(item.optString("taskId", ""));
        }

        wo.acceptOperator  = "";
        wo.dealInfo        = "";
        wo.lastOperateTime = "";

        // 解析 actionlist（接单人、最新反馈内容）
        JSONArray actionList = item.optJSONArray("actionlist");
        if (actionList == null) actionList = item.optJSONArray("actionList");
        if (actionList != null) {
            for (int j = 0; j < actionList.length(); j++) {
                try {
                    JSONObject act = actionList.getJSONObject(j);
                    String sv = act.optString("task_status_dictvalue", "");

                    // 接单人：ACCEPT 状态
                    if ("ACCEPT".equals(sv)) {
                        String op = firstNonEmpty(
                                act.optString("operator",         ""),
                                act.optString("operatorName",     ""),
                                act.optString("operName",         ""),
                                act.optString("handle_user_name", ""),
                                act.optString("handleUserName",   ""),
                                act.optString("dispatchUserName", ""),
                                act.optString("accept_user_name", "")
                        );
                        if (!op.isEmpty()) wo.acceptOperator = op.trim();
                    }

                    // 最新反馈（追加描述 / 故障反馈）
                    String rawDeal = act.optString("deal_info", "");
                    if (rawDeal.contains("追加描述：") || rawDeal.contains("故障反馈：")) {
                        String marker = rawDeal.contains("追加描述：") ? "追加描述：" : "故障反馈：";
                        int start = rawDeal.indexOf(marker) + marker.length();
                        int end   = rawDeal.indexOf("。", start);
                        wo.dealInfo = end > start
                                ? rawDeal.substring(start, end)
                                : rawDeal.substring(start);
                        wo.lastOperateTime = act.optString("operate_end_time", "");
                    }
                } catch (Exception ignored) {}
            }
        }

        // [BUG-7 修复] 告警状态不在这里串行请求，默认填"未知"，
        //   WorkerTask 回单场景里按需并发获取，大幅减少解析阶段耗时
        wo.alertStatus = "未知";
        wo.alertTime   = "";

        // timeDiff 计算
        wo.timeDiff2 = Math.max(0, WorkOrderApi.minutesDiff(wo.createTime));
        wo.timeDiff1 = wo.lastOperateTime.isEmpty()
                ? 0
                : Math.max(0, WorkOrderApi.minutesDiff(wo.lastOperateTime));

        wo.statusCol = "--排队等待处理中...";
        return wo;
    }

    // ── 打包任务字符串（WorkerTask 从这里取参数）──────────────────────────

    private String packTask(WorkOrder wo, int zeroBasedIndex) {
        return wo.billsn         + "\u0001"
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
             + zeroBasedIndex;
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────

    private static String normalizeStr(String s) {
        if (s == null || s.isEmpty() || "null".equalsIgnoreCase(s.trim())) return "";
        return s.trim();
    }

    private static String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) return c.trim();
        }
        return "";
    }
}
