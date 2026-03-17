package com.towerops.app.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.view.animation.OvershootInterpolator;
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
        flCaptcha.setOnClickListener(v -> {
            // 新拟态点击动画
            animateButtonPress(flCaptcha);
            loadCaptcha();
        });

        // 点击刷新按钮，重新加载验证码
        btnRefreshCaptcha.setOnClickListener(v -> {
            animateButtonPress(btnRefreshCaptcha);
            loadCaptcha();
        });

        btnGetSms.setOnClickListener(v -> {
            animateButtonPress(btnGetSms);
            doGetSms();
        });

        btnLogin.setOnClickListener(v -> {
            animateButtonPress(btnLogin);
            doLogin();
        });

        // 启动时自动加载验证码
        loadCaptcha();
    }

    /** 新拟态点击动画 */
    private void animateButtonPress(View view) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f, 1f);
        
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.setDuration(150);
        animatorSet.setInterpolator(new OvershootInterpolator(1.2f));
        animatorSet.playTogether(scaleX, scaleY);
        animatorSet.start();
    }

    private void loadCaptcha() {
        executor.execute(() -> {
            try {
                HttpUtil.GET(
                        CAPTCHA_URL,
                        new HttpUtil.Callback() {
                            @Override
                            public void onSuccess(String response) {
                                // 图片以二进制返回，不在这里处理
                            }

                            @Override
                            public void onSuccessBitmap(Bitmap bitmap) {
                                runOnUiThread(() -> {
                                    ivCaptcha.setImageBitmap(bitmap);
                                    tvStatus.setText("");
                                });
                            }

                            @Override
                            public void onFailure(String error) {
                                runOnUiThread(() -> {
                                    tvStatus.setText("加载验证码失败: " + error);
                                });
                            }
                        });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void doGetSms() {
        String phone = spinnerAccount.getSelectedItem().toString();
        if (phone == null || phone.isEmpty()) {
            tvStatus.setText("请选择账号");
            return;
        }

        // TODO: 实现获取短信验证码逻辑
        tvStatus.setText("短信验证码功能开发中...");
    }

    private void doLogin() {
        String phone   = spinnerAccount.getSelectedItem().toString();
        String vcode   = etVerifyCode.getText().toString().trim();
        String pin     = etPin.getText().toString().trim();

        if (phone == null || phone.isEmpty()) {
            tvStatus.setText("请选择账号");
            return;
        }

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
                AccountConfig selected = AccountConfig.getByDisplayName(phone);
                if (selected == null) {
                    runOnUiThread(() -> tvStatus.setText("账号配置错误"));
                    return;
                }

                LoginApi.login(
                        selected.getUsername(),
                        selected.getPassword(),
                        vcode,
                        pin,
                        new LoginApi.LoginCallback() {
                            @Override
                            public void onSuccess(Session session) {
                                runOnUiThread(() -> {
                                    tvStatus.setText("登录成功");
                                    tvStatus.setTextColor(getResources().getColor(R.color.success_neu));

                                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                                    intent.putExtra("session", session);
                                    startActivity(intent);
                                    finish();
                                });
                            }

                            @Override
                            public void onFailure(String error) {
                                runOnUiThread(() -> {
                                    tvStatus.setText(error);
                                    tvStatus.setTextColor(getResources().getColor(R.color.error_neu));
                                    loadCaptcha(); // 登录失败时刷新验证码
                                });
                            }
                        });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvStatus.setText("登录异常: " + e.getMessage());
                    tvStatus.setTextColor(getResources().getColor(R.color.error_neu));
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
