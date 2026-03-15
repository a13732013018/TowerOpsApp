package com.towerops.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.towerops.app.worker.MonitorTask;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView        tvUserInfo, tvProgress, tvNextRun;
    private CheckBox        cbFeedback, cbAccept, cbRevert;
    private EditText        etFbMin, etFbMax, etAccMin, etAccMax, etIntMin, etIntMax;
    private Button          btnStart, btnStop;
    private WorkOrderAdapter adapter;

    private volatile boolean monitoring = false;
    private final Handler    mainHandler  = new Handler(Looper.getMainLooper());
    private final ExecutorService pool    = Executors.newCachedThreadPool();
    private Runnable         scheduleRunnable;
    private final Random     random       = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupRecycler();
        updateUserInfo();

        btnStart.setOnClickListener(v -> startMonitor());
        btnStop.setOnClickListener(v  -> stopMonitor());
    }

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

    private void startMonitor() {
        if (monitoring) { Toast.makeText(this, "监控已在运行", Toast.LENGTH_SHORT).show(); return; }
        monitoring = true;
        btnStart.setText("监控中...");
        btnStart.setEnabled(false);
        tvProgress.setText("监控已启动");
        runOnce();
    }

    private void stopMonitor() {
        monitoring = false;
        if (scheduleRunnable != null) mainHandler.removeCallbacks(scheduleRunnable);
        btnStart.setText("开始监控");
        btnStart.setEnabled(true);
        tvProgress.setText("已停止");
        tvNextRun.setText("");
    }

    private void runOnce() {
        if (!monitoring) return;
        buildConfig();
        tvProgress.setText("拉取工单中...");

        pool.execute(new MonitorTask(new MonitorTask.MonitorCallback() {
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
            public void onAllDone() {
                syncConfigFromSession();
                int done = Session.get().getFinished();
                int total = Session.get().getTotal();
                tvProgress.setText("本轮完成：" + done + "/" + total + " 条");
                if (monitoring) scheduleNext();
            }

            @Override
            public void onError(String msg) {
                tvProgress.setText("错误：" + msg);
                if (monitoring) scheduleNext();
            }
        }));
    }

    private void scheduleNext() {
        int minSec = parseInt(etIntMin.getText().toString(), 30);
        int maxSec = parseInt(etIntMax.getText().toString(), 60);
        int delaySec = minSec + random.nextInt(Math.max(1, maxSec - minSec + 1));

        tvNextRun.setText("下次：" + delaySec + "秒后");

        scheduleRunnable = () -> {
            tvNextRun.setText("");
            runOnce();
        };
        mainHandler.postDelayed(scheduleRunnable, delaySec * 1000L);
    }

    private void buildConfig() {
        Session s = Session.get();
        String fb    = cbFeedback.isChecked() ? "true" : "false";
        String acc   = cbAccept.isChecked()   ? "true" : "false";
        String rev   = cbRevert.isChecked()   ? "true" : "false";
        // 如果输入框为空，使用默认值（反馈：30~60分钟；接单：3~8分钟）
        String fbMinStr  = etFbMin.getText().toString().trim();
        String fbMaxStr  = etFbMax.getText().toString().trim();
        String accMinStr = etAccMin.getText().toString().trim();
        String accMaxStr = etAccMax.getText().toString().trim();
        if (fbMinStr.isEmpty())  fbMinStr  = "30";
        if (fbMaxStr.isEmpty())  fbMaxStr  = "60";
        if (accMinStr.isEmpty()) accMinStr = "3";
        if (accMaxStr.isEmpty()) accMaxStr = "8";
        String range1 = fbMinStr  + "|" + fbMaxStr;
        String range2 = accMinStr + "|" + accMaxStr;
        s.appConfig = fb + "\u0001" + acc + "\u0001" + rev + "\u0001" + range1 + "\u0001" + range2;
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        monitoring = false;
        if (scheduleRunnable != null) mainHandler.removeCallbacks(scheduleRunnable);
        pool.shutdownNow();
    }
}
