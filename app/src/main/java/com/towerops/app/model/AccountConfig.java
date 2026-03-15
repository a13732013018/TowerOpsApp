package com.towerops.app.model;

/**
 * 账号配置数据模型
 */
public class AccountConfig {

    public static final String[][] ACCOUNTS = {
            {"wx-linjy22",       "z0J1CVrRPjfQgO4jhLuJwg%3D%3D"},
            {"wx-liujj6",        "MwAPfB0gVI3Ddfk%2BByiG3Q%3D%3D"},
            {"wx-linyl",         "z0J1CVrRPjfQgO4jhLuJwg%3D%3D"},
            {"wx-maoll5",        "z0J1CVrRPjfQgO4jhLuJwg%3D%3D"},
            {"wx-jinlz",         "z0J1CVrRPjfQgO4jhLuJwg%3D%3D"},
            {"wx-wangjj96",      "uwhpKacQXX1aC3eyE9rkQg%3D%3D"},
            {"%20%20wx-liusl35", "0b1XBanT0rLwBl%2BMIo87Lw%3D%3D"},
    };

    /** 返回下拉框显示名（脱敏展示，去除前缀空格） */
    public static String[] getDisplayNames() {
        String[] names = new String[ACCOUNTS.length];
        for (int i = 0; i < ACCOUNTS.length; i++) {
            names[i] = ACCOUNTS[i][0].replace("%20", "").trim();
        }
        return names;
    }
}
