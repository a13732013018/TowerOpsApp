package com.towerops.app.util;

import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 网络请求工具类 —— 对应易语言的 网页_访问_对象()
 * 使用 OkHttp 4.x，复用单例客户端（Keep-Alive 自动生效）
 */
public class HttpUtil {

    private static final MediaType FORM_TYPE =
            MediaType.parse("application/x-www-form-urlencoded");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * POST 请求，返回响应体字符串
     *
     * @param url     目标地址
     * @param post    POST 参数字符串（application/x-www-form-urlencoded 格式）
     * @param headers 附加协议头（格式："Key: Value\nKey2: Value2"），可为 null
     * @param cookie  Cookie 字符串，可为 null
     * @return 响应体，失败返回空字符串
     */
    public static String post(String url, String post, String headers, String cookie) {
        try {
            RequestBody body = RequestBody.create(post, FORM_TYPE);
            Request.Builder builder = new Request.Builder()
                    .url(url.trim())
                    .post(body);

            // 默认协议头
            builder.header("User-Agent", "okhttp/4.10.0");
            builder.header("Connection", "Keep-Alive");

            // 附加 cookie
            if (cookie != null && !cookie.isEmpty()) {
                builder.header("Cookie", cookie);
            }

            // 解析自定义协议头（换行分隔，格式 "Key: Value"）
            if (headers != null && !headers.isEmpty()) {
                String[] lines = headers.split("\n");
                for (String line : lines) {
                    int idx = line.indexOf(": ");
                    if (idx > 0) {
                        String key = line.substring(0, idx).trim();
                        String val = line.substring(idx + 2).trim();
                        builder.header(key, val);
                    }
                }
            }

            Request request = builder.build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * POST 请求（无 Cookie，无自定义头）
     */
    public static String post(String url, String post) {
        return post(url, post, null, null);
    }

    /**
     * GET 请求，返回字节数组（用于图片等二进制内容）
     * 同时会自动将响应的 Set-Cookie 存入 CookieStore
     */
    public static byte[] getBytes(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url.trim())
                    .get()
                    .header("User-Agent", "okhttp/4.10.0")
                    .header("Connection", "Keep-Alive")
                    .build();

            try (okhttp3.Response response = CLIENT.newCall(request).execute()) {
                // 提取并保存 Cookie
                String setCookie = response.header("Set-Cookie");
                if (setCookie != null && !setCookie.isEmpty()) {
                    // 取 name=value 部分（分号前）
                    String cookiePart = setCookie.split(";")[0].trim();
                    com.towerops.app.util.CookieStore.saveCookie(cookiePart);
                }
                if (response.body() != null) {
                    return response.body().bytes();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
