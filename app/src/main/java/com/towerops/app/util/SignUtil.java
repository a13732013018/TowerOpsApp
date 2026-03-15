package com.towerops.app.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 签名工具类 —— 对应易语言的 取数据摘要(到字节集(明文)) 并 到大写
 */
public class SignUtil {

    /**
     * 计算 MD5 并返回大写十六进制字符串
     */
    public static String md5Upper(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 生成登录签名
     * 原易语言：c_account + "&c_timestamp=" + 时间戳 + "&loginName=" + c_account
     */
    public static String buildLoginSign(String account, String timestamp) {
        String plain = "c_account=" + account + "&c_timestamp=" + timestamp + "&loginName=" + account;
        return md5Upper(plain);
    }
}
