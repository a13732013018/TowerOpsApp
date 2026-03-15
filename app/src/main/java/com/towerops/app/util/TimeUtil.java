package com.towerops.app.util;

/**
 * 时间戳工具类 —— 对应易语言的 时间_取现行时间戳()
 * 返回秒级时间戳字符串
 */
public class TimeUtil {

    public static String getCurrentTimestamp() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }
}
