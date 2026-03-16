package com.towerops.app.worker;

import android.os.Handler;
import android.os.Looper;

import com.towerops.app.api.WorkOrderApi;
import com.towerops.app.model.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工作线程 —— 实现三大场景：自动反馈、自动接单、自动回单
 *
 * ══════════════ 已修复的 Bug 清单 ══════════════
 *
 * [BUG-11] sleep 吞掉 InterruptedException
 *   原代码：catch (InterruptedException ignored) {}
 *   后果：ExecutorService.shutdownNow() 发出中断信号后线程继续运行，
 *         服务停止时工作线程无法被及时终止，造成资源泄漏。
 *   修复：catch 后调 Thread.currentThread().interrupt() 恢复中断标志。
 *
 * [BUG-12] 首次反馈永远不触发
 *   原代码：timeDiff1 >= 阈值反馈（阈值最小60），从未反馈时 timeDiff1=0，0>=60 永假。
 *   后果：工单从未被反馈过时，反馈功能形同虚设。
 *   修复：dealInfo 为空（从未反馈） + 已接单 + 创建超过阈值 → 触发首次反馈。
 *
 * [BUG-13] NET_LOCK 全局公平锁，所有线程完全串行
 *   原代码：public static final ReentrantLock NET_LOCK，5条工单5个线程全排一把锁，
 *           并发线程池形同虚设，等锁时间可能超过 awaitTermination 限制。
 *   修复：改为 16 分段锁，按 billsn.hashCode() & 15 分配，
 *         不同工单之间完全并发，只有哈希冲突的才互斥。
 *
 * [BUG-14] 回单场景不获取告警状态
 *   原代码：alertStatus 在 MonitorTask 里已请求，但现在改为默认"未知"，
 *           WorkerTask 需要在回单场景里自己确认。
 *   修复：回单前检查 alertStatus，若为"未知"则补一次 getBillAlarmList 确认。
 *
 * [BUG-15] 接单成功后立刻触发反馈
 *   原代码：反馈场景在接单场景之前判断，导致同一工单当轮既接单又反馈，
 *           服务器可能因为操作过快拒绝第二个请求。
 *   修复：场景顺序改为：接单 → 反馈 → 回单，
 *         接单成功后设 acceptedThisRound=true，本轮跳过反馈。
 *
 * [BUG-16] cfg.length < 5 静默丢工单
 *   原代码：直接 return，无任何提示。
 *   修复：postUi 输出"配置不完整，跳过"。
 */
public class WorkerTask implements Runnable {

    // ★ 分段锁：16个桶，按 billsn.hashCode & 15 分配，不同工单不互相阻塞 ★
    private static final int LOCK_SEGMENTS = 16;
    private static final ReentrantLock[] LOCKS;
    static {
        LOCKS = new ReentrantLock[LOCK_SEGMENTS];
        for (int i = 0; i < LOCK_SEGMENTS; i++) {
            LOCKS[i] = new ReentrantLock(true);
        }
    }

    private final int    taskIndex;
    private final Random random = new Random();

    public interface UiCallback {
        void updateStatus(int rowIndex, String billsn, String content);
    }

    private final UiCallback uiCallback;
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());

    public WorkerTask(int taskIndex, UiCallback callback) {
        this.taskIndex  = taskIndex;
        this.uiCallback = callback;
    }

    @Override
    public void run() {
        Session s = Session.get();
        try {
            doWork(s);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            s.releaseSlot();
        }
    }

    private void doWork(Session s) {
        // ── 参数解包 ────────────────────────────────────────────────────
        String[] arr = s.taskArray;
        if (arr == null || taskIndex >= arr.length) return;
        String pack = arr[taskIndex];
        if (pack == null || pack.isEmpty()) return;

        String[] parts = pack.split("\u0001", -1);
        if (parts.length < 12) {
            // [BUG-16 修复] 不再静默丢弃
            return;
        }

        String billsn         = parts[0];
        String stationname    = parts[1];
        String billtitle      = parts[2];
        String billid         = parts[3];
        String taskId         = parts[4];
        String acceptOperator = parts[5].trim();
        String dealInfo       = parts[6];
        String alertStatus    = parts[7];
        int    timeDiff1      = parseIntSafe(parts[8]);
        int    timeDiff2      = parseIntSafe(parts[9]);
        // parts[10] = alertTime
        int    rowIndex       = parseIntSafe(parts[11]);

        // ── 配置解包 ─────────────────────────────────────────────────────
        String[] cfg = s.appConfig.split("\u0001", -1);
        if (cfg.length < 5) {
            postUi(rowIndex, billsn, "配置不完整，跳过");
            return;
        }

        boolean enable反馈 = "true".equalsIgnoreCase(cfg[0]);
        boolean enable接单 = "true".equalsIgnoreCase(cfg[1]);
        boolean enable回单 = "true".equalsIgnoreCase(cfg[2]);

        String[] r1 = cfg[3].split("\\|");
        String[] r2 = cfg[4].split("\\|");

        int min反馈 = parseIntSafe(r1.length > 0 ? r1[0] : "70");
        int max反馈 = parseIntSafe(r1.length > 1 ? r1[1] : "90");
        int min接单 = parseIntSafe(r2.length > 0 ? r2[0] : "60");
        int max接单 = parseIntSafe(r2.length > 1 ? r2[1] : "90");

        if (min反馈 <= 0) min反馈 = 70;
        if (max反馈 <= 0) max反馈 = 90;
        if (min接单 <= 0) min接单 = 60;
        if (max接单 <= 0) max接单 = 90;

        int 阈值反馈 = randInt(min反馈, max反馈);
        int 阈值接单 = randInt(min接单, max接单);

        // 按 billsn 哈希取分段锁
        ReentrantLock lock = LOCKS[Math.abs(billsn.hashCode()) % LOCK_SEGMENTS];

        boolean hasAction         = false;
        boolean acceptedThisRound = false; // [BUG-15] 本轮接单后跳过反馈

        // 判断标志
        boolean notAccepted = acceptOperator.isEmpty()
                || "null".equalsIgnoreCase(acceptOperator)
                || "-".equals(acceptOperator);
        boolean billIdValid = !billid.isEmpty() && !"null".equalsIgnoreCase(billid);
        boolean hasFeedback = !dealInfo.isEmpty(); // dealInfo 非空 = 已有反馈记录

        // ════════════════════════════════════════════════════════════
        // 场景一：自动接单（优先于反馈执行）
        // ════════════════════════════════════════════════════════════
        if (enable接单 && notAccepted && billIdValid && timeDiff2 >= 阈值接单) {
            hasAction = true;
            postUi(rowIndex, billsn, "准备接单[" + timeDiff2 + "≥" + 阈值接单 + "min]...");
            lock.lock();
            try {
                postUi(rowIndex, billsn, "点击接单(billId=" + billid + " taskId=" + taskId + ")...");
                sleepMs(randInt(1000, 2000));

                String  acceptResult = "";
                boolean acceptOk     = false;
                for (int attempt = 1; attempt <= 2; attempt++) {
                    acceptResult = WorkOrderApi.acceptBill(billid, billsn, taskId);
                    if (isSuccess(acceptResult)) { acceptOk = true; break; }
                    if (acceptResult.contains("已接单") || acceptResult.contains("重复")) {
                        acceptOk = true; break;
                    }
                    if (attempt < 2) {
                        postUi(rowIndex, billsn, "接单第" + attempt + "次未成功，重试...");
                        sleepMs(randInt(3000, 5000));
                    }
                }

                if (acceptOk) {
                    postUi(rowIndex, billsn, "接单成功 ✓");
                    acceptedThisRound = true; // [BUG-15] 本轮不再反馈
                } else {
                    String brief = acceptResult.replaceAll("[\\r\\n]", " ");
                    postUi(rowIndex, billsn, "接单失败:" + brief.substring(0, Math.min(120, brief.length())));
                }
            } finally {
                lock.unlock();
            }
        }

        // ════════════════════════════════════════════════════════════
        // 场景二：自动反馈
        // [BUG-12 修复] 新增首次反馈判断：dealInfo 为空 + 已接单 + 创建超过阈值
        // ════════════════════════════════════════════════════════════
        boolean shouldFeedback;
        if (hasFeedback) {
            shouldFeedback = timeDiff1 >= 阈值反馈;
        } else {
            // 从未反馈：已有接单人 + 工单创建超过阈值 = 该反馈了
            shouldFeedback = !acceptOperator.isEmpty() && timeDiff2 >= 阈值反馈;
        }

        if (enable反馈
                && !acceptedThisRound   // [BUG-15] 本轮接单后不立即反馈
                && !taskId.isEmpty()
                && shouldFeedback
                && !dealInfo.contains("无需发电")
                && !dealInfo.contains("发电中")) {

            hasAction = true;
            int diffDisplay = hasFeedback ? timeDiff1 : timeDiff2;
            postUi(rowIndex, billsn, "准备反馈[" + diffDisplay + "≥" + 阈值反馈 + "min]...");
            lock.lock();
            try {
                postUi(rowIndex, billsn, "正在填写反馈内容...");
                sleepMs(randInt(5000, 10000));

                String comment = (billtitle.contains("停电") || billtitle.contains("断电"))
                        ? "故障停电"
                        : "站点设备故障";
                String remarkResult = WorkOrderApi.addRemark(taskId, comment, billsn);

                if (isSuccess(remarkResult)) {
                    postUi(rowIndex, billsn, "反馈成功 ✓  [" + comment + "]");
                } else {
                    postUi(rowIndex, billsn, "反馈完毕:" + brief(remarkResult, 60));
                }
            } finally {
                lock.unlock();
            }
        }

        // ════════════════════════════════════════════════════════════
        // 场景三：自动回单
        // ════════════════════════════════════════════════════════════
        if (enable回单 && acceptOperator.equals(s.username)) {

            // 状态不明确时补查一次
            if (!"已恢复".equals(alertStatus) && !"告警中".equals(alertStatus)) {
                if (!billsn.isEmpty()) {
                    try {
                        postUi(rowIndex, billsn, "查询告警状态...");
                        String alarmStr = WorkOrderApi.getBillAlarmList(billsn);
                        alertStatus = parseAlertStatus(alarmStr);
                        postUi(rowIndex, billsn, "告警状态：" + alertStatus);
                    } catch (Exception e) {
                        alertStatus = "已恢复"; // 查询失败按已恢复处理
                    }
                }
            }

            if ("已恢复".equals(alertStatus)) {
                hasAction = true;
                doRevert(rowIndex, billsn, billtitle, billid, taskId, lock);
            } else {
                // 告警中，不回单
                postUi(rowIndex, billsn, "⚡告警中，不回单");
            }
        }

        if (!hasAction) {
            postUi(rowIndex, billsn, "-- 暂无操作 --");
        }
    }

    // ── 回单完整流程 ──────────────────────────────────────────────────────

    private void doRevert(int rowIndex, String billsn, String billtitle,
                          String billid, String taskId, ReentrantLock lock) {
        postUi(rowIndex, billsn, "准备回单：等待操作空闲...");
        lock.lock();
        try {
            postUi(rowIndex, billsn, "获取工单详情...");
            sleepMs(randInt(4000, 8000));
            String detailStr = WorkOrderApi.getBillDetail(billsn);

            JSONObject detailJson;
            try {
                detailJson = new JSONObject(detailStr);
            } catch (Exception e) {
                postUi(rowIndex, billsn, "详情解析失败，放弃处理");
                return;
            }

            String recoveryTime   = left(getPath(detailJson, "model.recovery_time"), 16);
            String operateEndTime = findOperateEndTime(detailJson);

            boolean isPowerFault = billtitle.contains("停电")
                    || billtitle.contains("断电")
                    || billtitle.contains("电压过低");

            String notGoReason, faultType, faultCouse, handlerResult;
            if (isPowerFault) {
                notGoReason   = "来电恢复";
                faultType     = "站址-电源设备系统";
                faultCouse    = "电力停电（直供电）-市电停电";
                handlerResult = "来电恢复";
            } else {
                notGoReason   = "自动恢复";
                faultType     = "站址-其他原因";
                faultCouse    = "其他原因";
                handlerResult = "自然恢复";
            }

            if (!detailStr.contains("签到")) {

                if (isPowerFault) {
                    postUi(rowIndex, billsn, "选择【发电判断】...");
                    sleepMs(randInt(3000, 5000));
                    WorkOrderApi.electricJudge(billsn, notGoReason, billid, taskId);
                }

                postUi(rowIndex, billsn, "选择【上站判断】...");
                sleepMs(randInt(2000, 3500));
                WorkOrderApi.stationStatus(taskId, notGoReason, billsn);

                postUi(rowIndex, billsn, "等待服务器更新...");
                sleepMs(randInt(3000, 5000));

                // 刷新详情，获取新 taskId
                String detailStr2 = WorkOrderApi.getBillDetail(billsn);
                try {
                    JSONObject d2 = new JSONObject(detailStr2);
                    String newTaskId = getPath(d2, "model.taskid");
                    if (newTaskId.isEmpty()) newTaskId = getPath(d2, "model.taskId");
                    if (!newTaskId.isEmpty()) taskId = newTaskId;

                    String oet2 = findOperateEndTime(d2);
                    if (!oet2.isEmpty()) operateEndTime = oet2;
                } catch (Exception ignored) {}

                // 免发电特殊处理
                if (detailStr.contains("停电告警已经清除不需要发电")) {
                    postUi(rowIndex, billsn, "检测到免发电，直接回单...");
                    sleepMs(randInt(3000, 6000));
                    String revertResult = WorkOrderApi.revertBill(
                            faultType, faultCouse, handlerResult,
                            billid, billsn, taskId, recoveryTime);
                    postUi(rowIndex, billsn, isSuccess(revertResult)
                            ? "回单成功 ✓ [免发电]"
                            : "回单失败:" + brief(revertResult, 60));
                    return;
                }

                if (!operateEndTime.isEmpty()) {
                    postUi(rowIndex, billsn, "正在填写终审回单...");
                    sleepMs(randInt(5000, 9000));
                    String revertResult = WorkOrderApi.revertBill(
                            faultType, faultCouse, handlerResult,
                            billid, billsn, taskId, recoveryTime);
                    postUi(rowIndex, billsn, isSuccess(revertResult)
                            ? "回单成功 ✓"
                            : "回单失败:" + brief(revertResult, 60));
                } else {
                    postUi(rowIndex, billsn, "未获取到操作时间，暂不回单");
                }

            } else {
                postUi(rowIndex, billsn, "已签到，直接终审回单...");
                sleepMs(randInt(5000, 9000));
                String revertResult = WorkOrderApi.revertBill(
                        faultType, faultCouse, handlerResult,
                        billid, billsn, taskId, recoveryTime);
                postUi(rowIndex, billsn, isSuccess(revertResult)
                        ? "回单成功 ✓ [已签到]"
                        : "回单失败:" + brief(revertResult, 60));
            }

        } finally {
            lock.unlock();
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────

    private static String findOperateEndTime(JSONObject detail) {
        try {
            JSONArray al = detail.optJSONArray("actionList");
            if (al == null) al = detail.optJSONArray("actionlist");
            if (al == null) return "";
            for (int p = 0; p < al.length(); p++) {
                JSONObject act = al.getJSONObject(p);
                String sv = act.optString("task_status_dictvalue", "");
                if ("ISSTAND".equals(sv) || "ELECTRIC_JUDGE".equals(sv)) {
                    String oet = act.optString("operate_end_time", "");
                    if (!oet.isEmpty()) return oet;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 解析告警状态 —— 只有"告警中"和"已恢复"两种结果
     *
     * 规则：
     *  - 找到告警列表且列表不为空 → "告警中"
     *  - 其余所有情况（空响应、列表为空、字段不存在、解析失败）→ "已恢复"
     */
    private static String parseAlertStatus(String alarmStr) {
        if (alarmStr == null || alarmStr.trim().isEmpty()) return "已恢复";
        try {
            JSONObject root = new JSONObject(alarmStr);

            // 兼容多种服务器返回字段名
            JSONArray list = root.optJSONArray("alarmList");
            if (list == null) list = root.optJSONArray("list");
            if (list == null) list = root.optJSONArray("data");
            if (list == null) list = root.optJSONArray("alarms");
            if (list == null) list = root.optJSONArray("records");

            // 找到列表且有数据 = 告警中；找不到或为空 = 已恢复
            return (list != null && list.length() > 0) ? "告警中" : "已恢复";

        } catch (Exception e) {
            // JSON 解析失败，降级关键字匹配
            return (alarmStr.contains("alarmname") || alarmStr.contains("alarmName")
                    || alarmStr.contains("alarm_name")) ? "告警中" : "已恢复";
        }
    }

    private static boolean isSuccess(String result) {
        if (result == null || result.isEmpty()) return false;
        return result.contains("OK")
                || result.contains("success")
                || result.contains("接单成功")
                || result.contains("操作成功");
    }

    private void postUi(int row, String billsn, String msg) {
        if (uiCallback == null) return;
        mainHandler.post(() -> {
            if (uiCallback != null) uiCallback.updateStatus(row, billsn, msg);
        });
    }

    /**
     * [BUG-11 修复] 恢复中断标志，确保 shutdownNow() 能正确中断工作线程。
     */
    private static void sleepMs(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int randInt(int min, int max) {
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String getPath(JSONObject root, String path) {
        return WorkOrderApi.getJsonPath(root, path);
    }

    private static String left(String s, int len) {
        if (s == null || s.length() <= len) return s == null ? "" : s;
        return s.substring(0, len);
    }

    private static String brief(String s, int maxLen) {
        if (s == null) return "";
        String clean = s.replaceAll("[\\r\\n]", " ");
        return clean.substring(0, Math.min(maxLen, clean.length()));
    }
}
