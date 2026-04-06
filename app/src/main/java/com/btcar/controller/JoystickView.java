package com.btcar.controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Dokunmatik joystick widget.
 * X ve Y eksen değerlerini -100..+100 arasında raporlar.
 */
public class JoystickView extends View {

    public interface JoystickListener {
        void onJoystickMoved(int xPercent, int yPercent);
    }

    private Paint basePaint;
    private Paint baseRingPaint;
    private Paint thumbPaint;
    private Paint thumbGlowPaint;
    private Paint crosshairPaint;

    private float centerX, centerY;
    private float baseRadius;
    private float thumbRadius;
    private float thumbX, thumbY;
    private boolean isTouching = false;

    private JoystickListener listener;

    // Renkler
    private final int COLOR_BASE = 0xFF21262D;
    private final int COLOR_BASE_RING = 0xFF30363D;
    private final int COLOR_THUMB = 0xFF58A6FF;
    private final int COLOR_THUMB_PRESSED = 0xFF79C0FF;
    private final int COLOR_THUMB_GLOW = 0x4058A6FF;
    private final int COLOR_CROSSHAIR = 0x20F0F6FC;

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Touch olaylarının her zaman iletilmesini garanti et
        setClickable(true);
        setFocusable(true);

        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(COLOR_BASE);
        basePaint.setStyle(Paint.Style.FILL);

        baseRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseRingPaint.setColor(COLOR_BASE_RING);
        baseRingPaint.setStyle(Paint.Style.STROKE);
        baseRingPaint.setStrokeWidth(3f);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(COLOR_THUMB);
        thumbPaint.setStyle(Paint.Style.FILL);

        thumbGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbGlowPaint.setColor(COLOR_THUMB_GLOW);
        thumbGlowPaint.setStyle(Paint.Style.FILL);

        crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setColor(COLOR_CROSSHAIR);
        crosshairPaint.setStrokeWidth(1.5f);
    }

    public void setJoystickListener(JoystickListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) / 2f - 10f;
        thumbRadius = baseRadius * 0.30f;
        thumbX = centerX;
        thumbY = centerY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Taban daire
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);

        // Çapraz çizgiler (crosshair)
        canvas.drawLine(centerX - baseRadius * 0.7f, centerY,
                centerX + baseRadius * 0.7f, centerY, crosshairPaint);
        canvas.drawLine(centerX, centerY - baseRadius * 0.7f,
                centerX, centerY + baseRadius * 0.7f, crosshairPaint);

        // İç ve dış halkalar
        canvas.drawCircle(centerX, centerY, baseRadius, baseRingPaint);
        canvas.drawCircle(centerX, centerY, baseRadius * 0.5f, crosshairPaint);

        // Thumbstick glow
        if (isTouching) {
            thumbGlowPaint.setShader(new RadialGradient(
                    thumbX, thumbY, thumbRadius * 2f,
                    COLOR_THUMB_GLOW, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(thumbX, thumbY, thumbRadius * 2f, thumbGlowPaint);
        }

        // Thumbstick
        int thumbColor = isTouching ? COLOR_THUMB_PRESSED : COLOR_THUMB;
        thumbPaint.setColor(thumbColor);

        // İç gradyan efekti
        Paint innerShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerShadow.setShader(new RadialGradient(
                thumbX - thumbRadius * 0.3f, thumbY - thumbRadius * 0.3f,
                thumbRadius,
                lightenColor(thumbColor, 0.3f), thumbColor,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(thumbX, thumbY, thumbRadius, innerShadow);

        // Thumbstick kenar
        Paint thumbBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbBorder.setColor(lightenColor(thumbColor, 0.2f));
        thumbBorder.setStyle(Paint.Style.STROKE);
        thumbBorder.setStrokeWidth(2f);
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbBorder);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                isTouching = true;
                float dx = event.getX() - centerX;
                float dy = event.getY() - centerY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float maxDist = baseRadius - thumbRadius;

                if (maxDist <= 0) maxDist = 1; // Sıfıra bölme koruması

                if (dist > maxDist) {
                    dx = dx / dist * maxDist;
                    dy = dy / dist * maxDist;
                }

                thumbX = centerX + dx;
                thumbY = centerY + dy;

                // Yüzde hesapla: X → -100..+100, Y → -100..+100 (yukarı pozitif)
                int xPercent = (int) (dx / maxDist * 100);
                int yPercent = (int) (-dy / maxDist * 100); // Y ekseni ters

                if (listener != null) {
                    listener.onJoystickMoved(xPercent, yPercent);
                }

                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                resetToCenter();
                performClick();
                return true;
        }
        return true; // Her zaman true döndür — tüm touch olaylarını yakala
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /**
     * Joystick'i merkeze sıfırla ve listener'a (0,0) bildir.
     */
    private void resetToCenter() {
        isTouching = false;
        thumbX = centerX;
        thumbY = centerY;

        if (listener != null) {
            listener.onJoystickMoved(0, 0);
        }

        invalidate();
    }

    private int lightenColor(int color, float factor) {
        int r = Math.min(255, (int) (Color.red(color) + (255 - Color.red(color)) * factor));
        int g = Math.min(255, (int) (Color.green(color) + (255 - Color.green(color)) * factor));
        int b = Math.min(255, (int) (Color.blue(color) + (255 - Color.blue(color)) * factor));
        return Color.argb(Color.alpha(color), r, g, b);
    }
}
