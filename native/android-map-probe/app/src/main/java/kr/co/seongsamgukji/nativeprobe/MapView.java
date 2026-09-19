package kr.co.seongsamgukji.nativeprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

public class MapView extends View {
    private static final float TILE = 48f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    private Bitmap map;
    private float scale = 1f;
    private float offsetX = 0f;
    private float offsetY = 0f;
    private float lastX;
    private float lastY;
    private int selectedX = -1;
    private int selectedY = -1;

    public MapView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);

        try (InputStream in = context.getAssets().open("map/m000.jpg")) {
            map = BitmapFactory.decodeStream(in);
        }
        catch (IOException e) {
            throw new RuntimeException("m000.jpg load failed", e);
        }

        gridPaint.setColor(0x55FFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32f);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float oldScale = scale;
                        scale *= detector.getScaleFactor();
                        scale = Math.max(0.35f, Math.min(scale, 4.0f));

                        float focusX = detector.getFocusX();
                        float focusY = detector.getFocusY();
                        float ratio = scale / oldScale;
                        offsetX = focusX - (focusX - offsetX) * ratio;
                        offsetY = focusY - (focusY - offsetY) * ratio;
                        invalidate();
                        return true;
                    }
                });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (map == null) return;
        float fit = Math.min((float) w / map.getWidth(), (float) h / map.getHeight());
        scale = Math.min(1f, fit);
        offsetX = (w - map.getWidth() * scale) * 0.5f;
        offsetY = (h - map.getHeight() * scale) * 0.5f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (map == null) return;

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        canvas.drawBitmap(map, 0, 0, paint);

        int cols = map.getWidth() / (int) TILE;
        int rows = map.getHeight() / (int) TILE;

        for (int x = 0; x <= cols; x++) {
            canvas.drawLine(x * TILE, 0, x * TILE, rows * TILE, gridPaint);
        }
        for (int y = 0; y <= rows; y++) {
            canvas.drawLine(0, y * TILE, cols * TILE, y * TILE, gridPaint);
        }

        if (selectedX >= 0 && selectedY >= 0) {
            Paint sel = new Paint(Paint.ANTI_ALIAS_FLAG);
            sel.setColor(0x66FFFF00);
            sel.setStyle(Paint.Style.FILL);
            canvas.drawRect(new RectF(
                    selectedX * TILE,
                    selectedY * TILE,
                    (selectedX + 1) * TILE,
                    (selectedY + 1) * TILE), sel);
        }

        canvas.restore();

        String info = "m000  " + (map.getWidth()/48) + "x" + (map.getHeight()/48)
                + " tiles  |  pinch=zoom, drag=pan";
        canvas.drawText(info, 24, 44, textPaint);
        if (selectedX >= 0) {
            canvas.drawText("tile: (" + selectedX + ", " + selectedY + ")", 24, 84, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    offsetX += dx;
                    offsetY += dy;
                    lastX = event.getX();
                    lastY = event.getY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    float move = Math.abs(event.getX() - lastX) + Math.abs(event.getY() - lastY);
                    if (move < 18f) {
                        float mx = (event.getX() - offsetX) / scale;
                        float my = (event.getY() - offsetY) / scale;
                        int tx = (int) (mx / TILE);
                        int ty = (int) (my / TILE);
                        if (tx >= 0 && ty >= 0 && tx < map.getWidth()/48 && ty < map.getHeight()/48) {
                            selectedX = tx;
                            selectedY = ty;
                            invalidate();
                        }
                    }
                    return true;
            }
        }
        return true;
    }
}
