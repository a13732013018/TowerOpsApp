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
        // 回到前台时重新注册回调，保证 UI 刷新
        if (serviceBound && monitorService != null) {
            monitorService.setCallback(serviceCallback);
            syncButtonState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台时解除回调，避免内存泄漏（服务继续跑，只是不刷 UI）
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
        tvUserInfo = findViewById(R.id.tvUserInfo);
        tvProgress = findViewById(R.id.tvProgress);
        tvNextRun  = findViewById(R.id.tvNextRun);
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
