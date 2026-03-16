package com.towerops.app.api;

import com.towerops.app.model.Session;
import com.towerops.app.util.HttpUtil;
import com.towerops.app.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 工单操作 API —— 对应易语言中所有 APP_xxx 子程序
 */
public class WorkOrderApi {

    private static final String BASE = "http://ywapp.chinatowercom.cn:58090/itower/mobile/app/service";

    // =====================================================================
    // ★ 版本号 —— 每次升级只改这两行 ★
    //   V    : URL 里的 v=x.x.xx 参数（对应铁塔APP接口版本）
    //   UPVS : POST 里的 upvs=xxxx-xx-xx-ccssoft 参数
    // =====================================================================
    private static final String V    = "1.0.93";
    private static final String UPVS = "2025-04-12-ccssoft";

    // =====================================================================
    // 1. 获取工单监控列表
    // =====================================================================
    public static String getBillMonitorList() {
        Session s    = Session.get();
        String ts    = TimeUtil.getCurrentTimestamp();
        String url   = BASE + "?porttype=GET_BILL_MONITOR_LIST&v=" + V + "&userid=" + s.userid + "&c=0";
        String post  = "start=1&limit=500"
                + "&c_timestamp="  + ts
                + "&c_account="    + s.userid
                + "&c_sign=E9163ADC4E8E9B20293C8FC11A78E652"
                + "&upvs=" + UPVS;
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    // =====================================================================
    // 2. 获取工单告警信息（判断是否告警中）
    // =====================================================================
    public static String getBillAlarmList(String billSn) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=GET_BILL_ALARM_LIST&v=" + V + "&userid=" + s.userid + "&c=0";
        String post = "start=1&limit=200"
                + "&billsn="       + billSn
                + "&history_lasttime="
                + "&c_timestamp="  + ts
                + "&c_account="    + s.userid
                + "&c_sign=A7A87D3B5CB64B8DF7481E63D421F590"
                + "&upvs=" + UPVS;
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    // =====================================================================
    // 3. 获取工单详情页面
    // =====================================================================
    public static String getBillDetail(String billSn) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=GET_BILL_DETAIL&v=" + V + "&userid=" + s.userid + "&c=0";
        String post = "billSn="    + billSn
                + "&fromsource=list"
                + "&title=%E6%95%85%E9%9A%9C%E5%B7%A5%E5%8D%95%E5%BE%85%E5%8A%9E"
                + "&c_timestamp="  + ts
                + "&c_account="    + s.userid
                + "&c_sign=AF0F2A3018F6E966F3529BE87166E1B5"
                + "&upvs=" + UPVS;
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    // =====================================================================
    // 4. 故障反馈（追加描述）
    // =====================================================================
    public static String addRemark(String taskId, String comment, String billSn) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=SET_BILL_ADDRREMARK&v=" + V + "&userid=" + s.userid + "&c=0";
        String post = "taskId="      + taskId
                + "&linkInfo="       + s.mobilephone
                + "&dealComment="    + urlEncUtf8(comment)
                + "&billSn="         + billSn
                + "&c_timestamp="    + ts
                + "&c_account="      + s.userid
                + "&c_sign=60A1374C9CFF382C4B2668808D4394F8"
                + "&upvs=" + UPVS;
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    // =====================================================================
    // 5. 自动接单
    // =====================================================================
    /**
     * 接单请求。
     * billStatus=0 表示"当前工单未接单"，是触发接单动作的正确值，
     * 与原易语言 billStatus=0 完全一致。（传1=已接单，服务器会认为无需操作而忽略）
     */
    public static String acceptBill(String billId, String billSn, String taskId) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=SET_BILL_ACCEPT&v=" + V + "&userid=" + s.userid + "&c=0";
        String post = "userID="     + s.userid
                + "&billId="        + billId
                + "&billSn="        + billSn
                + "&taskId="        + taskId
                + "&billStatus=0"
                + "&faultCouse=%E6%89%8B%E6%9C%BA%E6%8E%A5%E5%8D%95"
                + "&handlerResult=%E6%89%8B%E6%9C%BA%E6%8E%A5%E5%8D%95"
                + "&c_timestamp="   + ts
                + "&c_account="     + s.userid
                + "&c_sign=437C91584844E7AB0BECF79BDF0D2B94"
                + "&upvs=" + UPVS;
        // ★ 接单接口需要完整协议头（含 Host），与回单保持一致，
        //   仅用 s.authHeader 在后台时可能因缺少 Host 被服务器拒绝 ★
        String header = "Authorization: " + s.token + "\n"
                + "equiptoken: \n"
                + "appVer: 202112\n"
                + "Content-Type: application/x-www-form-urlencoded\n"
                + "Host: ywapp.chinatowercom.cn:58090\n"
                + "Connection: Keep-Alive\n"
                + "User-Agent: okhttp/4.10.0";
        return HttpUtil.post(url, post, header, null);
    }

    // =====================================================================
    // 6. 上站判断（选择不上站）
    // =====================================================================
    public static String stationStatus(String taskId, String standCause, String billSn) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=BILL_STATION_STATUS&v=" + V + "&userid=" + s.userid + "&c=0";
        String post = "taskId="      + taskId
                + "&linkInfo="       + s.mobilephone
                + "&standCause="     + urlEncUtf8(standCause)
                + "&isStand=N"
                + "&billSn="         + billSn
                + "&c_timestamp="    + ts
                + "&c_account="      + s.userid
                + "&c_sign=1D68314D00F4D60898CE30692F09A98F"
                + "&upvs=" + UPVS;
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    // =====================================================================
    // 7. 发电判断
    // =====================================================================
    public static String electricJudge(String billSn, String dealComment,
                                       String billId, String taskId) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=SET_BILL_ELECTRICT_JUDGE&v=" + V + "&userid=" + s.userid + "&c=0";
        String post = "billSn="       + billSn
                + "&actionType=N"
                + "&dealComment="     + urlEncUtf8(dealComment)
                + "&billId="          + billId
                + "&taskId="          + taskId
                + "&c_timestamp="     + ts
                + "&c_account="       + s.userid
                + "&c_sign=A01016A3423D0CB351B85138DABC60CE"
                + "&upvs=" + UPVS;
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    // =====================================================================
    // 8. 终审回单
    // =====================================================================
    public static String revertBill(String faultType, String faultCouse,
                                    String handlerResult, String billId,
                                    String billSn, String taskId,
                                    String recoveryTime) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();

        // 回单接口需要带 Authorization token，单独构建协议头
        String extraHeader = "Authorization: " + s.token + "\n"
                + "equiptoken: \n"
                + "appVer: 202112\n"
                + "Content-Type: application/x-www-form-urlencoded\n"
                + "Host: ywapp.chinatowercom.cn:58090\n"
                + "Connection: Keep-Alive\n"
                + "User-Agent: okhttp/4.10.0";

        String url  = BASE + "?porttype=BILL_GENELEC_REVERT&v=" + V + "&userid=" + s.userid + "&c=0";
        String post = "isUpStation=N"
                + "&isRelief=N"
                + "&faultType="     + urlEncUtf8(faultType)
                + "&faultCouse="    + urlEncUtf8(faultCouse)
                + "&recoveryTime="  + recoveryTime
                + "&handlerResult=" + urlEncUtf8(handlerResult)
                + "&billId="        + billId
                + "&billSn="        + billSn
                + "&taskId="        + taskId
                + "&billStatus=1"
                + "&c_timestamp="   + ts
                + "&c_account="     + s.userid
                + "&c_sign=B5F0DE138D62276611216180553FD0D5"
                + "&upvs=" + UPVS;

        return HttpUtil.post(url, post, extraHeader, null);
    }

    // =====================================================================
    // 工具：URL 编码（UTF-8）
    // =====================================================================
    public static String urlEncUtf8(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // =====================================================================
    // 工具：从 JSON 字符串中提取嵌套属性（兼容 "user.mobilephone" 语法）
    // =====================================================================
    public static String getJsonPath(JSONObject root, String path) {
        try {
            String[] parts = path.split("\\.");
            JSONObject cur = root;
            for (int i = 0; i < parts.length - 1; i++) {
                cur = cur.getJSONObject(parts[i]);
            }
            return cur.optString(parts[parts.length - 1], "");
        } catch (Exception e) {
            return "";
        }
    }

    // =====================================================================
    // 工具：计算两个时间字符串的分钟差（对应 取时间间隔()）
    // 兼容格式：
    //   yyyy-MM-dd HH:mm:ss        （标准格式）
    //   yyyy/MM/dd HH:mm:ss        （斜杠格式）
    //   yyyy-MM-dd HH:mm:ss.SSS    （带毫秒）
    //   yyyy-MM-dd HH:mm           （只到分钟，16位）
    //   yyyy-MM-ddTHH:mm:ss        （ISO 8601 带T）
    // =====================================================================
    public static int minutesDiff(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        try {
            // 统一替换斜杠为横杠，去掉毫秒部分，替换T为空格
            String s = timeStr.trim()
                    .replace("/", "-")
                    .replace("T", " ");
            // 截掉毫秒（.SSS 或 .SSSSSS）
            int dotIdx = s.indexOf('.');
            if (dotIdx > 0) s = s.substring(0, dotIdx);
            // 补充秒（若只有16位：yyyy-MM-dd HH:mm）
            if (s.length() == 16) s = s + ":00";

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            sdf.setLenient(false);
            Date past = sdf.parse(s);
            if (past == null) return 0;
            long diff = System.currentTimeMillis() - past.getTime();
            return (int) (diff / 60000L);
        } catch (Exception e) {
            return 0;
        }
    }
}
