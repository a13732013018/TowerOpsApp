package com.towerops.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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

    // 防抖 Handler
    private final Handler  debounceHandler  = new Handler(Looper.getMainLooper());
    private       Runnable debounceRunnable;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MonitorService.LocalBinder lb = (MonitorService.LocalBinder) service;
            monitorService = lb.getService();
            serviceBound   = true;
            monitorService.setCallback(serviceCallback, false);
            syncButtonState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound   = false;
            monitorService = null;
        }
    };

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
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvProgress.setText("本轮完成 " + done + "/" + total + " 条  " + time);
        }

        @Override
        public void onError(String msg) {
            tvProgress.setText("错误：" + msg);
        }

        @Override
        public void onNextRun(int delaySec) {
            tvNextRun.setText("下次：" + delaySec + "秒后");
        }
    };

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

        // 启动时检测电池优化，未关闭则引导用户授权
        checkBatteryOptimization();

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
            monitorService.setCallback(serviceCallback, false);
            syncButtonState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (serviceBound && monitorService != null) {
            monitorService.setCallback(serviceCallback, true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        debounceHandler.removeCallbacks(debounceRunnable);
        if (serviceBound) {
            unbindService(conn);
            serviceBound = false;
        }
    }

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
        monitorService.setIntervalAndReschedule(
                parseInt(etIntMin.getText().toString(), 90),
                parseInt(etIntMax.getText().toString(), 120));
        monitorService.startMonitor();
        syncButtonState();
        tvProgress.setText("监控已启动");
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
        if (serviceBound && monitorService != null) {
            monitorService.stopMonitor();
        }
        Session s = Session.get();
        s.token       = "";
        s.userid      = "";
        s.mobilephone = "";
        s.username    = "";
        s.realname    = "";
        s.appConfig   = "";
        getApplicationContext()
            .getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply();
        stopService(new Intent(this, MonitorService.class));
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /** 检测电池优化，引导用户关闭，解决国产 ROM 后台杀进程问题 */
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        String pkg = getPackageName();
        if (pm.isIgnoringBatteryOptimizations(pkg)) return;

        new AlertDialog.Builder(this)
                .setTitle("需要关闭电池优化")
                .setMessage("检测到本应用受电池优化限制。\n\n"
                        + "息屏或切到后台后，系统可能会限制网络访问，导致后台自动接单失败。\n\n"
                        + "请在接下来的系统设置页中，将本应用设为「不限制」或「允许后台活动」，"
                        + "确保后台稳定运行。")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + pkg));
                        startActivity(intent);
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Exception ignored) {}
                    }
                })
                .setNegativeButton("暂不设置", null)
                .show();
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

    private void setupConfigWatchers() {
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

    private void applyConfigNow() {
        buildConfig();
        if (serviceBound && monitorService != null) {
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

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static String defaultIfEmpty(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }
}
