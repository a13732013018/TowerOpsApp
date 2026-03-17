package com.towerops.app.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * 全局会话信息 —— 登录成功后持久保存，供所有线程使用（等价于易语言全局变量）
 */
public class Session {

    private static volatile Session instance;

    private Session() {}

    public static Session get() {
        if (instance == null) {
            synchronized (Session.class) {
                if (instance == null) instance = new Session();
            }
        }
        return instance;
    }

    // ---------- 登录后写入 ----------
    public volatile String userid       = "";
    public volatile String token        = "";   // Authorization 值，发请求时动态组头
    public volatile String mobilephone  = "";
    public volatile String username     = "";
    /**
     * 真实姓名（中文），来自 AccountConfig 第三列。
     * 用于与工单 actionlist 中的 acceptOperator（中文接单人姓名）比对，
     * 以决定是否触发自动接单/回单。
     *
     * [BUG-FIX] 原代码用 username（账号工号）比对中文姓名，永远不等，后台接单/回单无法触发。
     */
    public volatile String realname     = "";

    // ---------- 运行时配置（主线程写，工作线程读）----------
    // ★ appConfig 同时保存在内存和 SharedPreferences，服务重建后可从 prefs 恢复 ★
    public volatile String appConfig    = ""; // 选1|选2|选5|阈值反馈|阈值接单 用 \u0001 分隔
    public volatile String[] taskArray  = new String[0];

    private static final String PREF_SESSION    = "session_prefs_enc";  // 改名，与旧明文 prefs 隔离
    private static final String KEY_APP_CONFIG  = "app_config";
    private static final String KEY_USERID      = "userid";
    private static final String KEY_TOKEN       = "token";
    private static final String KEY_MOBILE      = "mobilephone";
    private static final String KEY_USERNAME    = "username";
    private static final String KEY_REALNAME    = "realname";

    /**
     * 获取 AES256 加密的 SharedPreferences 实例。
     * Token/userid 等敏感凭据用此方法存取，无法通过 /data/data 文件直接读出明文。
     * 若创建加密存储失败（低版本ROM罕见），降级到普通 SharedPreferences 保证不崩溃。
     */
    private static SharedPreferences getSecurePrefs(Context ctx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    ctx.getApplicationContext(),
                    PREF_SESSION,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.w("Session", "EncryptedSharedPreferences 创建失败，降级到普通存储", e);
            return ctx.getApplicationContext()
                      .getSharedPreferences(PREF_SESSION + "_plain", Context.MODE_PRIVATE);
        }
    }

    /**
     * 将 appConfig 持久化（加密存储）。
     * 在 MainActivity.buildConfig() 写入 appConfig 后立刻调用。
     */
    public void saveConfig(Context ctx) {
        getSecurePrefs(ctx)
           .edit()
           .putString(KEY_APP_CONFIG, appConfig)
           .apply();
    }

    /**
     * 登录成功后调用：把登录凭据（token/userid 等）写入加密 SharedPreferences。
     * 服务被系统重建（START_STICKY）时进程可能重启，内存变量丢失，
     * 必须持久化才能让后台接单的 Authorization 头带上正确的 token。
     * ★ 使用 AES256 加密，即使手机被 root 也无法直接读出明文 token ★
     */
    public void saveLogin(Context ctx) {
        getSecurePrefs(ctx)
           .edit()
           .putString(KEY_USERID,      userid)
           .putString(KEY_TOKEN,       token)
           .putString(KEY_MOBILE,      mobilephone)
           .putString(KEY_USERNAME,    username)
           .putString(KEY_REALNAME,    realname)
           .apply();
    }

    /**
     * 从加密 SharedPreferences 恢复 appConfig 和登录凭据（服务重建/进程恢复时调用）。
     * 若 prefs 里没有，对应字段保持原值不变。
     */
    public void loadConfig(Context ctx) {
        SharedPreferences sp = getSecurePrefs(ctx);

        String savedConfig = sp.getString(KEY_APP_CONFIG, "");
        if (!savedConfig.isEmpty()) appConfig = savedConfig;

        // ★ 恢复登录凭据：服务重建后 token/userid 等内存变量会清空，
        //   acceptBill() 需要用 s.token 构建 Authorization 头，
        //   若 token 为空则服务器鉴权失败，接单被拒 ★
        String savedToken = sp.getString(KEY_TOKEN, "");
        if (!savedToken.isEmpty()) {
            token       = savedToken;
            userid      = sp.getString(KEY_USERID,    userid);
            mobilephone = sp.getString(KEY_MOBILE,    mobilephone);
            username    = sp.getString(KEY_USERNAME,  username);
            realname    = sp.getString(KEY_REALNAME,  realname);
        }
    }

    // ---------- 并发计数（用 synchronized 保护）----------
    private int runningThreads = 0;
    private int finishedCount  = 0;
    private int totalCount     = 0;

    public synchronized void resetProgress(int total) {
        this.totalCount     = total;
        this.runningThreads = 0;
        this.finishedCount  = 0;
    }

    public synchronized boolean tryAcquireSlot(int maxSlots) {
        if (runningThreads < maxSlots) { runningThreads++; return true; }
        return false;
    }

    public synchronized void releaseSlot() {
        runningThreads--;
        finishedCount++;
    }

    public synchronized boolean allDone() {
        return finishedCount >= totalCount;
    }

    public synchronized int getFinished() { return finishedCount; }
    public synchronized int getTotal()    { return totalCount; }
}
