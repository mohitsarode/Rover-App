package com.agrirover.teleop;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * Main Teleoperation Cockpit supporting 3 Control Modes:
 *  1. DUAL_THUMBS  (Split Vertical Throttle + Horizontal Steering)
 *  2. SINGLE_STICK (60/40 360° Analog Joystick)
 *  3. DPAD         (Discrete Press-and-Hold Directional Buttons)
 */
public class MainActivity extends AppCompatActivity implements
        MjpegStreamer.StreamListener,
        JoystickView.JoystickListener {

    private RoverConfig config;
    private RoverApiClient apiClient;
    private MjpegStreamer streamer;
    private Vibrator vibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String activeMode = LandingActivity.MODE_SINGLE_STICK;

    // Stream & Top HUD
    private ImageView ivCameraStream;
    private View layoutStreamPlaceholder;
    private TextView tvStreamStatus;
    private TextView tvStreamTarget;
    private Button btnRetryStream;
    private TextView tvHudFps;
    private TextView tvHudPing;
    private TextView tvHudConnBadge;
    private TextView tvHudMotionVector;
    private TextView tvSprayAlertBanner;
    private Button btnRotateStream;

    // Mode Containers
    private View leftSidePanel;
    private View rightSidePanel;
    private View leftDualControls;
    private View rightDualControls;
    private View leftDpadControls;
    private View rightDpadControls;
    private View singleStickBottomPanel;

    // Bottom Mode Pills & Slider
    private Button btnModeDualThumbs;
    private Button btnModeSingleStick;
    private Button btnModeDpad;
    private SeekBar sbSpeed;

    // Dual Thumbs Controls
    private AxisSliderView sliderThrottle;
    private AxisSliderView sliderSteering;
    private TextView tvDualSpeedVal;
    private Button btnDualSpeed35, btnDualSpeed65, btnDualSpeed100;
    private Button btnDualSpray, btnDualSpray05, btnDualSpray10, btnDualSpray20;

    // Single Stick Controls
    private JoystickView joystickView;
    private TextView tvSingleSpeedVal;
    private Button btnSingleSpeed35, btnSingleSpeed65, btnSingleSpeed100;
    private Button btnSingleSpray, btnSingleSpray05, btnSingleSpray10, btnSingleSpray20;
    private TextView tvProbeMoistureVal;
    private Button btnSingleProbe, btnSingleFlash, btnSingleCalib, btnSingleEstop;

    // Side Panel Shared Controls
    private Button btnSideFlash, btnSideProbe, btnSideEstop;

    // D-Pad State
    private float dpadThrottle = 0f;
    private float dpadSteer = 0f;
    private long lastAxisDispatchTime = 0;

    // App State
    private int currentSpeed = 65;
    private int currentSprayMs = 1000;
    private int flashBrightness = 0;
    private int streamRotationDeg = 0;
    private boolean isSprayActive = false;
    private boolean isProbing = false;

    private final Runnable pingRunnable = new Runnable() {
        @Override
        public void run() {
            if (apiClient != null) {
                apiClient.ping((success, latencyMs) -> updateConnectionStatus(success, latencyMs));
            }
            mainHandler.postDelayed(this, 2500);
        }
    };

    private final Runnable dpadDispatchLoop = new Runnable() {
        @Override
        public void run() {
            if (LandingActivity.MODE_DPAD.equals(activeMode) && (dpadThrottle != 0f || dpadSteer != 0f)) {
                dispatchAxisKinematics(dpadThrottle, dpadSteer);
                mainHandler.postDelayed(this, 60);
            }
        }
    };

    private final Runnable probePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isProbing || apiClient == null) return;
            apiClient.getProbeStatus(new RoverApiClient.ProbeCallback() {
                @Override
                public void onStatus(RoverApiClient.ProbeStatus status) {
                    updateProbeUI(status);
                    if (!status.idle) {
                        mainHandler.postDelayed(probePollRunnable, 500);
                    } else {
                        isProbing = false;
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    tvProbeMoistureVal.setText("🌱 Soil: ERR");
                    isProbing = false;
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        String requestedMode = getIntent().getStringExtra(LandingActivity.EXTRA_CONTROL_MODE);
        if (requestedMode != null) {
            activeMode = requestedMode;
        }

        config = new RoverConfig(this);
        apiClient = new RoverApiClient(config);
        streamer = new MjpegStreamer(config, this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        bindViews();
        setupListeners();

        applySpeed(65);
        applySprayDuration(1000);
        switchControlMode(activeMode);

        streamer.start();
        mainHandler.postDelayed(pingRunnable, 1000);
    }

    private void bindViews() {
        ivCameraStream = findViewById(R.id.iv_camera_stream);
        layoutStreamPlaceholder = findViewById(R.id.layout_stream_placeholder);
        tvStreamStatus = findViewById(R.id.tv_stream_status);
        tvStreamTarget = findViewById(R.id.tv_stream_target);
        btnRetryStream = findViewById(R.id.btn_retry_stream);
        tvHudFps = findViewById(R.id.tv_hud_fps);
        tvHudPing = findViewById(R.id.tv_hud_ping);
        tvHudConnBadge = findViewById(R.id.tv_hud_conn_badge);
        tvHudMotionVector = findViewById(R.id.tv_hud_motion_vector);
        tvSprayAlertBanner = findViewById(R.id.tv_spray_alert_banner);
        btnRotateStream = findViewById(R.id.btn_rotate_stream);

        leftSidePanel = findViewById(R.id.left_side_panel);
        rightSidePanel = findViewById(R.id.right_side_panel);
        leftDualControls = findViewById(R.id.left_dual_controls);
        rightDualControls = findViewById(R.id.right_dual_controls);
        leftDpadControls = findViewById(R.id.left_dpad_controls);
        rightDpadControls = findViewById(R.id.right_dpad_controls);
        singleStickBottomPanel = findViewById(R.id.single_stick_bottom_panel);

        btnModeDualThumbs = findViewById(R.id.btn_mode_dual_thumbs);
        btnModeSingleStick = findViewById(R.id.btn_mode_single_stick);
        btnModeDpad = findViewById(R.id.btn_mode_dpad);
        sbSpeed = findViewById(R.id.sb_speed);

        sliderThrottle = findViewById(R.id.slider_throttle);
        sliderSteering = findViewById(R.id.slider_steering);
        sliderSteering.configure(AxisSliderView.ORIENTATION_HORIZONTAL, Color.parseColor("#2563EB"), "R ►", "◄ L");

        tvDualSpeedVal = findViewById(R.id.tv_dual_speed_val);
        btnDualSpeed35 = findViewById(R.id.btn_dual_speed_35);
        btnDualSpeed65 = findViewById(R.id.btn_dual_speed_65);
        btnDualSpeed100 = findViewById(R.id.btn_dual_speed_100);
        btnDualSpray = findViewById(R.id.btn_dual_spray);
        btnDualSpray05 = findViewById(R.id.btn_dual_spray_05);
        btnDualSpray10 = findViewById(R.id.btn_dual_spray_10);
        btnDualSpray20 = findViewById(R.id.btn_dual_spray_20);

        joystickView = findViewById(R.id.joystick_view);
        joystickView.setConfig(config);
        joystickView.setListener(this);

        tvSingleSpeedVal = findViewById(R.id.tv_single_speed_val);
        btnSingleSpeed35 = findViewById(R.id.btn_single_speed_35);
        btnSingleSpeed65 = findViewById(R.id.btn_single_speed_65);
        btnSingleSpeed100 = findViewById(R.id.btn_single_speed_100);
        btnSingleSpray = findViewById(R.id.btn_single_spray);
        btnSingleSpray05 = findViewById(R.id.btn_single_spray_05);
        btnSingleSpray10 = findViewById(R.id.btn_single_spray_10);
        btnSingleSpray20 = findViewById(R.id.btn_single_spray_20);
        tvProbeMoistureVal = findViewById(R.id.tv_probe_moisture_val);
        btnSingleProbe = findViewById(R.id.btn_single_probe);
        btnSingleFlash = findViewById(R.id.btn_single_flash);
        btnSingleCalib = findViewById(R.id.btn_single_calib);
        btnSingleEstop = findViewById(R.id.btn_single_estop);

        btnSideFlash = findViewById(R.id.btn_side_flash);
        btnSideProbe = findViewById(R.id.btn_side_probe);
        btnSideEstop = findViewById(R.id.btn_side_estop);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        findViewById(R.id.btn_back_landing).setOnClickListener(v -> finish());
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());
        btnSingleCalib.setOnClickListener(v -> showSettingsDialog());
        tvProbeMoistureVal.setOnClickListener(v -> showSettingsDialog());
        btnRetryStream.setOnClickListener(v -> {
            tvStreamStatus.setText("Reconnecting...");
            streamer.restart();
        });

        btnRotateStream.setOnClickListener(v -> {
            streamRotationDeg = (streamRotationDeg + 90) % 360;
            btnRotateStream.setText(String.format(Locale.US, "🔄 %d°", streamRotationDeg));
        });

        btnModeDualThumbs.setOnClickListener(v -> switchControlMode(LandingActivity.MODE_DUAL_THUMBS));
        btnModeSingleStick.setOnClickListener(v -> switchControlMode(LandingActivity.MODE_SINGLE_STICK));
        btnModeDpad.setOnClickListener(v -> switchControlMode(LandingActivity.MODE_DPAD));

        btnDualSpeed35.setOnClickListener(v -> applySpeed(35));
        btnDualSpeed65.setOnClickListener(v -> applySpeed(65));
        btnDualSpeed100.setOnClickListener(v -> applySpeed(100));
        btnSingleSpeed35.setOnClickListener(v -> applySpeed(35));
        btnSingleSpeed65.setOnClickListener(v -> applySpeed(65));
        btnSingleSpeed100.setOnClickListener(v -> applySpeed(100));

        sbSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) applySpeed(Math.max(25, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnDualSpray.setOnClickListener(v -> triggerChemicalSpray());
        btnSingleSpray.setOnClickListener(v -> triggerChemicalSpray());
        btnDualSpray05.setOnClickListener(v -> applySprayDuration(500));
        btnDualSpray10.setOnClickListener(v -> applySprayDuration(1000));
        btnDualSpray20.setOnClickListener(v -> applySprayDuration(2000));
        btnSingleSpray05.setOnClickListener(v -> applySprayDuration(500));
        btnSingleSpray10.setOnClickListener(v -> applySprayDuration(1000));
        btnSingleSpray20.setOnClickListener(v -> applySprayDuration(2000));

        btnSideFlash.setOnClickListener(v -> toggleFlash());
        btnSingleFlash.setOnClickListener(v -> toggleFlash());
        btnSideProbe.setOnClickListener(v -> triggerSoilProbe());
        btnSingleProbe.setOnClickListener(v -> triggerSoilProbe());
        btnSideEstop.setOnClickListener(v -> emergencyStop());
        btnSingleEstop.setOnClickListener(v -> emergencyStop());

        AxisSliderView.AxisListener dualAxisListener = val ->
                dispatchAxisKinematics(sliderThrottle.getValue(), sliderSteering.getValue());
        sliderThrottle.setListener(dualAxisListener);
        sliderSteering.setListener(dualAxisListener);

        bindDpadButton(findViewById(R.id.btn_dpad_fwd), true, 1f);
        bindDpadButton(findViewById(R.id.btn_dpad_rev), true, -1f);
        bindDpadButton(findViewById(R.id.btn_dpad_left), false, -1f);
        bindDpadButton(findViewById(R.id.btn_dpad_right), false, 1f);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindDpadButton(Button btn, boolean isThrottle, float value) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackgroundResource(R.drawable.bg_button_tactical_active);
                    ((Button) v).setTextColor(Color.WHITE);
                    vibrate(20);
                    if (isThrottle) dpadThrottle = value;
                    else dpadSteer = value;
                    mainHandler.removeCallbacks(dpadDispatchLoop);
                    mainHandler.post(dpadDispatchLoop);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackgroundResource(R.drawable.bg_button_tactical);
                    ((Button) v).setTextColor(Color.parseColor(isThrottle ? "#059669" : "#2563EB"));
                    if (isThrottle) dpadThrottle = 0f;
                    else dpadSteer = 0f;
                    if (dpadThrottle == 0f && dpadSteer == 0f) {
                        mainHandler.removeCallbacks(dpadDispatchLoop);
                        onMotionStop();
                    } else {
                        dispatchAxisKinematics(dpadThrottle, dpadSteer);
                    }
                    return true;
            }
            return false;
        });
    }

    private void switchControlMode(String mode) {
        activeMode = mode;
        emergencyStopQuiet();

        boolean isDual = LandingActivity.MODE_DUAL_THUMBS.equals(mode);
        boolean isSingle = LandingActivity.MODE_SINGLE_STICK.equals(mode);
        boolean isDpad = LandingActivity.MODE_DPAD.equals(mode);

        leftSidePanel.setVisibility(isSingle ? View.GONE : View.VISIBLE);
        rightSidePanel.setVisibility(isSingle ? View.GONE : View.VISIBLE);
        singleStickBottomPanel.setVisibility(isSingle ? View.VISIBLE : View.GONE);

        leftDualControls.setVisibility(isDual ? View.VISIBLE : View.GONE);
        rightDualControls.setVisibility(isDual ? View.VISIBLE : View.GONE);
        leftDpadControls.setVisibility(isDpad ? View.VISIBLE : View.GONE);
        rightDpadControls.setVisibility(isDpad ? View.VISIBLE : View.GONE);

        styleModeButton(btnModeDualThumbs, isDual);
        styleModeButton(btnModeSingleStick, isSingle);
        styleModeButton(btnModeDpad, isDpad);
    }

    private void styleModeButton(Button btn, boolean active) {
        btn.setBackgroundResource(active ? R.drawable.bg_button_tactical_active : R.drawable.bg_button_tactical);
        btn.setTextColor(Color.parseColor(active ? "#FFFFFF" : "#475569"));
    }

    private void dispatchAxisKinematics(float throttle, float steer) {
        if (isProbing) {
            // Mechanical Interlock (Firmware ab9c802a & API Spec 4.1): Rover immobilized during probe extension
            tvHudMotionVector.setText("🔒 DRIVE LOCKED | SOIL PROBE ACTIVE");
            return;
        }
        KinematicsHelper.MotionResult res = KinematicsHelper.compute(throttle, steer, currentSpeed, config);
        long now = System.currentTimeMillis();
        if (now - lastAxisDispatchTime >= 50 || (res.leftPwm == 0 && res.rightPwm == 0)) {
            lastAxisDispatchTime = now;
            onMotionUpdate(res.leftPwm, res.rightPwm, throttle, steer, res.vectorText);
        }
    }

    @Override
    public void onMotionUpdate(int leftPwm, int rightPwm, float throttle, float steer, String vectorText) {
        tvHudMotionVector.setText(String.format(Locale.US, "%s | L:%d R:%d", vectorText, leftPwm, rightPwm));
        apiClient.sendDriveCommand(leftPwm, rightPwm, null);
    }

    @Override
    public void onMotionStop() {
        tvHudMotionVector.setText("■ STOPPED | L:0 R:0");
        apiClient.sendDriveCommand(0, 0, null);
    }

    private void applySpeed(int speed) {
        currentSpeed = Math.max(25, Math.min(100, speed));
        joystickView.setSpeedPercent(currentSpeed);
        sbSpeed.setProgress(currentSpeed);

        String tag = currentSpeed == 35 ? "SLOW" : (currentSpeed == 65 ? "MED" : (currentSpeed == 100 ? "FAST" : "CUSTOM"));
        tvDualSpeedVal.setText(String.format(Locale.US, "%d%%", currentSpeed));
        tvSingleSpeedVal.setText(String.format(Locale.US, "%d%% (%s)", currentSpeed, tag));

        styleSpeedBtn(btnDualSpeed35, currentSpeed == 35);
        styleSpeedBtn(btnDualSpeed65, currentSpeed == 65);
        styleSpeedBtn(btnDualSpeed100, currentSpeed == 100);
        styleSpeedBtn(btnSingleSpeed35, currentSpeed == 35);
        styleSpeedBtn(btnSingleSpeed65, currentSpeed == 65);
        styleSpeedBtn(btnSingleSpeed100, currentSpeed == 100);
    }

    private void styleSpeedBtn(Button btn, boolean active) {
        btn.setBackgroundResource(active ? R.drawable.bg_button_tactical_active : R.drawable.bg_button_tactical);
        btn.setTextColor(Color.parseColor(active ? "#FFFFFF" : "#475569"));
    }

    private void applySprayDuration(int ms) {
        currentSprayMs = ms;
        String secLabel = String.format(Locale.US, "%.1fs", ms / 1000f);
        btnDualSpray.setText(String.format(Locale.US, "💦 SPRAY (%s)", secLabel));
        btnSingleSpray.setText(String.format(Locale.US, "💦 SPRAY BURST (%s)", secLabel));

        styleSprayPill(btnDualSpray05, ms == 500);
        styleSprayPill(btnDualSpray10, ms == 1000);
        styleSprayPill(btnDualSpray20, ms == 2000);
        styleSprayPill(btnSingleSpray05, ms == 500);
        styleSprayPill(btnSingleSpray10, ms == 1000);
        styleSprayPill(btnSingleSpray20, ms == 2000);
    }

    private void styleSprayPill(Button btn, boolean active) {
        btn.setBackgroundResource(active ? R.drawable.bg_button_spray_pill_active : R.drawable.bg_button_tactical);
        btn.setTextColor(Color.parseColor(active ? "#2563EB" : "#475569"));
    }

    private void triggerChemicalSpray() {
        if (isSprayActive) return;
        isSprayActive = true;
        vibrate(70);

        tvSprayAlertBanner.setVisibility(View.VISIBLE);
        tvSprayAlertBanner.setText(String.format(Locale.US, "💦 SPRAY ACTIVE (%dms)", currentSprayMs));

        apiClient.triggerSpray(currentSprayMs, new RoverApiClient.ApiCallback() {
            @Override public void onSuccess(String responseText) {}
            @Override public void onError(String errorMessage) {
                Toast.makeText(MainActivity.this, "Spray error: " + errorMessage, Toast.LENGTH_SHORT).show();
            }
        });

        mainHandler.postDelayed(() -> {
            tvSprayAlertBanner.setVisibility(View.GONE);
            isSprayActive = false;
        }, currentSprayMs);
    }

    private void toggleFlash() {
        flashBrightness = (flashBrightness == 0) ? 180 : 0;
        vibrate(30);

        if (flashBrightness > 0) {
            btnSideFlash.setBackgroundResource(R.drawable.bg_button_flash);
            btnSideFlash.setTextColor(Color.parseColor("#92400E"));
            btnSingleFlash.setBackgroundResource(R.drawable.bg_button_flash);
            btnSingleFlash.setTextColor(Color.parseColor("#92400E"));
        } else {
            btnSideFlash.setBackgroundResource(R.drawable.bg_button_tactical);
            btnSideFlash.setTextColor(Color.parseColor("#64748B"));
            btnSingleFlash.setBackgroundResource(R.drawable.bg_button_tactical);
            btnSingleFlash.setTextColor(Color.parseColor("#64748B"));
        }

        apiClient.setFlash(flashBrightness, null);
    }

    private void triggerSoilProbe() {
        if (isProbing) return;
        isProbing = true;
        vibrate(50);
        emergencyStopQuiet();
        tvHudMotionVector.setText("🔒 DRIVE LOCKED | SOIL PROBE ACTIVE");
        tvProbeMoistureVal.setText("🌱 Soil: LOWERING PROBE 50mm...");
        tvProbeMoistureVal.setTextColor(Color.parseColor("#2563EB"));

        apiClient.triggerProbe(new RoverApiClient.ApiCallback() {
            @Override
            public void onSuccess(String responseText) {
                mainHandler.postDelayed(probePollRunnable, 400);
            }

            @Override
            public void onError(String errorMessage) {
                tvProbeMoistureVal.setText("🌱 Soil: PROBE FAIL (" + errorMessage + ")");
                tvProbeMoistureVal.setTextColor(Color.parseColor("#DC2626"));
                isProbing = false;
                tvHudMotionVector.setText("■ STOPPED | L:0 R:0");
            }
        });
    }

    private void updateProbeUI(RoverApiClient.ProbeStatus status) {
        if (status == null) return;
        if (!status.idle) {
            isProbing = true;
            tvHudMotionVector.setText("🔒 DRIVE LOCKED | " + (status.status != null ? status.status.toUpperCase(Locale.US) : "PROBING"));
            String statusMsg = status.status != null ? status.status : "Sampling subsurface...";
            tvProbeMoistureVal.setText(String.format(Locale.US, "🌱 Soil: %s (%d%%)", statusMsg, status.percent));
            tvProbeMoistureVal.setTextColor(Color.parseColor("#2563EB"));
        } else {
            isProbing = false;
            tvHudMotionVector.setText("■ STOPPED | L:0 R:0");
            String condition = status.dry ? "DRY (Hydrated)" : "OPTIMAL";
            String detail = status.status != null ? status.status : condition;
            tvProbeMoistureVal.setText(String.format(Locale.US, "🌱 Soil: %d%% | Limit: %d%% (%s)",
                    status.percent, status.threshold, detail));
            tvProbeMoistureVal.setTextColor(Color.parseColor(status.dry ? "#D97706" : "#059669"));
        }
    }

    private void emergencyStopQuiet() {
        dpadThrottle = 0f;
        dpadSteer = 0f;
        mainHandler.removeCallbacks(dpadDispatchLoop);
        if (sliderThrottle != null) sliderThrottle.reset();
        if (sliderSteering != null) sliderSteering.reset();
        if (joystickView != null) joystickView.resetThumb();
        onMotionStop();
    }

    private void emergencyStop() {
        vibrate(110);
        emergencyStopQuiet();
        tvHudMotionVector.setText("■ E-STOP | L:0 R:0");
        Toast.makeText(this, "🛑 EMERGENCY STOP ASSERTED", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStreamConnected(String url) {
        layoutStreamPlaceholder.setVisibility(View.GONE);
        ivCameraStream.setVisibility(View.VISIBLE);
        setConnected(true);
    }

    @Override
    public void onNewFrame(Bitmap bitmap, int fps) {
        if (streamRotationDeg != 0) {
            Matrix m = new Matrix();
            m.postRotate(streamRotationDeg);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            ivCameraStream.setImageBitmap(rotated);
        } else {
            ivCameraStream.setImageBitmap(bitmap);
        }
        tvHudFps.setText(String.format(Locale.US, "%d FPS", fps));
    }

    @Override
    public void onStreamError(String message) {
        tvStreamStatus.setText(String.format(Locale.US, "Connecting (%s)...", message));
        tvStreamTarget.setText(config.getStreamUrl());
    }

    @Override
    public void onStreamDisconnected() {
        ivCameraStream.setVisibility(View.GONE);
        layoutStreamPlaceholder.setVisibility(View.VISIBLE);
        tvHudFps.setText("0 FPS");
        setConnected(false);
    }

    private void updateConnectionStatus(boolean success, long latencyMs) {
        setConnected(success);
        tvHudPing.setText(success ? String.format(Locale.US, "%d ms", latencyMs) : "-- ms");
    }

    private void setConnected(boolean connected) {
        tvHudConnBadge.setText(connected ? "ONLINE" : "OFFLINE");
        tvHudConnBadge.setTextColor(Color.parseColor(connected ? "#059669" : "#DC2626"));
        tvHudConnBadge.setBackgroundResource(connected ? R.drawable.bg_status_badge : R.drawable.bg_button_tactical);
    }

    @SuppressLint("InflateParams")
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        SeekBar sbTurnSens = dialogView.findViewById(R.id.sb_dialog_turn_sens);
        TextView tvTurnSensVal = dialogView.findViewById(R.id.tv_dialog_turn_sens_val);
        SeekBar sbTrim = dialogView.findViewById(R.id.sb_dialog_trim);
        TextView tvTrimVal = dialogView.findViewById(R.id.tv_dialog_trim_val);
        CheckBox cbInvertThrottle = dialogView.findViewById(R.id.cb_invert_throttle);
        CheckBox cbInvertSteer = dialogView.findViewById(R.id.cb_invert_steer);

        EditText etHost = dialogView.findViewById(R.id.et_dialog_host);
        EditText etCtrlPort = dialogView.findViewById(R.id.et_dialog_ctrl_port);
        EditText etStreamPort = dialogView.findViewById(R.id.et_dialog_stream_port);
        EditText etSprayMs = dialogView.findViewById(R.id.et_dialog_spray_ms);
        SeekBar sbProbeThreshold = dialogView.findViewById(R.id.sb_dialog_probe_threshold);
        TextView tvProbeThresholdVal = dialogView.findViewById(R.id.tv_dialog_probe_threshold_val);

        Button btnReset = dialogView.findViewById(R.id.btn_dialog_reset);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_save);

        float currentSens = config.getTurnSensitivity();
        sbTurnSens.setProgress(Math.round((currentSens - 0.30f) * 100f));
        tvTurnSensVal.setText(String.format(Locale.US, "%.2fx", currentSens));

        int currentTrim = config.getSteeringTrim();
        sbTrim.setProgress(currentTrim + 25);
        tvTrimVal.setText(String.format(Locale.US, "%s%d%%", (currentTrim > 0 ? "+" : ""), currentTrim));

        cbInvertThrottle.setChecked(config.isInvertThrottle());
        cbInvertSteer.setChecked(config.isInvertSteer());

        etHost.setText(config.getHost());
        etCtrlPort.setText(String.valueOf(config.getCtrlPort()));
        etStreamPort.setText(String.valueOf(config.getStreamPort()));
        etSprayMs.setText(String.valueOf(config.getSprayMs()));

        int currentThreshold = config.getProbeThreshold();
        sbProbeThreshold.setProgress(Math.max(0, Math.min(70, currentThreshold - 10)));
        tvProbeThresholdVal.setText(String.format(Locale.US, "%d%%", currentThreshold));

        sbProbeThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvProbeThresholdVal.setText(String.format(Locale.US, "%d%%", 10 + progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbTurnSens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTurnSensVal.setText(String.format(Locale.US, "%.2fx", 0.30f + (progress / 100f)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbTrim.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress - 25;
                tvTrimVal.setText(String.format(Locale.US, "%s%d%%", (val > 0 ? "+" : ""), val));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnReset.setOnClickListener(v -> {
            config.resetDefaults();
            dialog.dismiss();
            streamer.restart();
            Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> {
            config.setTurnSensitivity(0.30f + (sbTurnSens.getProgress() / 100f));
            config.setSteeringTrim(sbTrim.getProgress() - 25);
            config.setInvertThrottle(cbInvertThrottle.isChecked());
            config.setInvertSteer(cbInvertSteer.isChecked());
            config.setHost(etHost.getText().toString());
            try { config.setCtrlPort(Integer.parseInt(etCtrlPort.getText().toString().trim())); } catch (Exception ignored) {}
            try { config.setStreamPort(Integer.parseInt(etStreamPort.getText().toString().trim())); } catch (Exception ignored) {}
            try { config.setSprayMs(Integer.parseInt(etSprayMs.getText().toString().trim())); } catch (Exception ignored) {}
            int newThreshold = 10 + sbProbeThreshold.getProgress();
            config.setProbeThreshold(newThreshold);
            apiClient.setProbeThreshold(newThreshold, null);

            dialog.dismiss();
            streamer.restart();
            Toast.makeText(this, "Settings applied", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void vibrate(long ms) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(ms);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (streamer != null) streamer.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (streamer != null) streamer.stop();
        emergencyStopQuiet();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(pingRunnable);
        mainHandler.removeCallbacks(dpadDispatchLoop);
        mainHandler.removeCallbacks(probePollRunnable);
        if (streamer != null) streamer.stop();
    }
}
