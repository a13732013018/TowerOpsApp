package com.towerops.app.api;

import com.towerops.app.model.Session;
import com.towerops.app.util.HttpUtil;
import com.towerops.app.util.SignUtil;
import com.towerops.app.util.TimeUtil;

import org.json.JSONObject;

/**
 * 登录相关 API —— 对应易语言按钮59（获取验证码）和按钮51（PIN码登录）
 *
 * [BUG-FIX] userid/c_account 硬编码问题
 *   原代码：发短信(4A_LOGIN_SMS_SEND)和PIN登录(4A_LOGIN_CHECK_PIN)的 URL 及 POST 里
 *           userid 和 c_account 全部硬编码为 "2662936450"，不管选哪个账号登录
 *           都用这个固定 ID 请求，导致服务器返回的 token/userid 对应的是该固定账号，
 *           而不是当前选中的账号，造成后台接单时身份错乱。
 *   修复：改为动态使用当前 account 字段作为 c_account，
 *         URL 中的 userid 参数使用临时占位值（服务器在未登录时不校验该字段），
 *         登录成功后 Session.userid 由服务器返回的真实 userid 覆盖。
 */
public class LoginApi {

    private static final String BASE = "http://ywapp.chinatowercom.cn:58090/itower/mobile/app/service";

    // =====================================================================
    // 1. 获取短信验证码（两步：先校验账号，再发送短信）
    // =====================================================================
    public static class SmsResult {
        public boolean success;
        public String  message;
    }

    public static SmsResult sendSmsCode(String account, String password,
                                        String verifyCode, String cookie) {
        SmsResult result = new SmsResult();
        String ts   = TimeUtil.getCurrentTimestamp();
        String sign = SignUtil.buildLoginSign(account, ts);

        // --- 第一步：登录校验 ---
        String url1  = BASE + "?porttype=USER_LOGIN&v=1.0.93&c=0";
        String post1 = "loginName=" + account
                + "&password="        + password
                + "&loginVersion=202206"
                + "&verifyCode="      + verifyCode
                + "&clientType=0"
                + "&osversion=android%3A14"
                + "&softversion=1.0.93"
                + "&phoneinfo=M2011K2C"
                + "&pin=&imsi=&phoneNum=&appacctid=&token=&pushtype=1"
                + "&c_timestamp="    + ts
                + "&c_account="      + account
                + "&c_sign="         + sign
                + "&upvs=2025-03-15-ccssoft";

        String str1 = HttpUtil.post(url1, post1, null, cookie);
        try {
            JSONObject j1 = new JSONObject(str1);
            if (!"OK".equals(j1.optString("status"))) {
                result.success = false;
                result.message = "验证码错误";
                return result;
            }
        } catch (Exception e) {
            result.success = false;
            result.message = "第一步解析失败: " + str1;
            return result;
        }

        // --- 第二步：发送短信 ---
        ts = TimeUtil.getCurrentTimestamp();
        // [BUG-FIX] userid 和 c_account 由硬编码改为动态使用当前 account
        String url2  = BASE + "?porttype=4A_LOGIN_SMS_SEND&v=1.0.93&userid=" + account + "&c=0";
        String post2 = "loginName="     + account
                + "&password="          + password
                + "&loginVersion=202206"
                + "&clientType=0"
                + "&osversion=android%3A14"
                + "&softversion=1.0.93"
                + "&phoneinfo=M2011K2C"
                + "&c_timestamp="       + ts
                + "&c_account="         + account
                + "&c_sign="            + sign
                + "&upvs=2025-03-15-ccssoft";

        String str2 = HttpUtil.post(url2, post2, null, cookie);
        try {
            JSONObject j2 = new JSONObject(str2);
            result.success = "OK".equals(j2.optString("status"));
            result.message = result.success ? "验证码已发送" : "短信发送失败: " + str2;
        } catch (Exception e) {
            result.success = false;
            result.message = "第二步解析失败: " + str2;
        }
        return result;
    }

    // =====================================================================
    // 2. PIN 码登录（对应按钮51）
    // =====================================================================
    public static class LoginResult {
        public boolean success;
        public String  message;
        public String  userid;
        public String  token;
        public String  mobilephone;
        public String  username;
    }

    public static LoginResult loginWithPin(String account, String password,
                                           String verifyCode, String pin) {
        LoginResult result = new LoginResult();
        String ts   = TimeUtil.getCurrentTimestamp();
        String sign = SignUtil.buildLoginSign(account, ts);

        // [BUG-FIX] userid 和 c_account 由硬编码改为动态使用当前 account
        String url  = BASE + "?porttype=4A_LOGIN_CHECK_PIN&v=1.0.93&userid=" + account + "&c=0";
        String post = "loginName="     + account
                + "&password="         + password
                + "&loginVersion=202206"
                + "&verifyCode="       + verifyCode
                + "&clientType=0"
                + "&osversion=android%3A14"
                + "&softversion=1.0.93"
                + "&phoneinfo=M2011K2C"
                + "&pin="              + pin
                + "&pushtype=1"
                + "&c_timestamp="      + ts
                + "&c_account="        + account
                + "&c_sign="           + sign
                + "&upvs=2025-03-15-ccssoft";

        String str = HttpUtil.post(url, post);
        try {
            JSONObject json = new JSONObject(str);
            result.userid      = getNestedStr(json, "user.userid");
            result.token       = json.optString("token", "");
            result.mobilephone = getNestedStr(json, "user.mobilephone");
            result.username    = getNestedStr(json, "user.username");
            result.success     = str.contains("mobilephone");
            result.message     = result.success ? "登录成功" : "登录失败";
        } catch (Exception e) {
            result.success = false;
            result.message = "JSON解析失败: " + str;
        }
        return result;
    }

    /** 解析 "user.userid" 这类带点的路径 */
    private static String getNestedStr(JSONObject root, String path) {
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
}
