package com.towerops.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.towerops.app.R;
import com.towerops.app.api.LoginApi;
import com.towerops.app.model.AccountConfig;
import com.towerops.app.model.Session;
import com.towerops.app.util.CookieStore;
import com.towerops.app.util.HttpUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private Spinner   spinnerAccount;
    private EditText  etVerifyCode;
    private EditText  etPin;
    private ImageView ivCaptcha;       // 验证码图片
    private Button    btnRefreshCaptcha;
    private Button    btnGetSms;
    private Button    btnLogin;
    private TextView  tvStatus;
    private FrameLayout flCaptcha;      // 验证码容器

    private static final String CAPTCHA_URL =
            "http://ywapp.chinatowercom.cn:58090/itower/mobile/app/verifyImg";

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private String cookie = "";  // 保存Cookie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 隐藏标题栏
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().hide();
            }
        } catch (Exception e) {
            // 忽略
        }

        // 检查是否已登录
        Session session = Session.get();
        session.loadConfig(this);

        if (!session.token.isEmpty() && !session.userid.isEmpty()) {
            // 已登录,直接跳转到主界面
            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        // 初始化控件
        flCaptcha         = findViewById(R.id.flCaptcha);
        ivCaptcha         = findViewById(R.id.ivCaptcha);
        spinnerAccount    = findViewById(R.id.spinnerAccount);
        etVerifyCode      = findViewById(R.id.etVerifyCode);
        etPin             = findViewById(R.id.etPin);
        btnRefreshCaptcha = findViewById(R.id.btnRefreshCaptcha);
        btnGetSms         = findViewById(R.id.btnGetSms);
        btnLogin          = findViewById(R.id.btnLogin);
        tvStatus          = findViewById(R.id.tvStatus);

        // 设置账号下拉框
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, AccountConfig.getDisplayNames());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAccount.setAdapter(adapter);

        // 点击验证码容器，重新加载验证码
        flCaptcha.setOnClickListener(v -> loadCaptcha());

        // 点击刷新按钮，重新加载验证码
        btnRefreshCaptcha.setOnClickListener(v -> loadCaptcha());

        btnGetSms.setOnClickListener(v -> doGetSms());

        btnLogin.setOnClickListener(v -> doLogin());

        // 启动时自动加载验证码
        loadCaptcha();
    }

    private void loadCaptcha() {
        executor.execute(() -> {
            try {
                byte[] bytes = HttpUtil.getBytes(CAPTCHA_URL);
                if (bytes != null && bytes.length > 0) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    cookie = CookieStore.getCookie();
                    runOnUiThread(() -> {
                        ivCaptcha.setImageBitmap(bitmap);
                        tvStatus.setText("");
                    });
                } else {
                    runOnUiThread(() -> {
                        tvStatus.setText("加载验证码失败");
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvStatus.setText("加载验证码异常: " + e.getMessage());
                });
            }
        });
    }

    private void doGetSms() {
        int position = spinnerAccount.getSelectedItemPosition();
        if (position < 0 || position >= AccountConfig.ACCOUNTS.length) {
            tvStatus.setText("请选择账号");
            return;
        }

        String account = AccountConfig.ACCOUNTS[position][0];
        String password = AccountConfig.ACCOUNTS[position][1];
        String vcode = etVerifyCode.getText().toString().trim();

        if (vcode.isEmpty()) {
            tvStatus.setText("请输入验证码");
            return;
        }

        tvStatus.setText("发送中...");

        executor.execute(() -> {
            try {
                LoginApi.SmsResult result = LoginApi.sendSmsCode(account, password, vcode, cookie);
                runOnUiThread(() -> {
                    if (result.success) {
                        tvStatus.setText(result.message);
                    } else {
                        tvStatus.setText(result.message);
                        loadCaptcha();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvStatus.setText("发送失败: " + e.getMessage());
                });
            }
        });
    }

    private void doLogin() {
        int position = spinnerAccount.getSelectedItemPosition();
        if (position < 0 || position >= AccountConfig.ACCOUNTS.length) {
            tvStatus.setText("请选择账号");
            return;
        }

        String account = AccountConfig.ACCOUNTS[position][0];
        String password = AccountConfig.ACCOUNTS[position][1];
        String vcode = etVerifyCode.getText().toString().trim();
        String pin = etPin.getText().toString().trim();

        if (vcode.isEmpty()) {
            tvStatus.setText("请输入验证码");
            return;
        }

        if (pin.isEmpty()) {
            tvStatus.setText("请输入PIN码");
            return;
        }

        tvStatus.setText("登录中...");

        executor.execute(() -> {
            try {
                LoginApi.LoginResult result = LoginApi.loginWithPin(account, password, vcode, pin);
                runOnUiThread(() -> {
                    if (result.success) {
                        // 保存登录信息
                        Session s = Session.get();
                        s.token = result.token;
                        s.userid = result.userid;
                        s.mobilephone = result.mobilephone;
                        s.username = result.username;
                        s.realname = AccountConfig.getRealname(position);
                        s.saveLogin(LoginActivity.this);  // 使用saveLogin而不是saveConfig

                        tvStatus.setText("登录成功");
                        tvStatus.setTextColor(getResources().getColor(R.color.success_neu));

                        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    } else {
                        tvStatus.setText(result.message);
                        tvStatus.setTextColor(getResources().getColor(R.color.error_neu));
                        loadCaptcha(); // 登录失败时刷新验证码
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvStatus.setText("登录异常: " + e.getMessage());
                    tvStatus.setTextColor(getResources().getColor(R.color.error_neu));
                    loadCaptcha();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
