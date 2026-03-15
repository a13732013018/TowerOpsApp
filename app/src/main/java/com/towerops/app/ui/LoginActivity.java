package com.towerops.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.towerops.app.R;
import com.towerops.app.api.LoginApi;
import com.towerops.app.model.AccountConfig;
import com.towerops.app.model.Session;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 登录界面 Activity
 */
public class LoginActivity extends AppCompatActivity {

    private Spinner  spinnerAccount;
    private EditText etVerifyCode;
    private EditText etPin;
    private Button   btnGetSms;
    private Button   btnLogin;
    private TextView tvStatus;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        spinnerAccount = findViewById(R.id.spinnerAccount);
        etVerifyCode   = findViewById(R.id.etVerifyCode);
        etPin          = findViewById(R.id.etPin);
        btnGetSms      = findViewById(R.id.btnGetSms);
        btnLogin       = findViewById(R.id.btnLogin);
        tvStatus       = findViewById(R.id.tvLoginStatus);

        // 填充账号下拉框
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, AccountConfig.getDisplayNames());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAccount.setAdapter(adapter);

        btnGetSms.setOnClickListener(v -> doGetSms());
        btnLogin.setOnClickListener(v -> doLogin());
    }

    // ---- 获取短信验证码 ----
    private void doGetSms() {
        btnGetSms.setEnabled(false);
        tvStatus.setText("正在获取验证码...");

        int idx  = spinnerAccount.getSelectedItemPosition();
        String[] acc = AccountConfig.ACCOUNTS[idx];
        String account  = acc[0];
        String password = acc[1];
        String verifyCode = etVerifyCode.getText().toString().trim();

        executor.execute(() -> {
            LoginApi.SmsResult r = LoginApi.sendSmsCode(account, password, verifyCode, "");
            runOnUiThread(() -> {
                tvStatus.setText(r.message);
                btnGetSms.setEnabled(true);
                if (r.success) {
                    Toast.makeText(this, "短信验证码已发送", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ---- PIN 码登录 ----
    private void doLogin() {
        btnLogin.setEnabled(false);
        tvStatus.setText("正在登录...");

        int idx  = spinnerAccount.getSelectedItemPosition();
        String[] acc = AccountConfig.ACCOUNTS[idx];
        String account   = acc[0];
        String password  = acc[1];
        String verifyCode = etVerifyCode.getText().toString().trim();
        String pin        = etPin.getText().toString().trim();

        executor.execute(() -> {
            LoginApi.LoginResult r = LoginApi.loginWithPin(account, password, verifyCode, pin);
            runOnUiThread(() -> {
                btnLogin.setEnabled(true);
                if (r.success) {
                    // 写入全局会话
                    Session s      = Session.get();
                    s.userid       = r.userid;
                    s.token        = r.token;
                    s.mobilephone  = r.mobilephone;
                    s.username     = r.username;
                    // 构建通用协议头（含 token）
                    s.authHeader   = "Authorization: " + r.token + "\n"
                            + "equiptoken: \n"
                            + "appVer: 202112\n"
                            + "Content-Type: application/x-www-form-urlencoded\n"
                            + "User-Agent: okhttp/4.10.0\n"
                            + "Connection: Keep-Alive";

                    Toast.makeText(this, "登录成功！" + r.username, Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                } else {
                    tvStatus.setText(r.message);
                    Toast.makeText(this, "登录失败：" + r.message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
