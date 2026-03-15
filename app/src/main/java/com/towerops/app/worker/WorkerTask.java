package com.towerops.app.worker;

import android.os.Handler;
import android.os.Looper;

import com.towerops.app.api.WorkOrderApi;
import com.towerops.app.model.Session;

import org.json.JSONObject;

import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工作线程 —— 对应易语言 子程序_工作线程_APP自动处理
 * 实现三大场景：自动反馈、自动接单、自动回单
 */
public class WorkerTask implements Runnable {

    // 串行网络发包锁（对应 集_网络发包许可证）
    public static final ReentrantLock NET_LOCK = new ReentrantLock(true);

    private final int    taskIndex;
    private final Random random = new Random();

    /** UI 回调接口（主线程更新列表） */
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

        // 解包任务参数（对应 分割文本 + 字符(1) 分隔）
        String pack = s.taskArray[taskIndex];
        if (pack == null || pack.isEmpty()) { s.releaseSlot(); return; }

        String[] parts = pack.split("\u0001", -1);
        if (parts.length < 11) { s.releaseSlot(); return; }

        String billsn          = parts[0];
        String stationname     = parts[1];
        String billtitle       = parts[2];
        String billid          = parts[3];
        String taskId          = parts[4];
        String acceptOperator  = parts[5];
        String dealInfo        = parts[6];
        String alertStatus     = parts[7];
        int    timeDiff1       = parseInt(parts[8]);
        int    timeDiff2       = parseInt(parts[9]);
        int    rowIndex        = parseInt(parts[10]);

        // 解包配置参数
        String[] cfg = s.appConfig.split("\u0001", -1);
        if (cfg.length < 5) { s.releaseSlot(); return; }

        boolean enable反馈  = "true".equalsIgnoreCase(cfg[0]);
        boolean enable接单  = "true".equalsIgnoreCase(cfg[1]);
        boolean enable回单  = "true".equalsIgnoreCase(cfg[2]);

        String[] r1 = cfg[3].split("\\|");
        String[] r2 = cfg[4].split("\\|");

        int min反馈 = parseInt(r1.length > 0 ? r1[0] : "30");
        int max反馈 = parseInt(r1.length > 1 ? r1[1] : "60");
        int min接单 = parseInt(r2.length > 0 ? r2[0] : "5");
        int max接单 = parseInt(r2.length > 1 ? r2[1] : "15");

        int 阈值反馈 = randInt(min反馈, max反馈);
        int 阈值接单 = randInt(min接单, max接单);

        boolean hasAction = false;

        // ==================== 场景一：自动反馈 ====================
        // 条件：taskId非空（工单存在）+ 距上次操作 >= 阈值分钟 + 未处理过发电
        // 说明：不限制接单人是谁，只要工单存在且超时未反馈，均可追加描述
        //       原易语言逻辑：不判断接单人，只要工单在列表里就反馈
        if (enable反馈
                && !taskId.isEmpty()           // 工单taskId有效即可，不限制接单人
                && timeDiff1 >= 阈值反馈
                && !dealInfo.contains("无需发电")
                && !dealInfo.contains("发电中")) {

            hasAction = true;
            postUi(rowIndex, billsn, "准备反馈[" + timeDiff1 + "≥" + 阈值反馈 + "min]...");
            NET_LOCK.lock();
            try {
                postUi(rowIndex, billsn, "正在填写反馈内容...");
                sleep(randInt(5000, 10000));

                String comment = (billtitle.contains("停电") || billtitle.contains("断电"))
                        ? "故障停电，联系电力部门处理中"
                        : "站点设备故障，正在处理中";
                String remarkResult = WorkOrderApi.addRemark(taskId, comment, billsn);

                if (remarkResult.contains("OK") || remarkResult.contains("success")) {
                    postUi(rowIndex, billsn, "反馈成功 ✓");
                } else {
                    postUi(rowIndex, billsn, "反馈完毕:" + remarkResult.substring(0, Math.min(40, remarkResult.length())));
                }
            } finally {
                NET_LOCK.unlock();
            }
        }

        // ==================== 场景二：自动接单 ====================
        // 条件：未接单（acceptOperator为空）+ 距创建时间 >= 阈值分钟
        if (enable接单
                && acceptOperator.isEmpty()
                && timeDiff2 >= 阈值接单) {

            hasAction = true;
            postUi(rowIndex, billsn, "准备接单[" + timeDiff2 + "≥" + 阈值接单 + "min]...");
            NET_LOCK.lock();
            try {
                postUi(rowIndex, billsn, "阅读工单详情中...");
                sleep(randInt(2000, 4000));
                postUi(rowIndex, billsn, "点击接单...");
                sleep(randInt(1000, 2000));
                String acceptResult = WorkOrderApi.acceptBill(billid, billsn, taskId);
                if (acceptResult.contains("OK") || acceptResult.contains("success")) {
                    postUi(rowIndex, billsn, "接单成功 ✓");
                } else {
                    postUi(rowIndex, billsn, "接单完毕:" + acceptResult.substring(0, Math.min(40, acceptResult.length())));
                }
            } finally {
                NET_LOCK.unlock();
            }
        }

        // ==================== 场景三：自动回单 ====================
        if (enable回单
                && acceptOperator.equals(s.username)
                && "已恢复".equals(alertStatus)) {

            hasAction = true;
            boolean isFinalRevert = false;
            postUi(rowIndex, billsn, "准备回单：等待操作空闲...");

            NET_LOCK.lock();
            try {
                postUi(rowIndex, billsn, "点开工单，解析详情...");
                sleep(randInt(4000, 8000));
                String detailStr = WorkOrderApi.getBillDetail(billsn);

                JSONObject detailJson;
                try {
                    detailJson = new JSONObject(detailStr);
                } catch (Exception e) {
                    postUi(rowIndex, billsn, "详情解析失败，放弃处理");
                    return;
                }

                String recoveryTime = left(getPath(detailJson, "model.recovery_time"), 16);
                String operateEndTime = "";

                // 找 ISSTAND 或 ELECTRIC_JUDGE 的 operate_end_time
                try {
                    org.json.JSONArray actionList = detailJson.getJSONArray("actionList");
                    for (int p = 0; p < actionList.length(); p++) {
                        JSONObject act = actionList.getJSONObject(p);
                        String sv = act.optString("task_status_dictvalue", "");
                        if ("ISSTAND".equals(sv) || "ELECTRIC_JUDGE".equals(sv)) {
                            String oet = act.optString("operate_end_time", "");
                            if (!oet.isEmpty()) { operateEndTime = oet; break; }
                        }
                    }
                } catch (Exception ignored) {}

                String notGoReason, faultType, faultCouse, handlerResult;

                if (!detailStr.contains("签到")) {
                    if (billtitle.contains("停电") || billtitle.contains("断电") || billtitle.contains("电压过低")) {
                        notGoReason  = "来电恢复";
                        faultType    = "站址-电源设备系统";
                        faultCouse   = "电力停电（直供电）-市电停电";
                        handlerResult = "来电恢复";

                        postUi(rowIndex, billsn, "选择【发电判断】...");
                        sleep(randInt(3000, 5000));
                        WorkOrderApi.electricJudge(billsn, notGoReason, billid, taskId);
                    } else {
                        notGoReason  = "自动恢复";
                        faultType    = "站址-其他原因";
                        faultCouse   = "其他原因";
                        handlerResult = "自然恢复";
                    }

                    postUi(rowIndex, billsn, "选择【上站判断】...");
                    sleep(randInt(2000, 3500));
                    WorkOrderApi.stationStatus(taskId, notGoReason, billsn);

                    postUi(rowIndex, billsn, "等待页面刷新...");
                    sleep(randInt(3000, 5000));

                    // 刷新详情，获取新 taskId
                    String detailStr2 = WorkOrderApi.getBillDetail(billsn);
                    try {
                        JSONObject d2 = new JSONObject(detailStr2);
                        String newTaskId = getPath(d2, "model.taskid");
                        if (newTaskId.isEmpty()) newTaskId = getPath(d2, "model.taskId");
                        if (!newTaskId.isEmpty()) taskId = newTaskId;

                        org.json.JSONArray al2 = d2.getJSONArray("actionList");
                        for (int p = 0; p < al2.length(); p++) {
                            JSONObject act = al2.getJSONObject(p);
                            String sv = act.optString("task_status_dictvalue", "");
                            if ("ISSTAND".equals(sv) || "ELECTRIC_JUDGE".equals(sv)) {
                                String oet = act.optString("operate_end_time", "");
                                if (!oet.isEmpty()) { operateEndTime = oet; break; }
                            }
                        }
                    } catch (Exception ignored) {}

                    // 判断时间间隔 > 0 才回单
                    if (!operateEndTime.isEmpty()) {
                        String normalizedOet = operateEndTime.substring(0, Math.min(19, operateEndTime.length()))
                                .replace("-", "/");
                        int diffMin = WorkOrderApi.minutesDiff(normalizedOet);
                        if (diffMin > 0) {
                            postUi(rowIndex, billsn, "正在填写终审回单...");
                            sleep(randInt(5000, 9000));
                            WorkOrderApi.revertBill(faultType, faultCouse, handlerResult,
                                    billid, billsn, taskId, recoveryTime);
                            isFinalRevert = true;
                        }
                    }

                    // 免发电特殊处理
                    if (detailStr.contains("停电告警已经清除不需要发电")) {
                        postUi(rowIndex, billsn, "检测到免发电，提交回单...");
                        sleep(randInt(3000, 6000));
                        WorkOrderApi.revertBill(faultType, faultCouse, handlerResult,
                                billid, billsn, taskId, recoveryTime);
                        isFinalRevert = true;
                    }
                }

                if (isFinalRevert) {
                    postUi(rowIndex, billsn, "最终回单执行成功");
                } else if (operateEndTime.isEmpty()) {
                    postUi(rowIndex, billsn, "异常：未能获取到操作时间");
                } else {
                    postUi(rowIndex, billsn, "拦截：操作时间间隔不足");
                }

            } finally {
                NET_LOCK.unlock();
            }
        }

        // 兜底显示
        if (!hasAction) {
            postUi(rowIndex, billsn, "-- 暂无需要操作 --");
        }

        s.releaseSlot();
    }

    // ---- 辅助方法 ----

    private void postUi(int row, String billsn, String msg) {
        mainHandler.post(() -> uiCallback.updateStatus(row, billsn, msg));
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private int randInt(int min, int max) {
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String getPath(JSONObject root, String path) {
        return WorkOrderApi.getJsonPath(root, path);
    }

    private static String left(String s, int len) {
        if (s == null || s.length() <= len) return s == null ? "" : s;
        return s.substring(0, len);
    }
}
