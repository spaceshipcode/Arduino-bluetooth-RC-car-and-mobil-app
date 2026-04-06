package com.btcar.controller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Set;

/**
 * Ana ekran: Joystick + Gamepad kontrolü, BT bağlantısı, korna.
 *
 * Protokol:
 *   [F/B][DD][L/R][DD]  →  ör: F90R30
 *   V = korna aç, v = korna kapat
 *
 * Mimari: 20 Hz send loop — her 50ms'de mevcut joystick durumunu gönderir.
 * Joystick bırakılınca currentX/currentY sıfırlanır, bir sonraki döngüde dur komutu gider.
 */
public class MainActivity extends AppCompatActivity
        implements BluetoothService.ConnectionListener, JoystickView.JoystickListener {

    private static final int REQ_BT_PERMISSIONS = 1001;

    // UI
    private Button btnConnect;
    private Button btnHorn;
    private View statusDot;
    private TextView tvStatus;
    private TextView tvDeviceName;
    private TextView tvGamepad;
    private TextView tvSpeed;
    private TextView tvTurn;
    private View speedBar;
    private View turnBar;
    private JoystickView joystick;

    // Bluetooth
    private BluetoothAdapter btAdapter;
    private BluetoothService btService;

    // Durum
    private boolean isConnected = false;
    private boolean hornActive = false;

    // =====================================================================
    //  Mevcut joystick durumu — sadece bu iki değişken giriş olaylarında
    //  güncellenir. Send loop bunları okur ve Arduino'ya gönderir.
    // =====================================================================
    private volatile int currentX = 0;  // -100 .. +100
    private volatile int currentY = 0;  // -100 .. +100

    // =====================================================================
    //  20 Hz Send Loop — joystick durumunu sürekli Arduino'ya gönderir
    // =====================================================================
    private static final long SEND_PERIOD_MS = 50; // 20 Hz
    private final Handler sendHandler = new Handler(Looper.getMainLooper());
    private final Runnable sendLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (isConnected) {
                dispatchState();
            }
            sendHandler.postDelayed(this, SEND_PERIOD_MS);
        }
    };

    /**
     * Mevcut currentX / currentY değerini Arduino'ya gönderir.
     * Bu metot send loop tarafından 20 Hz'de çağrılır.
     */
    private void dispatchState() {
        int x = currentX;
        int y = currentY;

        int fwdVal  = Math.min(Math.abs(y), 99);
        int turnVal = Math.min(Math.abs(x), 99);

        char fwdDir  = (y >= 0) ? 'F' : 'B';
        char turnDir = (x >= 0) ? 'R' : 'L';

        String packet = String.format("%c%02d%c%02d", fwdDir, fwdVal, turnDir, turnVal);
        btService.send(packet);

        // UI güncelle (Main thread'deyiz zaten)
        updateSpeedTurnUI(fwdVal, turnVal, y, x);
    }

    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tam ekran
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);

        // View referansları
        btnConnect   = findViewById(R.id.btnConnect);
        btnHorn      = findViewById(R.id.btnHorn);
        statusDot    = findViewById(R.id.statusDot);
        tvStatus     = findViewById(R.id.tvStatus);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvGamepad    = findViewById(R.id.tvGamepad);
        tvSpeed      = findViewById(R.id.tvSpeed);
        tvTurn       = findViewById(R.id.tvTurn);
        speedBar     = findViewById(R.id.speedBar);
        turnBar      = findViewById(R.id.turnBar);
        joystick     = findViewById(R.id.joystick);

        // Bluetooth
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        btService = new BluetoothService();
        btService.setConnectionListener(this);

        // Joystick listener — sadece değerleri günceller, send loop gönderir
        joystick.setJoystickListener(this);

        // Bağlan butonu
        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                btService.disconnect();
            } else {
                checkPermissionsAndShowDevices();
            }
        });

        // Korna butonu — basılı tutma
        btnHorn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    setHorn(true);
                    v.setScaleX(0.9f);
                    v.setScaleY(0.9f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setHorn(false);
                    v.setScaleX(1.0f);
                    v.setScaleY(1.0f);
                    return true;
            }
            return false;
        });

        updateConnectionUI(false, null);

        // Send loop'u başlat
        sendHandler.postDelayed(sendLoopRunnable, SEND_PERIOD_MS);
    }

    // ================================================================
    //  Joystick Callback — sadece state günceller
    // ================================================================

    @Override
    public void onJoystickMoved(int xPercent, int yPercent) {
        // Deadzone
        if (Math.abs(xPercent) < 8) xPercent = 0;
        if (Math.abs(yPercent) < 8) yPercent = 0;

        currentX = xPercent;
        currentY = yPercent;
        // Send loop bir sonraki 50ms döngüsünde bunu gönderir
    }

    // ================================================================
    //  Bluetooth İzinleri
    // ================================================================

    private void checkPermissionsAndShowDevices() {
        if (btAdapter == null) {
            Toast.makeText(this, R.string.bt_not_available, Toast.LENGTH_LONG).show();
            return;
        }
        if (!btAdapter.isEnabled()) {
            Toast.makeText(this, R.string.bt_not_enabled, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                        }, REQ_BT_PERMISSIONS);
                return;
            }
        }
        showDevicePickerDialog();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showDevicePickerDialog();
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ================================================================
    //  Cihaz Seçici Dialog
    // ================================================================

    @SuppressLint("MissingPermission")
    private void showDevicePickerDialog() {
        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<BluetoothDevice> deviceList = new ArrayList<>(pairedDevices);
        String[] deviceNames = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice d = deviceList.get(i);
            String name = d.getName();
            if (name == null || name.isEmpty()) name = d.getAddress();
            deviceNames[i] = name + "\n" + d.getAddress();
        }

        new AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(R.string.select_device)
                .setItems(deviceNames, (dialog, which) -> {
                    BluetoothDevice device = deviceList.get(which);
                    updateConnectionUI(false, null);
                    tvStatus.setText(R.string.connecting);
                    btnConnect.setEnabled(false);
                    btService.connect(device);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ================================================================
    //  Bağlantı Callback'leri
    // ================================================================

    @Override
    public void onConnected(String deviceName) {
        isConnected = true;
        // Bağlanınca sıfır gönder
        currentX = 0;
        currentY = 0;
        updateConnectionUI(true, deviceName);
    }

    @Override
    public void onDisconnected() {
        isConnected = false;
        currentX = 0;
        currentY = 0;
        updateConnectionUI(false, null);
    }

    @Override
    public void onConnectionFailed(String error) {
        isConnected = false;
        updateConnectionUI(false, null);
        Toast.makeText(this, getString(R.string.connection_failed) + ": " + error,
                Toast.LENGTH_LONG).show();
    }

    private void updateConnectionUI(boolean connected, String deviceName) {
        btnConnect.setEnabled(true);
        btnConnect.setText(connected ? R.string.disconnect : R.string.connect);
        tvStatus.setText(connected ? R.string.connected : R.string.disconnected);
        tvStatus.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.accent_green : R.color.text_secondary));
        tvDeviceName.setText(deviceName != null ? deviceName : "");

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ContextCompat.getColor(this,
                connected ? R.color.accent_green : R.color.accent_red));
        dot.setSize(dpToPx(10), dpToPx(10));
        statusDot.setBackground(dot);
    }

    // ================================================================
    //  UI güncelleme
    // ================================================================

    private void updateSpeedTurnUI(int fwdVal, int turnVal, int ySign, int xSign) {
        // Hız göstergesi
        String speedText = (ySign < 0 ? "-" : "") + fwdVal;
        tvSpeed.setText(speedText);
        tvSpeed.setTextColor(ContextCompat.getColor(this,
                ySign < 0 ? R.color.accent_red : R.color.accent_blue));

        // Hız barı
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) speedBar.getLayoutParams();
        View speedParent = (View) speedBar.getParent();
        if (speedParent.getWidth() > 0) {
            sp.width = (int) (speedParent.getWidth() * fwdVal / 99f);
            speedBar.setLayoutParams(sp);
        }
        speedBar.setBackgroundColor(ContextCompat.getColor(this,
                ySign < 0 ? R.color.accent_red : R.color.accent_blue));

        // Dönüş göstergesi
        String turnText = (xSign < 0 ? "◀ " : "") + turnVal + (xSign > 0 ? " ▶" : "");
        tvTurn.setText(turnText);

        // Dönüş barı
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) turnBar.getLayoutParams();
        View turnParent = (View) turnBar.getParent();
        if (turnParent.getWidth() > 0) {
            tp.width = (int) (turnParent.getWidth() * turnVal / 99f * 0.5f);
            tp.leftMargin = (xSign >= 0)
                    ? turnParent.getWidth() / 2
                    : (int) (turnParent.getWidth() / 2 - tp.width);
            turnBar.setLayoutParams(tp);
        }
    }

    // ================================================================
    //  Korna
    // ================================================================

    private void setHorn(boolean on) {
        if (hornActive == on) return;
        hornActive = on;
        btService.send(on ? "V" : "v");
    }

    // ================================================================
    //  Gamepad Desteği — sadece currentX/currentY günceller
    // ================================================================

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isGameController(event.getDevice())) {
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);

            float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
            float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
            if (rt == 0) rt = event.getAxisValue(MotionEvent.AXIS_GAS);
            if (lt == 0) lt = event.getAxisValue(MotionEvent.AXIS_BRAKE);

            // D-pad
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(x) < 0.15f && hatX != 0) x = hatX;
            if (Math.abs(y) < 0.15f && hatY != 0) y = hatY;

            // Deadzone
            if (Math.abs(x) < 0.12f) x = 0;
            if (Math.abs(y) < 0.12f) y = 0;

            // Y ekseni + tetikler → ileri/geri
            float fwd;
            if (rt > 0.1f)      fwd = rt;
            else if (lt > 0.1f) fwd = -lt;
            else                 fwd = -y;

            currentX = (int) (x * 100);
            currentY = (int) (fwd * 100);

            tvGamepad.setAlpha(1.0f);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isGameController(event.getDevice())) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BUTTON_X:
                    setHorn(true);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    if (!isConnected) checkPermissionsAndShowDevices();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isGameController(event.getDevice())) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BUTTON_X:
                    setHorn(false);
                    return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isGameController(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    // ================================================================
    //  Lifecycle
    // ================================================================

    @Override
    protected void onPause() {
        super.onPause();
        // Arka plana alınınca durdur
        currentX = 0;
        currentY = 0;
        btService.send("F00L00");
        btService.send("v");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sendHandler.removeCallbacksAndMessages(null);
        currentX = 0;
        currentY = 0;
        if (btService.isConnected()) {
            btService.send("F00L00");
            btService.send("v");
        }
        btService.disconnect();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    // ================================================================
    //  Yardımcılar
    // ================================================================

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
