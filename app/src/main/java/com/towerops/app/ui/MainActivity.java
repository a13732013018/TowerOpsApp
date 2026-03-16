package com.towerops.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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

public class MainActivity extends AppCompatActivity {

    // UI 控件
    private TextView        tvUserInfo, tvProgress, tvNextRun;
    private TextView        btnSortBillTime, btnSortFeedbackTime,
                            btnSortAlertTime, btnSortAlertStatus, tvSortDesc;
    private CheckBox        cbFeedback, cbAccept, cbRevert;
    private EditText        etFbMin, etFbMax, etAccMin, etAccMax, etIntMin, etIntMax;
    private Button          btnStart, btnStop;
    private WorkOrderAdapter adapter;

    // 服务绑定
    private MonitorService  monitorService;
    private boolean         serviceBound = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MonitorService.LocalBinder binder = (MonitorService.LocalBinder) service;
            monitorService = binder.getService();
            serviceBound   = true;
            // 注册 UI 回调
            monitorService.setCallback(serviceCallback);
            // 同步按钮状态（服务可能已在运行）
            syncButtonState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            monitorService = null;
        }
    };

    // 服务回调 → 刷新 UI
    private final MonitorService.ServiceCallback serviceCallback = new MonitorService.ServiceCallback() {
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
            syncConfigFromSession();
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

    // ─────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupRecycler();
        setupSortButtons();
        updateUserInfo();

        btnStart.setOnClickListener(v -> startMonitor());
        btnStop.setOnClickListener(v  -> stopMonitor());

        // 启动并绑定前台服务
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
            // 重新注册回调（setCallback内部会主动推送一次倒计时给UI）
            monitorService.setCallback(serviceCallback);
            syncButtonState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ★ 退到后台只解除UI回调引用，服务本身继续全速运行 ★
        // ★ 不再置null——改为传入一个"静默回调"，避免callback==null时UI恢复慢 ★
        if (serviceBound && monitorService != null) {
            monitorService.setCallback(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(conn);
            serviceBound = false;
        }
        // 注意：不 stopService，让服务继续在后台跑
    }

    // ─────────────────────────────────────────────
    // 开始 / 停止
    // ─────────────────────────────────────────────

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
        monitorService.setInterval(
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

    // ─────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────

    private void bindViews() {
        tvUserInfo         = findViewById(R.id.tvUserInfo);
        tvProgress         = findViewById(R.id.tvProgress);
        tvNextRun          = findViewById(R.id.tvNextRun);
        btnSortBillTime    = findViewById(R.id.btnSortBillTime);
        btnSortFeedbackTime= findViewById(R.id.btnSortFeedbackTime);
        btnSortAlertTime   = findViewById(R.id.btnSortAlertTime);
        btnSortAlertStatus = findViewById(R.id.btnSortAlertStatus);
        tvSortDesc         = findViewById(R.id.tvSortDesc);
        cbFeedback = findViewById(R.id.cbAutoFeedback);
        cbAccept   = findViewById(R.id.cbAutoAccept);
        cbRevert   = findViewById(R.id.cbAutoRevert);
        etFbMin    = findViewById(R.id.etFeedbackMin);
        etFbMax    = findViewById(R.id.etFeedbackMax);
        etAccMin   = findViewById(R.id.etAcceptMin);
        etAccMax   = findViewById(R.id.etAcceptMax);
        etIntMin   = findViewById(R.id.etIntervalMin);
        etIntMax   = findViewById(R.id.etIntervalMax);
        btnStart   = findViewById(R.id.btnStartMonitor);
        btnStop    = findViewById(R.id.btnStopMonitor);
    }

    private void setupRecycler() {
        RecyclerView rv = findViewById(R.id.recyclerOrders);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new WorkOrderAdapter();
        rv.setAdapter(adapter);
    }

    private void setupSortButtons() {
        // 工单历时排序（点击在 大→小 / 小→大 之间切换）
        btnSortBillTime.setOnClickListener(v -> {
            WorkOrderAdapter.SortMode cur = adapter.getSortMode();
            if (cur == WorkOrderAdapter.SortMode.BILL_TIME_DESC) {
                adapter.setSortMode(WorkOrderAdapter.SortMode.BILL_TIME_ASC);
            } else {
                adapter.setSortMode(WorkOrderAdapter.SortMode.BILL_TIME_DESC);
            }
            updateSortUI();
        });

        // 反馈历时排序（点击在 大→小 / 小→大 之间切换）
        btnSortFeedbackTime.setOnClickListener(v -> {
            WorkOrderAdapter.SortMode cur = adapter.getSortMode();
            if (cur == WorkOrderAdapter.SortMode.FEEDBACK_TIME_DESC) {
                adapter.setSortMode(WorkOrderAdapter.SortMode.FEEDBACK_TIME_ASC);
            } else {
                adapter.setSortMode(WorkOrderAdapter.SortMode.FEEDBACK_TIME_DESC);
            }
            updateSortUI();
        });

        // 告警时间排序（点击在 最新→最旧 / 最旧→最新 之间切换）
        btnSortAlertTime.setOnClickListener(v -> {
            WorkOrderAdapter.SortMode cur = adapter.getSortMode();
            if (cur == WorkOrderAdapter.SortMode.ALERT_TIME_DESC) {
                adapter.setSortMode(WorkOrderAdapter.SortMode.ALERT_TIME_ASC);
            } else {
                adapter.setSortMode(WorkOrderAdapter.SortMode.ALERT_TIME_DESC);
            }
            updateSortUI();
        });

        // 告警状态排序（点击在 告警中优先 / 已恢复优先 之间切换）
        btnSortAlertStatus.setOnClickListener(v -> {
            WorkOrderAdapter.SortMode cur = adapter.getSortMode();
            if (cur == WorkOrderAdapter.SortMode.ALERT_STATUS_ALARM) {
                adapter.setSortMode(WorkOrderAdapter.SortMode.ALERT_STATUS_RECOVER);
            } else {
                adapter.setSortMode(WorkOrderAdapter.SortMode.ALERT_STATUS_ALARM);
            }
            updateSortUI();
        });

        // 初始化排序 UI 显示
        updateSortUI();
    }

    /** 根据当前排序模式更新按钮高亮和说明文字 */
    private void updateSortUI() {
        WorkOrderAdapter.SortMode mode = adapter.getSortMode();
        // 全部重置为暗色
        btnSortBillTime.setTextColor(0xffa0a0c0);
        btnSortFeedbackTime.setTextColor(0xffa0a0c0);
        btnSortAlertTime.setTextColor(0xffa0a0c0);
        btnSortAlertStatus.setTextColor(0xffa0a0c0);
        // 重置箭头（工单历时、告警时间、告警状态都支持双向切换，统一用 ↕）
        btnSortBillTime.setText("工单历时 ↕");
        btnSortFeedbackTime.setText("反馈历时 ↕");
        btnSortAlertTime.setText("告警时间 ↕");
        btnSortAlertStatus.setText("告警状态 ↕");

        switch (mode) {
            case BILL_TIME_DESC:
                btnSortBillTime.setText("工单历时 ↓");
                btnSortBillTime.setTextColor(0xffffffff);
                tvSortDesc.setText("工单历时 大→小");
                break;
            case BILL_TIME_ASC:
                btnSortBillTime.setText("工单历时 ↑");
                btnSortBillTime.setTextColor(0xffffffff);
                tvSortDesc.setText("工单历时 小→大");
                break;
            case FEEDBACK_TIME_DESC:
                btnSortFeedbackTime.setText("反馈历时 ↓");
                btnSortFeedbackTime.setTextColor(0xffffffff);
                tvSortDesc.setText("反馈历时 大→小");
                break;
            case FEEDBACK_TIME_ASC:
                btnSortFeedbackTime.setText("反馈历时 ↑");
                btnSortFeedbackTime.setTextColor(0xffffffff);
                tvSortDesc.setText("反馈历时 小→大");
                break;
            case ALERT_TIME_DESC:
                btnSortAlertTime.setText("告警时间 ↓");
                btnSortAlertTime.setTextColor(0xffffffff);
                tvSortDesc.setText("告警时间 最新→最旧");
                break;
            case ALERT_TIME_ASC:
                btnSortAlertTime.setText("告警时间 ↑");
                btnSortAlertTime.setTextColor(0xffffffff);
                tvSortDesc.setText("告警时间 最旧→最新");
                break;
            case ALERT_STATUS_ALARM:
                btnSortAlertStatus.setText("告警中优先 ↓");
                btnSortAlertStatus.setTextColor(0xffff6b35);
                tvSortDesc.setText("告警中优先");
                break;
            case ALERT_STATUS_RECOVER:
                btnSortAlertStatus.setText("已恢复优先 ↓");
                btnSortAlertStatus.setTextColor(0xff40c080);
                tvSortDesc.setText("已恢复优先");
                break;
        }
    }

    private void updateUserInfo() {
        Session s = Session.get();
        tvUserInfo.setText(s.username.isEmpty() ? "未登录" : s.username + " | " + s.userid);
    }

    private void buildConfig() {
        Session s = Session.get();
        String fb    = cbFeedback.isChecked() ? "true" : "false";
        String acc   = cbAccept.isChecked()   ? "true" : "false";
        String rev   = cbRevert.isChecked()   ? "true" : "false";
        String fbMinStr  = etFbMin.getText().toString().trim();
        String fbMaxStr  = etFbMax.getText().toString().trim();
        String accMinStr = etAccMin.getText().toString().trim();
        String accMaxStr = etAccMax.getText().toString().trim();
        if (fbMinStr.isEmpty())  fbMinStr  = "70";
        if (fbMaxStr.isEmpty())  fbMaxStr  = "90";
        if (accMinStr.isEmpty()) accMinStr = "60";
        if (accMaxStr.isEmpty()) accMaxStr = "90";
        s.appConfig = fb + "\u0001" + acc + "\u0001" + rev + "\u0001"
                + fbMinStr + "|" + fbMaxStr + "\u0001"
                + accMinStr + "|" + accMaxStr;
    }

    private void syncConfigFromSession() {
        Session s = Session.get();
        if (s.appConfig.isEmpty()) return;
        String[] parts = s.appConfig.split("\u0001", -1);
        if (parts.length >= 2) {
            cbFeedback.setChecked("true".equalsIgnoreCase(parts[0]));
            cbAccept.setChecked("true".equalsIgnoreCase(parts[1]));
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
