package com.agrirover.teleop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Land Rover Clean Automotive 360° Analog Joystick.
 */
public class JoystickView extends View {

    public interface JoystickListener {
        void onMotionUpdate(int leftPwm, int rightPwm, float throttle, float steer, String vectorText);
        void onMotionStop();
    }

    private JoystickListener listener;
    private RoverConfig config;
    private int speedPercent = 65;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float centerX, centerY;
    private float baseRadius, thumbRadius, maxTravelRadius;
    private float thumbX, thumbY;
    private boolean isDragging = false;

    private long lastDispatchTime = 0;
    private static final long DISPATCH_INTERVAL_MS = 50;

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        basePaint.setColor(Color.parseColor("#F1F5F9"));
        basePaint.setStyle(Paint.Style.FILL);

        baseRingPaint.setColor(Color.parseColor("#CBD5E1"));
        baseRingPaint.setStyle(Paint.Style.STROKE);
        baseRingPaint.setStrokeWidth(3f);

        guidePaint.setColor(Color.parseColor("#E2E8F0"));
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(2f);

        thumbPaint.setColor(Color.parseColor("#2563EB")); // Modern automotive blue knob
        thumbPaint.setStyle(Paint.Style.FILL);

        thumbHighlightPaint.setColor(Color.parseColor("#80FFFFFF"));
        thumbHighlightPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.parseColor("#64748B"));
        textPaint.setTextSize(22f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setConfig(RoverConfig config) {
        this.config = config;
    }

    public void setListener(JoystickListener listener) {
        this.listener = listener;
    }

    public void setSpeedPercent(int percent) {
        this.speedPercent = Math.max(25, Math.min(100, percent));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        thumbX = centerX;
        thumbY = centerY;

        float minDim = Math.min(w, h);
        baseRadius = (minDim / 2f) * 0.88f;
        thumbRadius = baseRadius * 0.34f;
        maxTravelRadius = Math.max(1f, baseRadius - thumbRadius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseRadius <= 0) return;

        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, baseRingPaint);

        canvas.drawLine(centerX - baseRadius, centerY, centerX + baseRadius, centerY, guidePaint);
        canvas.drawLine(centerX, centerY - baseRadius, centerX, centerY + baseRadius, guidePaint);
        canvas.drawCircle(centerX, centerY, baseRadius * 0.52f, guidePaint);

        canvas.drawText("360° DRIVE", centerX, centerY + baseRadius - 18f, textPaint);

        int glowColor = isDragging ? 0x402563EB : 0x202563EB;
        glowPaint.setShader(new RadialGradient(thumbX, thumbY, thumbRadius * 1.5f,
                glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 1.5f, glowPaint);

        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);
        canvas.drawCircle(thumbX - thumbRadius * 0.22f, thumbY - thumbRadius * 0.22f,
                thumbRadius * 0.24f, thumbHighlightPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                isDragging = true;
                processTouch(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    processTouch(event.getX(), event.getY());
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                isDragging = false;
                resetThumb();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void processTouch(float touchX, float touchY) {
        float dx = touchX - centerX;
        float dy = touchY - centerY;
        float distance = (float) Math.hypot(dx, dy);

        if (distance > maxTravelRadius) {
            float ratio = maxTravelRadius / distance;
            thumbX = centerX + (dx * ratio);
            thumbY = centerY + (dy * ratio);
        } else {
            thumbX = touchX;
            thumbY = touchY;
        }
        invalidate();

        float rawThrottle = -(thumbY - centerY) / maxTravelRadius;
        float rawSteer = (thumbX - centerX) / maxTravelRadius;

        KinematicsHelper.MotionResult res = KinematicsHelper.compute(rawThrottle, rawSteer, speedPercent, config);

        long now = System.currentTimeMillis();
        if (now - lastDispatchTime >= DISPATCH_INTERVAL_MS || (res.leftPwm == 0 && res.rightPwm == 0)) {
            lastDispatchTime = now;
            if (listener != null) {
                listener.onMotionUpdate(res.leftPwm, res.rightPwm, rawThrottle, rawSteer, res.vectorText);
            }
        }
    }

    public void resetThumb() {
        thumbX = centerX;
        thumbY = centerY;
        invalidate();
        if (listener != null) {
            listener.onMotionStop();
        }
    }
}
