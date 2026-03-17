package com.towerops.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.towerops.app.R;
import com.towerops.app.model.Session;
import com.towerops.app.model.WorkOrder;
import com.towerops.app.worker.MonitorService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面
 *
 * ══════════════ 已修复的 Bug 清单 ══════════════
 *
 * [BUG-5] onPause 置 setCallback(null) → 后台运行期间完全失联
 *   原代码：onPause 调 monitorService.setCallback(null)，
 *           导致后台运行时 callback=null，
 *           MonitorService 里直接调 callback.onXxx() 抛 NPE，
 *           接单/反馈结果全部静默丢失，且服务内有可能因 NPE 崩溃。
 *   修复：改为 setCallback(serviceCallback, silent=true)，
 *         保留引用、关闭 UI 更新，onResume 时传 silent=false 恢复显示。
 *
 * [BUG-17] onAllDone 回调调 syncConfigFromSession，覆盖用户手动设置
 *   原代码：每轮工单处理完后，把 Session.appConfig 里的值同步回 CheckBox，
 *           而 applyTimeSchedule（已删除）或其他逻辑修改了 appConfig，
 *           用户手动关掉的反馈/接单开关在下一轮完成后被强制恢复为开。
 *   修复：删除 syncConfigFromSession() 调用，onAllDone 只更新进度文字。
 *
 * [BUG-18] 每次键盘敲击都写 SharedPreferences（高频 IO）
 *   原代码：EditText TextWatcher.afterTextChanged → applyConfigNow → saveConfig，
 *           用户输入"120"要调3次 apply()。
 *   修复：加 300ms 防抖，连续输入停止后只写一次。
 *
 * [BUG-19] setCallback 接口改为双参数，调用处需同步更新
 *   修复：onServiceConnected / onResume / onPause 全部更新。
 *
 * [BUG-20] setInterval 改名为 setIntervalAndReschedule，调用处需同步更新
 *   修复：startMonitor / applyConfigNow 全部更新。
 */
public class MainActivity extends AppCompatActivity {

    // UI 控件
    private TextView        tvUserInfo, tvProgress, tvNextRun, btnLogout;
    private TextView        btnSortBillTime, btnSortFeedbackTime,
                            btnSortAlertTime, btnSortAlertStatus, tvSortDesc;
    private CheckBox        cbFeedback, cbAccept, cbRevert;
    private EditText        etFbMin, etFbMax, etAccMin, etAccMax, etIntMin, etIntMax;
    private Button          btnStart, btnStop;
    private WorkOrderAdapter adapter;

    // 服务绑定
    private MonitorService  monitorService;
    private boolean         serviceBound = false;

    // [BUG-18] 防抖 Handler
    private final Handler  debounceHandler  = new Handler(Looper.getMainLooper());
    private       Runnable debounceRunnable;

    // 本地倒计时：前台时自己每秒递减，不依赖 Service 推送
    private CountDownTimer countDownTimer;

    // ── 服务连接 ──────────────────────────────────────────────────────────

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MonitorService.LocalBinder lb = (MonitorService.LocalBinder) service;
            monitorService = lb.getService();
            serviceBound   = true;
            // [BUG-19 修复] silent=false：Activity 在前台，开启 UI 更新
            monitorService.setCallback(serviceCallback, false);
            syncButtonState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound   = false;
            monitorService = null;
        }
    };

    // 服务回调（所有方法均在主线程执行）
    private final MonitorService.ServiceCallback serviceCallback =
            new MonitorService.ServiceCallback() {

        @Override
        public void onOrdersReady(List<WorkOrder> orders) {
            tvProgress.setText("共 " + orders.size() + " 条工单，处理中...");
            adapter.setData(orders);
        }

        @Override
        public void onStatusUpdate(int rowIndex, String billsn, String content) {
            adapter.updateStatus(rowIndex, billsn, content);
        }

        @Override
        public void onAllDone(int done, int total) {
            // [BUG-17 修复] 不再调 syncConfigFromSession，避免覆盖用户设置
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvProgress.setText("本轮完成 " + done + "/" + total + " 条  " + time);
        }

        @Override
        public void onError(String msg) {
            tvProgress.setText("错误：" + msg);
        }

        @Override
        public void onNextRun(int delaySec) {
            startCountDown(delaySec);
        }
    };

    // ── 生命周期 ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupRecycler();
        setupSortButtons();
        setupConfigWatchers();
        updateUserInfo();

        btnStart.setOnClickListener(v -> startMonitor());
        btnStop.setOnClickListener(v  -> stopMonitor());
        btnLogout.setOnClickListener(v -> doLogout());

        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (serviceBound && monitorService != null) {
            // [BUG-5 修复] 恢复前台：取消静默模式，重新接收 UI 更新
            monitorService.setCallback(serviceCallback, false);
            syncButtonState();
            // 恢复倒计时：从 prefs 读取下次执行时间，重新开始本地倒数
            long nextAt   = monitorService.getNextRunAt();
            int  delaySec = (int) Math.max(0, (nextAt - System.currentTimeMillis()) / 1000);
            if (delaySec > 0) startCountDown(delaySec);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // [BUG-5 修复] 退后台：保留 callback 引用但静默，Service 继续全速运行
        if (serviceBound && monitorService != null) {
            monitorService.setCallback(serviceCallback, true);
        }
        // 后台不需要本地倒计时，停掉省资源
        stopCountDown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        debounceHandler.removeCallbacks(debounceRunnable);
        stopCountDown();
        if (serviceBound) {
            unbindService(conn);
            serviceBound = false;
        }
        // 不 stopService，让服务继续后台运行
    }

    // ── 开始 / 停止 ───────────────────────────────────────────────────────

    private void startMonitor() {
        if (!serviceBound || monitorService == null) {
            Toast.makeText(this, "服务未就绪，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (monitorService.isRunning()) {
            Toast.makeText(this, "监控已在运行", Toast.LENGTH_SHORT).show();
            return;
        }
        buildConfig();
        // [BUG-20 修复] 改用新方法名，触发立即重新调度
        monitorService.setIntervalAndReschedule(
                parseInt(etIntMin.getText().toString(), 90),
                parseInt(etIntMax.getText().toString(), 120));
        monitorService.startMonitor();
        syncButtonState();
        tvProgress.setText("等待首次执行...");
    }

    private void stopMonitor() {
        if (!serviceBound || monitorService == null) return;
        monitorService.stopMonitor();
        syncButtonState();
        tvProgress.setText("已停止");
        tvNextRun.setText("");
    }

    /** 退出登录：清除本地凭据，停止服务，跳回登录页 */
    private void doLogout() {
        // 停止监控服务
        if (serviceBound && monitorService != null) {
            monitorService.stopMonitor();
        }
        // 清除内存里的登录信息
        Session s = Session.get();
        s.token       = "";
        s.userid      = "";
        s.mobilephone = "";
        s.username    = "";
        s.realname    = "";
        s.appConfig   = "";
        // 清除 SharedPreferences 里的持久化登录信息
        getApplicationContext()
            .getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply();
        // 停止服务（退出登录后不需要继续后台跑）
        stopService(new Intent(this, MonitorService.class));
        // 跳回登录页
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void syncButtonState() {
        if (monitorService != null && monitorService.isRunning()) {
            btnStart.setText("监控中...");
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        } else {
            btnStart.setText("开始监控");
            btnStart.setEnabled(true);
            btnStop.setEnabled(true);
        }
    }

    // ── 配置监听 ──────────────────────────────────────────────────────────

    private void setupConfigWatchers() {
        // [BUG-18 修复] TextWatcher 通过防抖触发，连续输入停止300ms后才写入
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                scheduleApplyConfig();
            }
        };
        etFbMin.addTextChangedListener(watcher);
        etFbMax.addTextChangedListener(watcher);
        etAccMin.addTextChangedListener(watcher);
        etAccMax.addTextChangedListener(watcher);
        etIntMin.addTextChangedListener(watcher);
        etIntMax.addTextChangedListener(watcher);

        cbFeedback.setOnCheckedChangeListener((btn, checked) -> applyConfigNow());
        cbAccept.setOnCheckedChangeListener(  (btn, checked) -> applyConfigNow());
        cbRevert.setOnCheckedChangeListener(  (btn, checked) -> applyConfigNow());
    }

    private void scheduleApplyConfig() {
        if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        debounceRunnable = this::applyConfigNow;
        debounceHandler.postDelayed(debounceRunnable, 300L);
    }

    /**
     * 立即将当前 UI 配置写入 Session 并通知 Service 重新调度轮询间隔。
     */
    private void applyConfigNow() {
        buildConfig();
        if (serviceBound && monitorService != null) {
            // [BUG-20 修复] 改用新方法名，阈值修改立即生效
            monitorService.setIntervalAndReschedule(
                    parseInt(etIntMin.getText().toString(), 90),
                    parseInt(etIntMax.getText().toString(), 120));
        }
    }

    private void buildConfig() {
        Session s        = Session.get();
        String fb        = cbFeedback.isChecked() ? "true" : "false";
        String acc       = cbAccept.isChecked()   ? "true" : "false";
        String rev       = cbRevert.isChecked()   ? "true" : "false";
        String fbMinStr  = defaultIfEmpty(etFbMin.getText().toString().trim(),  "70");
        String fbMaxStr  = defaultIfEmpty(etFbMax.getText().toString().trim(),  "90");
        String accMinStr = defaultIfEmpty(etAccMin.getText().toString().trim(), "60");
        String accMaxStr = defaultIfEmpty(etAccMax.getText().toString().trim(), "90");

        s.appConfig = fb  + "\u0001"
                    + acc + "\u0001"
                    + rev + "\u0001"
                    + fbMinStr  + "|" + fbMaxStr  + "\u0001"
                    + accMinStr + "|" + accMaxStr;
        s.saveConfig(this);
    }

    // ── 视图初始化 ────────────────────────────────────────────────────────

    private void bindViews() {
        tvUserInfo          = findViewById(R.id.tvUserInfo);
        tvProgress          = findViewById(R.id.tvProgress);
        tvNextRun           = findViewById(R.id.tvNextRun);
        btnLogout           = findViewById(R.id.btnLogout);
        btnSortBillTime     = findViewById(R.id.btnSortBillTime);
        btnSortFeedbackTime = findViewById(R.id.btnSortFeedbackTime);
        btnSortAlertTime    = findViewById(R.id.btnSortAlertTime);
        btnSortAlertStatus  = findViewById(R.id.btnSortAlertStatus);
        tvSortDesc          = findViewById(R.id.tvSortDesc);
        cbFeedback  = findViewById(R.id.cbAutoFeedback);
        cbAccept    = findViewById(R.id.cbAutoAccept);
        cbRevert    = findViewById(R.id.cbAutoRevert);
        etFbMin     = findViewById(R.id.etFeedbackMin);
        etFbMax     = findViewById(R.id.etFeedbackMax);
        etAccMin    = findViewById(R.id.etAcceptMin);
        etAccMax    = findViewById(R.id.etAcceptMax);
        etIntMin    = findViewById(R.id.etIntervalMin);
        etIntMax    = findViewById(R.id.etIntervalMax);
        btnStart    = findViewById(R.id.btnStartMonitor);
        btnStop     = findViewById(R.id.btnStopMonitor);
    }

    private void setupRecycler() {
        RecyclerView rv = findViewById(R.id.recyclerOrders);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new WorkOrderAdapter();
        rv.setAdapter(adapter);
    }

    private void setupSortButtons() {
        btnSortBillTime.setOnClickListener(v -> {
            WorkOrderAdapter.SortMode cur = adapter.getSortMode();
            adapter.setSortMode(cur == WorkOrderAdapter.SortMode.BILL_TIME_DESC
                    ? WorkOrderAdapter.SortMode.BILL_TIME_ASC
                    : WorkOrderAdapter.SortMode.BILL_TIME_DESC);
            updateSortUI();
        });
        btnSortFeedbackTime.setOnClickListener(v -> {
            WorkOrderAdapter.SortMode cur = adapter.getSortMode();
            adapter.setSortMode(cur == WorkOrderAdapter.SortMode.FEEDBACK_TIME_DESC
                    ? WorkOrderAdapter.SortMode.FEEDBACK_TIME_ASC
                    : WorkOrderAdapter.SortMode.FEEDBACK_TIME_DESC);
            updateSortUI();
        });
        btnSortAlertTime.setOnClickListener(v -> {
            WorkOrderAdapter.SortMode cur = adapter.getSortMode();
            adapter.setSortMode(cur == WorkOrderAdapter.SortMode.ALERT_TIME_DESC
                    ? WorkOrderAdapter.SortMode.ALERT_TIME_ASC
                    : WorkOrderAdapter.SortMode.ALERT_TIME_DESC);
            updateSortUI();
        });
        btnSortAlertStatus.setOnClickListener(v -> {
            WorkOrderAdapter.SortMode cur = adapter.getSortMode();
            adapter.setSortMode(cur == WorkOrderAdapter.SortMode.ALERT_STATUS_ALARM
                    ? WorkOrderAdapter.SortMode.ALERT_STATUS_RECOVER
                    : WorkOrderAdapter.SortMode.ALERT_STATUS_ALARM);
            updateSortUI();
        });
        updateSortUI();
    }

    private void updateSortUI() {
        WorkOrderAdapter.SortMode mode = adapter.getSortMode();
        btnSortBillTime.setTextColor(0xffa0a0c0);
        btnSortFeedbackTime.setTextColor(0xffa0a0c0);
        btnSortAlertTime.setTextColor(0xffa0a0c0);
        btnSortAlertStatus.setTextColor(0xffa0a0c0);
        btnSortBillTime.setText("工单历时 ↕");
        btnSortFeedbackTime.setText("反馈历时 ↕");
        btnSortAlertTime.setText("告警时间 ↕");
        btnSortAlertStatus.setText("告警状态 ↕");

        switch (mode) {
            case BILL_TIME_DESC:
                btnSortBillTime.setText("工单历时 ↓");
                btnSortBillTime.setTextColor(0xffffffff);
                tvSortDesc.setText("工单历时 大→小"); break;
            case BILL_TIME_ASC:
                btnSortBillTime.setText("工单历时 ↑");
                btnSortBillTime.setTextColor(0xffffffff);
                tvSortDesc.setText("工单历时 小→大"); break;
            case FEEDBACK_TIME_DESC:
                btnSortFeedbackTime.setText("反馈历时 ↓");
                btnSortFeedbackTime.setTextColor(0xffffffff);
                tvSortDesc.setText("反馈历时 大→小"); break;
            case FEEDBACK_TIME_ASC:
                btnSortFeedbackTime.setText("反馈历时 ↑");
                btnSortFeedbackTime.setTextColor(0xffffffff);
                tvSortDesc.setText("反馈历时 小→大"); break;
            case ALERT_TIME_DESC:
                btnSortAlertTime.setText("告警时间 ↓");
                btnSortAlertTime.setTextColor(0xffffffff);
                tvSortDesc.setText("告警时间 最新→最旧"); break;
            case ALERT_TIME_ASC:
                btnSortAlertTime.setText("告警时间 ↑");
                btnSortAlertTime.setTextColor(0xffffffff);
                tvSortDesc.setText("告警时间 最旧→最新"); break;
            case ALERT_STATUS_ALARM:
                btnSortAlertStatus.setText("告警中优先 ↓");
                btnSortAlertStatus.setTextColor(0xffff6b35);
                tvSortDesc.setText("告警中优先"); break;
            case ALERT_STATUS_RECOVER:
                btnSortAlertStatus.setText("已恢复优先 ↓");
                btnSortAlertStatus.setTextColor(0xff40c080);
                tvSortDesc.setText("已恢复优先"); break;
        }
    }

    private void updateUserInfo() {
        Session s = Session.get();
        tvUserInfo.setText(s.username.isEmpty() ? "未登录" : s.username + " | " + s.userid);
    }

    // ── 静态工具 ──────────────────────────────────────────────────────────

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static String defaultIfEmpty(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }

    /**
     * 启动本地倒计时，每秒更新 tvNextRun，倒完显示"即将执行..."。
     * 多次调用时自动取消上次再重新开始。
     */
    private void startCountDown(int delaySec) {
        stopCountDown();
        tvNextRun.setText("下次：" + delaySec + "秒后");
        countDownTimer = new CountDownTimer(delaySec * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                tvNextRun.setText("下次：" + (millisUntilFinished / 1000) + "秒后");
            }
            @Override
            public void onFinish() {
                tvNextRun.setText("即将执行...");
            }
        }.start();
    }

    /** 停止并销毁本地倒计时 */
    private void stopCountDown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    // ── 后台保活：厂商白名单 ──────────────────────────────────────────────

    /**
     * 跳转到对应厂商的后台管理/自启动白名单设置页面。
     * 国产 ROM 的后台杀进程不仅受电池优化控制，还有独立的"自启动"/"后台运行"开关，
     * 必须在厂商自己的设置页里手动开启，代码层面无法绕过。
     * 在"开始监控"按钮点击时调用，提示用户手动操作一次即可。
     */
    private void goToVendorBatterySettings() {
        String manufacturer = android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        Intent intent = null;
        try {
            if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                // 华为：受保护应用 / 应用启动管理
                intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            } else if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                // 小米：自启动管理
                intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            } else if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
                // OPPO/Realme：自启动管理
                intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.privacypermissionsentry.PermissionTopActivity"));
            } else if (manufacturer.contains("vivo")) {
                // vivo：后台高耗电
                intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            } else if (manufacturer.contains("meizu")) {
                // 魅族：自启动管理
                intent = new Intent("com.meizu.safe.security.SHOW_APPSEC");
                intent.putExtra("packageName", getPackageName());
            } else if (manufacturer.contains("samsung")) {
                // 三星：设备维护 > 电池
                intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"));
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this,
                        "请在此页面找到本应用，\n开启\"允许后台运行\"或\"自启动\"",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            // 厂商页面跳转失败（ROM 版本差异），降级到系统电池设置
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {}
        }
    }
}
