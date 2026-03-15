package com.towerops.app.util;

/**
 * 全局 Cookie 存储 —— 对应易语言中的 运维APP_cookie 变量
 * 在获取验证码图片时自动保存服务器返回的 Set-Cookie，
 * 后续登录请求携带此 Cookie 以保持会话
 */
public class CookieStore {

    private static volatile String cookie = "";

    /** 保存服务器返回的 Cookie（自动去重拼接） */
    public static synchronized void saveCookie(String newCookie) {
        if (newCookie == null || newCookie.isEmpty()) return;
        // 直接覆盖（验证码场景下只需要最新的 JSESSIONID）
        cookie = newCookie;
    }

    /** 获取当前保存的 Cookie */
    public static String getCookie() {
        return cookie;
    }

    /** 清除 Cookie（登出时调用） */
    public static void clear() {
        cookie = "";
    }
}
