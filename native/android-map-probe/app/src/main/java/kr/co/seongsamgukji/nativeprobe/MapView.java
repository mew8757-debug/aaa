package kr.co.seongsamgukji.nativeprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MapView extends View {
    private static final float TILE = 48f;
    private static final int UNIT_W = 48;
    private static final int UNIT_H = 48;
    private static final int UNIT_FRAMES = 11;
    private static final long MOVE_STEP_MS = 170L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hpBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    private Bitmap map;
    private Bitmap[] moveFrames;
    private Bitmap[] idleFrames;
    private byte[] palette;

    private float scale = 1f;
    private float offsetX = 0f;
    private float offsetY = 0f;
    private float lastX;
    private float lastY;
    private float downX;
    private float downY;

    private int selectedX = -1;
    private int selectedY = -1;

    private int unitX = 10;
    private int unitY = 8;
    private int targetX = unitX;
    private int targetY = unitY;
    private int moveFrame = 0;
    private long lastMoveStepAt = 0L;

    public MapView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);

        try (InputStream in = context.getAssets().open("map/m000.jpg")) {
            map = BitmapFactory.decodeStream(in);
        }
        catch (IOException e) {
            throw new RuntimeException("m000.jpg load failed", e);
        }

        try {
            palette = loadBytes(context, "sprites/spalet_000.bin");
            if (palette.length != 256 * 3) throw new IOException("palette size=" + palette.length);
            moveFrames = loadIndexedFrames(context, "sprites/unit_mov_000.bin", 48, 48, 11);
            idleFrames = loadIndexedFrames(context, "sprites/unit_spc_000.bin", 48, 48, 5);
        }
        catch (IOException e) {
            throw new RuntimeException("native sprite decode failed", e);
        }

        spritePaint.setAntiAlias(false);
        spritePaint.setFilterBitmap(false);

        gridPaint.setColor(0x44FFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        selectedPaint.setColor(0x66FFFF00);
        selectedPaint.setStyle(Paint.Style.FILL);

        hpBackPaint.setColor(0xCC000000);
        hpPaint.setColor(0xFF55FF55);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
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

    private byte[] loadBytes(Context context, String assetName) throws IOException {
        try (InputStream in = context.getAssets().open(assetName);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private Bitmap[] loadIndexedFrames(Context context, String assetName,
                                       int width, int height, int frameCount) throws IOException {
        byte[] raw = loadBytes(context, assetName);
        int frameBytes = width * height;
        if (raw.length < frameBytes * frameCount) {
            throw new IOException("sprite payload too small: " + raw.length);
        }

        Bitmap[] frames = new Bitmap[frameCount];
        int[] pixels = new int[frameBytes];

        for (int f = 0; f < frameCount; f++) {
            int base = f * frameBytes;
            for (int i = 0; i < frameBytes; i++) {
                int index = raw[base + i] & 0xff;
                if (index == 0) {
                    pixels[i] = Color.TRANSPARENT;
                }
                else {
                    int q = index * 3;
                    int r = palette[q] & 0xff;
                    int g = palette[q + 1] & 0xff;
                    int b = palette[q + 2] & 0xff;
                    pixels[i] = Color.argb(255, r, g, b);
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            frames[f] = bitmap;
        }
        return frames;
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

        updateUnitMovement();

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
            canvas.drawRect(new RectF(
                    selectedX * TILE,
                    selectedY * TILE,
                    (selectedX + 1) * TILE,
                    (selectedY + 1) * TILE), selectedPaint);
        }

        // Exact indexed-color sprite: Spalet.e5 RGB palette + original Unit_*.e5 indexes.
        boolean moving = unitX != targetX || unitY != targetY;
        Bitmap[] source = moving ? moveFrames : idleFrames;
        int frameIndex = moving ? Math.max(0, Math.min(moveFrame, source.length - 1)) : 0;
        Bitmap frame = source[frameIndex];
        float spriteX = unitX * TILE;
        float spriteY = unitY * TILE;
        canvas.drawBitmap(frame, spriteX, spriteY, spritePaint);

        // Temporary HP bar used only to make the native unit position obvious.
        float hpY = spriteY - 5f;
        canvas.drawRect(spriteX + 4f, hpY, spriteX + 44f, hpY + 4f, hpBackPaint);
        canvas.drawRect(spriteX + 5f, hpY + 1f, spriteX + 41f, hpY + 3f, hpPaint);

        canvas.restore();

        canvas.drawText("Native v0.3  |  실제 맵 + S형상 + Spalet 원색", 24, 40, textPaint);
        canvas.drawText("터치: 유닛 이동  ·  드래그: 화면 이동  ·  두 손가락: 확대/축소", 24, 76, textPaint);

        if (selectedX >= 0) {
            canvas.drawText("선택 타일 (" + selectedX + ", " + selectedY + ") / 유닛 (" + unitX + ", " + unitY + ")",
                    24, 112, textPaint);
        }

        if (unitX != targetX || unitY != targetY) {
            postInvalidateDelayed(40L);
        }
    }

    private void updateUnitMovement() {
        if (unitX == targetX && unitY == targetY) {
            moveFrame = 0;
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastMoveStepAt < MOVE_STEP_MS) return;
        lastMoveStepAt = now;

        if (unitX < targetX) unitX++;
        else if (unitX > targetX) unitX--;
        else if (unitY < targetY) unitY++;
        else if (unitY > targetY) unitY--;

        moveFrame = (moveFrame + 1) % moveFrames.length;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = event.getX();
                    downY = lastY = event.getY();
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
                    float move = Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY);
                    if (move < 24f) {
                        float mx = (event.getX() - offsetX) / scale;
                        float my = (event.getY() - offsetY) / scale;
                        int tx = (int) (mx / TILE);
                        int ty = (int) (my / TILE);

                        if (tx >= 0 && ty >= 0 && tx < map.getWidth() / 48 && ty < map.getHeight() / 48) {
                            selectedX = tx;
                            selectedY = ty;
                            targetX = tx;
                            targetY = ty;
                            lastMoveStepAt = 0L;
                            invalidate();
                        }
                    }
                    return true;
            }
        }
        return true;
    }
}
