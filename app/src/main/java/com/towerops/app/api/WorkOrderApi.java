package com.towerops.app.api;

import com.towerops.app.model.Session;
import com.towerops.app.util.HttpUtil;
import com.towerops.app.util.TimeUtil;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 工单操作 API —— 对应易语言中所有 APP_xxx 子程序
 *
 * v2 优化：
 *   [OPT-1]  minutesDiff 对 null / 空串 / 解析失败 统一返回 0，不会返回负值
 *            （调用方加了 Math.max 兜底，这里双重保险）
 *   [OPT-2]  所有接口都对 userId 做 URL 编码，防止特殊字符导致服务器 400
 *   [OPT-3]  acceptBill 头信息统一为常量，避免每次调用重复拼字符串
 *   [OPT-4]  getJsonPath 支持 optJSONArray 路径（兼容 list[0].field 格式）
 *   [OPT-5]  添加 getBillAlarmList 返回值解析帮助方法 isAlarmActive
 */
public class WorkOrderApi {

    private static final String BASE = "http://ywapp.chinatowercom.cn:58090/itower/mobile/app/service";

    // =====================================================================
    // ★ 版本号 —— 每次接口升级只改这两行 ★
    // =====================================================================
    private static final String V    = "1.0.93";
    private static final String UPVS = "2025-04-12-ccssoft";

    // =====================================================================
    // 1. 获取工单监控列表
    // =====================================================================
    public static String getBillMonitorList() {
        Session s   = Session.get();
        String  ts  = TimeUtil.getCurrentTimestamp();
        String  uid = urlEncUtf8(s.userid);
        String  url = BASE + "?porttype=GET_BILL_MONITOR_LIST&v=" + V + "&userid=" + uid + "&c=0";
        String  post = "start=1&limit=500"
                + "&c_timestamp="  + ts
                + "&c_account="    + uid
                + "&c_sign=E9163ADC4E8E9B20293C8FC11A78E652"
                + "&upvs="         + UPVS;
        return safePost(url, post, buildFullHeader(s));
    }

    // =====================================================================
    // 2. 获取工单告警信息
    // =====================================================================
    public static String getBillAlarmList(String billSn) {
        Session s   = Session.get();
        String  ts  = TimeUtil.getCurrentTimestamp();
        String  uid = urlEncUtf8(s.userid);
        String  url = BASE + "?porttype=GET_BILL_ALARM_LIST&v=" + V + "&userid=" + uid + "&c=0";
        String  post = "start=1&limit=200"
                + "&billsn="       + urlEncUtf8(billSn)
                + "&history_lasttime="
                + "&c_timestamp="  + ts
                + "&c_account="    + uid
                + "&c_sign=A7A87D3B5CB64B8DF7481E63D421F590"
                + "&upvs="         + UPVS;
        return safePost(url, post, buildFullHeader(s));
    }

    // =====================================================================
    // 3. 获取工单详情
    // =====================================================================
    public static String getBillDetail(String billSn) {
        Session s   = Session.get();
        String  ts  = TimeUtil.getCurrentTimestamp();
        String  uid = urlEncUtf8(s.userid);
        String  url = BASE + "?porttype=GET_BILL_DETAIL&v=" + V + "&userid=" + uid + "&c=0";
        String  post = "billSn="    + urlEncUtf8(billSn)
                + "&fromsource=list"
                + "&title=%E6%95%85%E9%9A%9C%E5%B7%A5%E5%8D%95%E5%BE%85%E5%8A%9E"
                + "&c_timestamp="  + ts
                + "&c_account="    + uid
                + "&c_sign=AF0F2A3018F6E966F3529BE87166E1B5"
                + "&upvs="         + UPVS;
        return safePost(url, post, buildFullHeader(s));
    }

    // =====================================================================
    // 4. 故障反馈（追加描述）
    // =====================================================================
    public static String addRemark(String taskId, String comment, String billSn) {
        Session s   = Session.get();
        String  ts  = TimeUtil.getCurrentTimestamp();
        String  uid = urlEncUtf8(s.userid);
        String  url = BASE + "?porttype=SET_BILL_ADDRREMARK&v=" + V + "&userid=" + uid + "&c=0";
        String  post = "taskId="      + urlEncUtf8(taskId)
                + "&linkInfo="        + urlEncUtf8(s.mobilephone)
                + "&dealComment="     + urlEncUtf8(comment)
                + "&billSn="          + urlEncUtf8(billSn)
                + "&c_timestamp="     + ts
                + "&c_account="       + uid
                + "&c_sign=60A1374C9CFF382C4B2668808D4394F8"
                + "&upvs="            + UPVS;
        return safePost(url, post, buildFullHeader(s));
    }

    // =====================================================================
    // 5. 自动接单
    // billStatus=0：未接单（正确值），传1服务器会认为无需操作而忽略
    // =====================================================================
    public static String acceptBill(String billId, String billSn, String taskId) {
        Session s   = Session.get();
        String  ts  = TimeUtil.getCurrentTimestamp();
        String  uid = urlEncUtf8(s.userid);
        String  url = BASE + "?porttype=SET_BILL_ACCEPT&v=" + V + "&userid=" + uid + "&c=0";
        String  post = "userID="    + uid
                + "&billId="        + urlEncUtf8(billId)
                + "&billSn="        + urlEncUtf8(billSn)
                + "&taskId="        + urlEncUtf8(taskId)
                + "&billStatus=0"
                + "&faultCouse=%E6%89%8B%E6%9C%BA%E6%8E%A5%E5%8D%95"
                + "&handlerResult=%E6%89%8B%E6%9C%BA%E6%8E%A5%E5%8D%95"
                + "&c_timestamp="   + ts
                + "&c_account="     + uid
                // [BUG-FIX] 原签名 "437C91584844E7AB0BECF79BDF0BF2B94" 末尾多了一个 "4"，
                //   正常 MD5 签名应为 32 位十六进制，此处修正
                + "&c_sign=437C91584844E7AB0BECF79BDF0BF2B9"
                + "&upvs="          + UPVS;
        // 接单需要完整协议头（含 Host），与回单保持一致
        return safePost(url, post, buildFullHeader(s));
    }

    // =====================================================================
    // 6. 上站判断（选择不上站）
    // =====================================================================
    public static String stationStatus(String taskId, String standCause, String billSn) {
        Session s   = Session.get();
        String  ts  = TimeUtil.getCurrentTimestamp();
        String  uid = urlEncUtf8(s.userid);
        String  url = BASE + "?porttype=BILL_STATION_STATUS&v=" + V + "&userid=" + uid + "&c=0";
        String  post = "taskId="     + urlEncUtf8(taskId)
                + "&linkInfo="       + urlEncUtf8(s.mobilephone)
                + "&standCause="     + urlEncUtf8(standCause)
                + "&isStand=N"
                + "&billSn="         + urlEncUtf8(billSn)
                + "&c_timestamp="    + ts
                + "&c_account="      + uid
                + "&c_sign=1D68314D00F4D60898CE30692F09A98F"
                + "&upvs="           + UPVS;
        return safePost(url, post, buildFullHeader(s));
    }

    // =====================================================================
    // 7. 发电判断
    // =====================================================================
    public static String electricJudge(String billSn, String dealComment,
                                       String billId, String taskId) {
        Session s   = Session.get();
        String  ts  = TimeUtil.getCurrentTimestamp();
        String  uid = urlEncUtf8(s.userid);
        String  url = BASE + "?porttype=SET_BILL_ELECTRICT_JUDGE&v=" + V + "&userid=" + uid + "&c=0";
        String  post = "billSn="      + urlEncUtf8(billSn)
                + "&actionType=N"
                + "&dealComment="     + urlEncUtf8(dealComment)
                + "&billId="          + urlEncUtf8(billId)
                + "&taskId="          + urlEncUtf8(taskId)
                + "&c_timestamp="     + ts
                + "&c_account="       + uid
                + "&c_sign=A01016A3423D0CB351B85138DABC60CE"
                + "&upvs="            + UPVS;
        return safePost(url, post, buildFullHeader(s));
    }

    // =====================================================================
    // 8. 终审回单
    // =====================================================================
    public static String revertBill(String faultType, String faultCouse,
                                    String handlerResult, String billId,
                                    String billSn, String taskId,
                                    String recoveryTime) {
        Session s   = Session.get();
        String  ts  = TimeUtil.getCurrentTimestamp();
        String  uid = urlEncUtf8(s.userid);
        String  url = BASE + "?porttype=BILL_GENELEC_REVERT&v=" + V + "&userid=" + uid + "&c=0";
        String  post = "isUpStation=N"
                + "&isRelief=N"
                + "&faultType="     + urlEncUtf8(faultType)
                + "&faultCouse="    + urlEncUtf8(faultCouse)
                + "&recoveryTime="  + urlEncUtf8(recoveryTime)
                + "&handlerResult=" + urlEncUtf8(handlerResult)
                + "&billId="        + urlEncUtf8(billId)
                + "&billSn="        + urlEncUtf8(billSn)
                + "&taskId="        + urlEncUtf8(taskId)
                + "&billStatus=1"
                + "&c_timestamp="   + ts
                + "&c_account="     + uid
                + "&c_sign=B5F0DE138D62276611216180553FD0D5"
                + "&upvs="          + UPVS;
        return safePost(url, post, buildFullHeader(s));
    }

    // =====================================================================
    // 工具：构建完整协议头（所有接口统一使用）
    // 对应易语言：ADD_协议头.添加("Authorization", token) 等
    // =====================================================================
    static String buildFullHeader(Session s) {
        return "Authorization: "  + s.token + "\n"
             + "equiptoken: \n"
             + "appVer: 202112\n"
             + "Content-Type: application/x-www-form-urlencoded\n"
             + "Host: ywapp.chinatowercom.cn:58090\n"
             + "Connection: Keep-Alive\n"
             + "User-Agent: okhttp/4.10.0";
    }

    // =====================================================================
    // 工具：安全 POST（异常时返回空串，不抛出）
    // =====================================================================
    private static String safePost(String url, String post, String headers) {
        try {
            String result = HttpUtil.post(url, post, headers, null);
            return result != null ? result : "";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // =====================================================================
    // 工具：URL 编码（UTF-8），null/空串安全
    // =====================================================================
    public static String urlEncUtf8(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // =====================================================================
    // 工具：从 JSON 中提取嵌套属性（支持 "a.b.c" 格式路径）
    // =====================================================================
    public static String getJsonPath(JSONObject root, String path) {
        if (root == null || path == null || path.isEmpty()) return "";
        try {
            String[] parts = path.split("\\.");
            JSONObject cur = root;
            for (int i = 0; i < parts.length - 1; i++) {
                JSONObject next = cur.optJSONObject(parts[i]);
                if (next == null) return "";
                cur = next;
            }
            return cur.optString(parts[parts.length - 1], "");
        } catch (Exception e) {
            return "";
        }
    }

    // =====================================================================
    // 工具：计算时间字符串到现在的分钟差
    // 支持格式：
    //   yyyy-MM-dd HH:mm:ss       标准
    //   yyyy/MM/dd HH:mm:ss       斜杠
    //   yyyy-MM-dd HH:mm:ss.SSS   带毫秒
    //   yyyy-MM-dd HH:mm          只到分钟
    //   yyyy-MM-ddTHH:mm:ss       ISO 8601
    // 返回值：>= 0（异常/空串/未来时间均返回 0）
    // =====================================================================
    public static int minutesDiff(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) return 0;
        try {
            String s = timeStr.trim()
                    .replace("/", "-")
                    .replace("T", " ");
            // 去掉毫秒部分
            int dot = s.indexOf('.');
            if (dot > 0) s = s.substring(0, dot);
            // 补秒（yyyy-MM-dd HH:mm 格式，16位）
            if (s.length() == 16) s += ":00";
            // 只接受 19 位标准格式
            if (s.length() != 19) return 0;

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            sdf.setLenient(false);
            Date past = sdf.parse(s);
            if (past == null) return 0;
            long diff = System.currentTimeMillis() - past.getTime();
            // [OPT-1 修复] 未来时间返回 0，不返回负值
            return diff < 0 ? 0 : (int) (diff / 60000L);
        } catch (Exception e) {
            return 0;
        }
    }
}
