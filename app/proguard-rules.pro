# ============================================================
#  TowerOps App — ProGuard 混淆规则
#  作用：release 包混淆后，反编译 APK 看到的是无意义的 a/b/c 类名，
#        账号密码、接口路径、业务逻辑均无法直接读出，大幅提高安全性。
# ============================================================

# ── Android 基础保留（必须，否则四大组件无法运行）──────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# ── View Binding / Layout 相关 ─────────────────────────────────────────
-keep class * implements android.os.Parcelable { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── OkHttp / Okio ──────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── org.json（标准库，保留避免反射错误）────────────────────────────────
-keep class org.json.** { *; }

# ── AndroidX / Material ────────────────────────────────────────────────
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ── JobScheduler 保活组件（系统通过反射启动，不能混淆）────────────────
-keep class com.towerops.app.worker.MonitorJobService { *; }

# ── 内部接口/回调（反射或多态调用，必须保留方法签名）──────────────────
-keep interface com.towerops.app.worker.MonitorTask$MonitorCallback { *; }
-keep interface com.towerops.app.worker.WorkerTask$UiCallback { *; }
-keep interface com.towerops.app.worker.MonitorService$ServiceCallback { *; }

# ── WorkOrder 数据模型（JSON 解析字段名必须与服务器一致）──────────────
-keep class com.towerops.app.model.WorkOrder { *; }

# ── 去除 Log 调用（release 包不应输出任何日志）─────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ── 通用安全规则 ────────────────────────────────────────────────────────
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
