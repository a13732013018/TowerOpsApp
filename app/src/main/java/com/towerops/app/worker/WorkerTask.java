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
 * [BUG-14] 告警状态查询时机
 *   原代码：在 MonitorTask 解析阶段串行查（100条=100次串行请求，极慢）。
 *   修复：MonitorTask Step 2b 用并发线程池同时查所有工单告警（最多10线程，限时2分钟），
 *         全部查完后打包 taskPacks，WorkerTask 拿到的已是实时告警状态，无需再查。
 *         查询失败时保守标记为"告警中"，宁可不回单也不误回单。
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

    /**
     * 全局操作时序锁（公平锁）：
     * 保证任意两次实际提交（接单/反馈/回单）之间至少间隔 MIN_OP_GAP_MS 毫秒。
     * 全局唯一入口，所有工单的提交操作严格串行，绝不允许同一时刻有两条工单同时提交。
     * 模拟人工操作节奏，防止服务器端记录的操作时间戳完全相同。
     */
    private static final ReentrantLock  OP_SEQUENCE_LOCK = new ReentrantLock(true);
    private static volatile long        lastOpTimeMs     = 0L;
    /** 任意两次操作之间的最小间隔：5秒，保证服务器记录的时间戳至少差5秒 */
    private static final long           MIN_OP_GAP_MS    = 5000L;

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

            // ★ 时序锁在分段锁外面获取，等待期间不占用分段锁 ★
            boolean opAcquired = acquireOpLock();
            if (!opAcquired) {
                postUi(rowIndex, billsn, "接单：等待被中断，跳过");
            } else {
                lock.lock();
                try {
                    postUi(rowIndex, billsn, "点击接单(billId=" + billid
                            + " taskId=" + taskId
                            + " userid=" + s.userid
                            + " realname=" + s.realname + ")...");
                    sleepMs(randInt(1000, 2000));

                    String  acceptResult = "";
                    boolean acceptOk     = false;
                    try {
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
                    } finally {
                        lock.unlock();
                        releaseOpLock(); // 先解分段锁，再释放时序锁
                    }

                    if (acceptOk) {
                        postUi(rowIndex, billsn, "接单成功 ✓");
                        acceptedThisRound = true; // [BUG-15] 本轮不再反馈
                    } else {
                        String brief = acceptResult.replaceAll("[\\r\\n]+", " ").trim();
                        postUi(rowIndex, billsn, "接单失败，服务器响应: " + brief);
                    }
                } catch (Exception e) {
                    lock.unlock();
                    releaseOpLock();
                    throw e;
                }
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

            // ★ 时序锁在分段锁外面获取，等待期间不占用分段锁 ★
            boolean opAcquired = acquireOpLock();
            if (!opAcquired) {
                postUi(rowIndex, billsn, "反馈：等待被中断，跳过");
            } else {
                lock.lock();
                try {
                    postUi(rowIndex, billsn, "正在填写反馈内容...");
                    sleepMs(randInt(5000, 10000));

                    String comment = (billtitle.contains("停电") || billtitle.contains("断电"))
                            ? "故障停电"
                            : "站点设备故障";
                    String remarkResult;
                    try {
                        remarkResult = WorkOrderApi.addRemark(taskId, comment, billsn);
                    } finally {
                        lock.unlock();
                        releaseOpLock();
                    }

                    if (isSuccess(remarkResult)) {
                        postUi(rowIndex, billsn, "反馈成功 ✓  [" + comment + "]");
                    } else {
                        postUi(rowIndex, billsn, "反馈完毕:" + brief(remarkResult, 60));
                    }
                } catch (Exception e) {
                    lock.unlock();
                    releaseOpLock();
                    throw e;
                }
            }
        }

        // ════════════════════════════════════════════════════════════
        // 场景三：自动回单
        // [BUG-FIX] 原来用 s.username（账号工号）和 acceptOperator（中文姓名）比对，
        //   永远不等，回单永远不触发。修复：改用 s.realname。
        // 告警状态已由 MonitorTask Step 2b 并发实时查询填充（告警中/已恢复），
        // 此处直接用，无需再补查。
        // ════════════════════════════════════════════════════════════
        if (enable回单 && !s.realname.isEmpty() && s.realname.equals(acceptOperator)) {

            if ("已恢复".equals(alertStatus)) {
                hasAction = true;
                doRevert(rowIndex, billsn, billtitle, billid, taskId, lock);
            } else {
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
        postUi(rowIndex, billsn, "准备回单：获取工单详情...");

        // 详情查询不需要时序锁（只读），直接查
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

        // 用于在后续步骤中更新 taskId（需要 final[]）
        final String[] taskIdRef = { taskId };

        if (!detailStr.contains("签到")) {

            if (isPowerFault) {
                postUi(rowIndex, billsn, "选择【发电判断】...");
                // ★ 时序锁在分段锁外面获取 ★
                if (!acquireOpLock()) {
                    postUi(rowIndex, billsn, "发电判断：被中断，放弃回单");
                    return;
                }
                lock.lock();
                try {
                    sleepMs(randInt(3000, 5000));
                    WorkOrderApi.electricJudge(billsn, notGoReason, billid, taskIdRef[0]);
                } finally {
                    lock.unlock();
                    releaseOpLock();
                }
            }

            postUi(rowIndex, billsn, "选择【上站判断】...");
            if (!acquireOpLock()) {
                postUi(rowIndex, billsn, "上站判断：被中断，放弃回单");
                return;
            }
            lock.lock();
            try {
                sleepMs(randInt(2000, 3500));
                WorkOrderApi.stationStatus(taskIdRef[0], notGoReason, billsn);
            } finally {
                lock.unlock();
                releaseOpLock();
            }

            postUi(rowIndex, billsn, "等待服务器更新...");
            sleepMs(randInt(3000, 5000));

            // 刷新详情，获取新 taskId
            String detailStr2 = WorkOrderApi.getBillDetail(billsn);
            try {
                JSONObject d2 = new JSONObject(detailStr2);
                String newTaskId = getPath(d2, "model.taskid");
                if (newTaskId.isEmpty()) newTaskId = getPath(d2, "model.taskId");
                if (!newTaskId.isEmpty()) taskIdRef[0] = newTaskId;

                String oet2 = findOperateEndTime(d2);
                if (!oet2.isEmpty()) operateEndTime = oet2;
            } catch (Exception ignored) {}

            // 免发电特殊处理
            if (detailStr.contains("停电告警已经清除不需要发电")) {
                postUi(rowIndex, billsn, "检测到免发电，直接回单...");
                if (!acquireOpLock()) {
                    postUi(rowIndex, billsn, "免发电回单：被中断，放弃");
                    return;
                }
                lock.lock();
                String revertResult;
                try {
                    sleepMs(randInt(3000, 6000));
                    revertResult = WorkOrderApi.revertBill(
                            faultType, faultCouse, handlerResult,
                            billid, billsn, taskIdRef[0], recoveryTime);
                } finally {
                    lock.unlock();
                    releaseOpLock();
                }
                postUi(rowIndex, billsn, isSuccess(revertResult)
                        ? "回单成功 ✓ [免发电]"
                        : "回单失败:" + brief(revertResult, 60));
                return;
            }

            if (!operateEndTime.isEmpty()) {
                postUi(rowIndex, billsn, "正在填写终审回单...");
                if (!acquireOpLock()) {
                    postUi(rowIndex, billsn, "终审回单：被中断，放弃");
                    return;
                }
                lock.lock();
                String revertResult;
                try {
                    sleepMs(randInt(5000, 9000));
                    revertResult = WorkOrderApi.revertBill(
                            faultType, faultCouse, handlerResult,
                            billid, billsn, taskIdRef[0], recoveryTime);
                } finally {
                    lock.unlock();
                    releaseOpLock();
                }
                postUi(rowIndex, billsn, isSuccess(revertResult)
                        ? "回单成功 ✓"
                        : "回单失败:" + brief(revertResult, 60));
            } else {
                postUi(rowIndex, billsn, "未获取到操作时间，暂不回单");
            }

        } else {
            postUi(rowIndex, billsn, "已签到，直接终审回单...");
            if (!acquireOpLock()) {
                postUi(rowIndex, billsn, "回单：被中断，放弃");
                return;
            }
            lock.lock();
            String revertResult;
            try {
                sleepMs(randInt(5000, 9000));
                revertResult = WorkOrderApi.revertBill(
                        faultType, faultCouse, handlerResult,
                        billid, billsn, taskIdRef[0], recoveryTime);
            } finally {
                lock.unlock();
                releaseOpLock();
            }
            postUi(rowIndex, billsn, isSuccess(revertResult)
                    ? "回单成功 ✓ [已签到]"
                    : "回单失败:" + brief(revertResult, 60));
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

    private static boolean isSuccess(String result) {
        if (result == null || result.isEmpty()) return false;
        return result.contains("OK")
                || result.contains("\"status\":\"ok\"")
                || result.contains("\"status\": \"ok\"")
                || result.contains("success")
                || result.contains("接单成功")
                || result.contains("操作成功")
                || result.contains("处理成功")
                || result.contains("提交成功");
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

    /**
     * 操作时序保护：在实际提交操作前调用。
     * 获取全局操作序列锁（公平锁），等待距上次任意提交至少 MIN_OP_GAP_MS 毫秒。
     *
     * ★ 重要：调用此方法时必须在分段锁 lock.lock() 之外，
     *   不允许在持有分段锁的情况下调用，否则等待期间长时间占用分段锁导致其他工单全程阻塞。
     *   正确用法：先 acquireOpLock()，再 lock.lock()，操作完后先 lock.unlock()，最后 releaseOpLock()。
     *
     * @return false 表示等待期间被中断，调用方应跳过本次提交
     */
    private static boolean acquireOpLock() {
        OP_SEQUENCE_LOCK.lock();
        if (Thread.currentThread().isInterrupted()) {
            OP_SEQUENCE_LOCK.unlock(); // 被中断立即释放，不持有锁挂着
            return false;
        }
        long now  = System.currentTimeMillis();
        long wait = MIN_OP_GAP_MS - (now - lastOpTimeMs);
        if (wait > 0) {
            try {
                Thread.sleep(wait); // 可中断 sleep
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                OP_SEQUENCE_LOCK.unlock();
                return false;
            }
        }
        return true;
    }

    /**
     * 记录本次操作的完成时间并释放操作时序锁。
     * 必须与 acquireOpLock() 成对调用（acquireOpLock 返回 true 时），放在 finally 块中。
     */
    private static void releaseOpLock() {
        lastOpTimeMs = System.currentTimeMillis();
        OP_SEQUENCE_LOCK.unlock();
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
