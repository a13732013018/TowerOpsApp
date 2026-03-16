package com.towerops.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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

/**
 * 登录界面 Activity —— 含图形验证码图片显示
 */
public class LoginActivity extends AppCompatActivity {

    private Spinner   spinnerAccount;
    private EditText  etVerifyCode;
    private EditText  etPin;
    private ImageView ivCaptcha;       // 验证码图片
    private Button    btnRefreshCaptcha;
    private Button    btnGetSms;
    private Button    btnLogin;
    private TextView  tvStatus;

    private static final String CAPTCHA_URL =
            "http://ywapp.chinatowercom.cn:58090/itower/mobile/app/verifyImg";

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        spinnerAccount    = findViewById(R.id.spinnerAccount);
        etVerifyCode      = findViewById(R.id.etVerifyCode);
        etPin             = findViewById(R.id.etPin);
        ivCaptcha         = findViewById(R.id.ivCaptcha);
        btnRefreshCaptcha = findViewById(R.id.btnRefreshCaptcha);
        btnGetSms         = findViewById(R.id.btnGetSms);
        btnLogin          = findViewById(R.id.btnLogin);
        tvStatus          = findViewById(R.id.tvLoginStatus);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, AccountConfig.getDisplayNames());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAccount.setAdapter(adapter);

        // 点击验证码图片或刷新按钮，重新加载验证码
        ivCaptcha.setOnClickListener(v -> loadCaptcha());
        btnRefreshCaptcha.setOnClickListener(v -> loadCaptcha());

        btnGetSms.setOnClickListener(v -> doGetSms());
        btnLogin.setOnClickListener(v -> doLogin());

        // 启动时自动加载验证码
        loadCaptcha();
    }

    /** 从服务器加载图形验证码图片（同时获取 Cookie） */
    private void loadCaptcha() {
        ivCaptcha.setImageResource(android.R.drawable.ic_menu_rotate); // loading占位
        tvStatus.setText("正在加载验证码...");

        executor.execute(() -> {
            // 带随机参数防缓存
            String url = CAPTCHA_URL + "?" + Math.random();
            byte[] imgBytes = HttpUtil.getBytes(url);
            if (imgBytes != null && imgBytes.length > 0) {
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                runOnUiThread(() -> {
                    if (bmp != null) {
                        ivCaptcha.setImageBitmap(bmp);
                        tvStatus.setText("请输入图中验证码");
                    } else {
                        ivCaptcha.setImageResource(android.R.drawable.ic_dialog_alert);
                        tvStatus.setText("验证码加载失败，请点击刷新");
                    }
                });
            } else {
                runOnUiThread(() -> {
                    ivCaptcha.setImageResource(android.R.drawable.ic_dialog_alert);
                    tvStatus.setText("网络连接失败，请检查网络后点击刷新");
                });
            }
        });
    }

    private void doGetSms() {
        String verifyCode = etVerifyCode.getText().toString().trim();
        if (verifyCode.isEmpty()) {
            Toast.makeText(this, "请先输入图形验证码", Toast.LENGTH_SHORT).show();
            return;
        }

        btnGetSms.setEnabled(false);
        tvStatus.setText("正在获取短信验证码...");

        int idx = spinnerAccount.getSelectedItemPosition();
        String[] acc = AccountConfig.ACCOUNTS[idx];
        String account  = acc[0];
        String password = acc[1];
        String cookie   = CookieStore.getCookie();

        executor.execute(() -> {
            LoginApi.SmsResult r = LoginApi.sendSmsCode(account, password, verifyCode, cookie);
            runOnUiThread(() -> {
                tvStatus.setText(r.message);
                btnGetSms.setEnabled(true);
                if (r.success) {
                    Toast.makeText(this, "短信验证码已发送", Toast.LENGTH_SHORT).show();
                } else {
                    // 发送失败可能是验证码错误，刷新验证码
                    loadCaptcha();
                }
            });
        });
    }

    private void doLogin() {
        String verifyCode = etVerifyCode.getText().toString().trim();
        String pin        = etPin.getText().toString().trim();

        if (verifyCode.isEmpty()) {
            Toast.makeText(this, "请输入图形验证码", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pin.isEmpty()) {
            Toast.makeText(this, "请输入短信验证码/PIN码", Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false);
        tvStatus.setText("正在登录...");

        int idx = spinnerAccount.getSelectedItemPosition();
        String[] acc = AccountConfig.ACCOUNTS[idx];
        String account  = acc[0];
        String password = acc[1];

        executor.execute(() -> {
            LoginApi.LoginResult r = LoginApi.loginWithPin(account, password, verifyCode, pin);
            runOnUiThread(() -> {
                btnLogin.setEnabled(true);
                if (r.success) {
                    Session s     = Session.get();
                    s.userid      = r.userid;
                    s.token       = r.token;
                    s.mobilephone = r.mobilephone;
                    s.username    = r.username;
                    s.authHeader  = "Authorization: " + r.token + "\n"
                            + "equiptoken: \n"
                            + "appVer: 202112\n"
                            + "Content-Type: application/x-www-form-urlencoded\n"
                            + "User-Agent: okhttp/4.10.0\n"
                            + "Connection: Keep-Alive";
                    // ★ 持久化登录凭据：服务被系统重建(START_STICKY)后进程重启，
                    //   内存变量清空，必须从 SharedPreferences 恢复 token，
                    //   否则后台接单时 Authorization 头为空，服务器鉴权失败 ★
                    s.saveLogin(this);

                    Toast.makeText(this, "登录成功！" + r.username, Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                } else {
                    tvStatus.setText(r.message);
                    Toast.makeText(this, "登录失败：" + r.message, Toast.LENGTH_LONG).show();
                    // 登录失败刷新验证码
                    loadCaptcha();
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
