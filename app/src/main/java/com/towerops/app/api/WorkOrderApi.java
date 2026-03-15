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

    public static String getBillMonitorList() {
        Session s    = Session.get();
        String ts    = TimeUtil.getCurrentTimestamp();
        String url   = BASE + "?porttype=GET_BILL_MONITOR_LIST&v=1.0.93&userid=" + s.userid + "&c=0";
        String post  = "start=1&limit=500"
                + "&c_timestamp="  + ts
                + "&c_account="    + s.userid
                + "&c_sign=E9163ADC4E8E9B20293C8FC11A78E652"
                + "&upvs=2025-03-15-ccssoft";
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    public static String getBillAlarmList(String billSn) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=GET_BILL_ALARM_LIST&v=1.0.93&userid=" + s.userid + "&c=0";
        String post = "start=1&limit=200"
                + "&billsn="       + billSn
                + "&history_lasttime="
                + "&c_timestamp="  + ts
                + "&c_account="    + s.userid
                + "&c_sign=A7A87D3B5CB64B8DF7481E63D421F590"
                + "&upvs=2025-03-16-ccssoft";
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    public static String getBillDetail(String billSn) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=GET_BILL_DETAIL&v=1.0.93&userid=" + s.userid + "&c=0";
        String post = "billSn="    + billSn
                + "&fromsource=list"
                + "&title=%E6%95%85%E9%9A%9C%E5%B7%A5%E5%8D%95%E5%BE%85%E5%8A%9E"
                + "&c_timestamp="  + ts
                + "&c_account="    + s.userid
                + "&c_sign=AF0F2A3018F6E966F3529BE87166E1B5"
                + "&upvs=2025-04-08-ccssoft";
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    public static String addRemark(String taskId, String comment, String billSn) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=SET_BILL_ADDRREMARK&v=1.0.93&userid=" + s.userid + "&c=0";
        String post = "taskId="      + taskId
                + "&linkInfo="       + s.mobilephone
                + "&dealComment="    + urlEncUtf8(comment)
                + "&billSn="         + billSn
                + "&c_timestamp="    + ts
                + "&c_account="      + s.userid
                + "&c_sign=60A1374C9CFF382C4B2668808D4394F8"
                + "&upvs=2025-03-16-ccssoft";
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    public static String acceptBill(String billId, String billSn, String taskId) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=SET_BILL_ACCEPT&v=1.0.93&userid=" + s.userid + "&c=0";
        // billStatus=1 表示接单成功（原来错误地传了0=未接单）
        String post = "userID="     + s.userid
                + "&billId="        + billId
                + "&billSn="        + billSn
                + "&taskId="        + taskId
                + "&billStatus=1"
                + "&faultCouse=%E6%89%8B%E6%9C%BA%E6%8E%A5%E5%8D%95"
                + "&handlerResult=%E6%89%8B%E6%9C%BA%E6%8E%A5%E5%8D%95"
                + "&c_timestamp="   + ts
                + "&c_account="     + s.userid
                + "&c_sign=437C91584844E7AB0BECF79BDF0D2B94"
                + "&upvs=2025-03-16-ccssoft";
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    public static String stationStatus(String taskId, String standCause, String billSn) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=BILL_STATION_STATUS&v=1.0.93&userid=" + s.userid + "&c=0";
        String post = "taskId="      + taskId
                + "&linkInfo="       + s.mobilephone
                + "&standCause="     + urlEncUtf8(standCause)
                + "&isStand=N"
                + "&billSn="         + billSn
                + "&c_timestamp="    + ts
                + "&c_account="      + s.userid
                + "&c_sign=1D68314D00F4D60898CE30692F09A98F"
                + "&upvs=2025-04-08-ccssoft";
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    public static String electricJudge(String billSn, String dealComment,
                                       String billId, String taskId) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();
        String url  = BASE + "?porttype=SET_BILL_ELECTRICT_JUDGE&v=1.0.93&userid=" + s.userid + "&c=0";
        String post = "billSn="       + billSn
                + "&actionType=N"
                + "&dealComment="     + urlEncUtf8(dealComment)
                + "&billId="          + billId
                + "&taskId="          + taskId
                + "&c_timestamp="     + ts
                + "&c_account="       + s.userid
                + "&c_sign=A01016A3423D0CB351B85138DABC60CE"
                + "&upvs=2025-04-12-ccssoft";
        return HttpUtil.post(url, post, s.authHeader, null);
    }

    public static String revertBill(String faultType, String faultCouse,
                                    String handlerResult, String billId,
                                    String billSn, String taskId,
                                    String recoveryTime) {
        Session s   = Session.get();
        String ts   = TimeUtil.getCurrentTimestamp();

        String extraHeader = "Authorization: " + s.token + "\n"
                + "equiptoken: \n"
                + "appVer: 202112\n"
                + "Content-Type: application/x-www-form-urlencoded\n"
                + "Host: ywapp.chinatowercom.cn:58090\n"
                + "Connection: Keep-Alive\n"
                + "User-Agent: okhttp/4.10.0";

        String url  = BASE + "?porttype=BILL_GENELEC_REVERT&v=1.0.93&userid=" + s.userid + "&c=0";
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
                + "&upvs=2025-04-08-ccssoft";

        return HttpUtil.post(url, post, extraHeader, null);
    }

    public static String urlEncUtf8(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

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

    public static int minutesDiff(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        try {
            String normalized = timeStr.replace("-", "/");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault());
            Date past = sdf.parse(normalized);
            if (past == null) return 0;
            long diff = System.currentTimeMillis() - past.getTime();
            return (int) (diff / 60000L);
        } catch (Exception e) {
            return 0;
        }
    }
}
