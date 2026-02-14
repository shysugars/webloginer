package com.example.helloworld;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import rikka.shizuku.Shizuku;
import rikka.shizuku.Shizuku.UserServiceArgs;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 100;
    private static final String PREFS_NAME = "AppPrefs";
    private static final String PREF_KEY = "key";
    private static final String PREF_URL = "url";
    private static final String PREF_PACKAGES = "packages";
    private static final String PREF_SWITCH_STATE = "switch_state";

    private EditText etKey, etServerUrl, etPackages;
    private MaterialSwitch switchControl;
    private Button btnSave, btnReconnect;
    private TextView tvStatus, tvShizukuStatus;
    private MaterialCardView cardStatus;

    private IUserService userService;
    private boolean isServiceBound = false;

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        updateShizukuStatus("Shizuku 权限已授予");
                        bindUserService();
                    } else {
                        updateShizukuStatus("Shizuku 权限被拒绝");
                    }
                }
            };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        Log.d(TAG, "Shizuku binder received");
        runOnUiThread(() -> checkAndRequestPermission());
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        Log.d(TAG, "Shizuku binder dead");
        runOnUiThread(() -> {
            updateShizukuStatus("Shizuku 服务已断开");
            isServiceBound = false;
            userService = null;
        });
    };

    private final ServiceConnection serviceConnection = new ServiceConnection();

    private class ServiceConnection implements Shizuku.UserServiceArgs.Callback,
            android.content.ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "UserService connected");
            userService = IUserService.Stub.asInterface(service);
            isServiceBound = true;
            runOnUiThread(() -> {
                updateShizukuStatus("UserService 已连接");
                connectWebSocket();
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "UserService disconnected");
            userService = null;
            isServiceBound = false;
            runOnUiThread(() -> updateShizukuStatus("UserService 已断开"));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadPreferences();
        setupListeners();

        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
    }

    private void initViews() {
        etKey = findViewById(R.id.et_key);
        etServerUrl = findViewById(R.id.et_server_url);
        etPackages = findViewById(R.id.et_packages);
        switchControl = findViewById(R.id.switch_control);
        btnSave = findViewById(R.id.btn_save);
        btnReconnect = findViewById(R.id.btn_reconnect);
        tvStatus = findViewById(R.id.tv_status);
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        cardStatus = findViewById(R.id.card_status);
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etKey.setText(prefs.getString(PREF_KEY, ""));
        etServerUrl.setText(prefs.getString(PREF_URL, "ws://192.168.1.100:8080"));
        etPackages.setText(prefs.getString(PREF_PACKAGES, ""));
        switchControl.setChecked(prefs.getBoolean(PREF_SWITCH_STATE, true));
    }

    private void savePreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_KEY, etKey.getText().toString().trim())
                .putString(PREF_URL, etServerUrl.getText().toString().trim())
                .putString(PREF_PACKAGES, etPackages.getText().toString().trim())
                .apply();
    }

    private void setupListeners() {
        btnSave.setOnClickListener(v -> {
            savePreferences();
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            if (isServiceBound && userService != null) {
                connectWebSocket();
            }
        });

        btnReconnect.setOnClickListener(v -> {
            if (isServiceBound && userService != null) {
                connectWebSocket();
            } else {
                checkAndRequestPermission();
            }
        });

        switchControl.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(PREF_SWITCH_STATE, isChecked).apply();

            if (isServiceBound && userService != null) {
                try {
                    String key = etKey.getText().toString().trim();
                    if (isChecked) {
                        userService.executeAction("start", key);
                        updateStatus("已执行: 恢复应用");
                    } else {
                        userService.executeAction("stop", key);
                        updateStatus("已执行: 停止应用");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error executing action", e);
                    updateStatus("执行失败: " + e.getMessage());
                }
            } else {
                updateStatus("UserService 未连接");
            }
        });
    }

    private void checkAndRequestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                updateShizukuStatus("Shizuku 未运行");
                return;
            }

            if (Shizuku.isPreV11()) {
                updateShizukuStatus("Shizuku 版本过低");
                return;
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                updateShizukuStatus("Shizuku 权限已授予");
                bindUserService();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                updateShizukuStatus("Shizuku 权限已被永久拒绝");
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
                updateShizukuStatus("正在请求 Shizuku 权限...");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking Shizuku permission", e);
            updateShizukuStatus("Shizuku 检查失败: " + e.getMessage());
        }
    }

    private void bindUserService() {
        if (isServiceBound) {
            Log.d(TAG, "Service already bound");
            return;
        }

        try {
            Shizuku.bindUserService(
                    new UserServiceArgs(new ComponentName(
                            getPackageName(),
                            UserService.class.getName()))
                            .daemon(true)
                            .processNameSuffix("user_service")
                            .debuggable(BuildConfig.DEBUG)
                            .version(BuildConfig.VERSION_CODE),
                    serviceConnection
            );
            updateShizukuStatus("正在绑定 UserService...");
        } catch (Exception e) {
            Log.e(TAG, "Error binding user service", e);
            updateShizukuStatus("绑定 UserService 失败: " + e.getMessage());
        }
    }

    private void connectWebSocket() {
        String url = etServerUrl.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        String packages = etPackages.getText().toString().trim();

        if (url.isEmpty()) {
            updateStatus("请输入服务器地址");
            return;
        }

        if (key.isEmpty()) {
            updateStatus("请输入 Key");
            return;
        }

        try {
            userService.connect(url, key, packages);
            updateStatus("WebSocket 正在连接...");
        } catch (Exception e) {
            Log.e(TAG, "Error connecting WebSocket", e);
            updateStatus("连接失败: " + e.getMessage());
        }
    }

    private void updateStatus(String status) {
        tvStatus.setText(status);
        Log.d(TAG, "Status: " + status);
    }

    private void updateShizukuStatus(String status) {
        tvShizukuStatus.setText(status);
        Log.d(TAG, "Shizuku Status: " + status);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        // 不断开 UserService，让它在后台保持运行
    }
}