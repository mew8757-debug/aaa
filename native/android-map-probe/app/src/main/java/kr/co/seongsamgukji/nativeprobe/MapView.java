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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapView extends View {
    private static final float TILE = 48f;
    private static final int UNIT_W = 48;
    private static final int UNIT_H = 48;
    private static final int MOVE_FRAMES = 11;
    private static final int IDLE_FRAMES = 5;
    private static final long MOVE_STEP_MS = 165L;

    private static final int FACTION_PLAYER = 0;
    private static final int FACTION_ALLY = 1;
    private static final int FACTION_ENEMY = 2;

    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hpBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    private final List<BattleUnit> units = new ArrayList<>();
    private final Map<Integer, Bitmap[]> idleCache = new HashMap<>();
    private final Map<Integer, Bitmap[]> moveCache = new HashMap<>();

    private Bitmap map;
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
    private BattleUnit selectedUnit;

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
            palette = loadBytes(context, "sprites/palette.bin");
            if (palette.length < 256 * 3) {
                throw new IOException("palette size=" + palette.length);
            }
            loadBattle(context);
        }
        catch (Exception e) {
            throw new RuntimeException("native battle data load failed", e);
        }

        spritePaint.setAntiAlias(false);
        spritePaint.setFilterBitmap(false);

        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        selectedPaint.setColor(0x66FFFF00);
        selectedPaint.setStyle(Paint.Style.FILL);

        hpBackPaint.setColor(0xD0000000);
        hpPaint.setColor(0xFF55FF55);

        labelBackPaint.setColor(0xAA000000);

        overlayTextPaint.setColor(Color.WHITE);
        overlayTextPaint.setTextSize(26f);
        overlayTextPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        unitTextPaint.setColor(Color.WHITE);
        unitTextPaint.setTextSize(13f);
        unitTextPaint.setTextAlign(Paint.Align.CENTER);
        unitTextPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);

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

    private void loadBattle(Context context) throws Exception {
        String jsonText = new String(loadBytes(context, "battle/battle0.json"), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(jsonText);
        JSONArray array = root.getJSONArray("units");

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            BattleUnit unit = new BattleUnit();
            unit.characterId = item.getInt("characterId");
            unit.name = item.getString("name");
            unit.faction = item.getInt("faction");
            unit.x = item.getInt("x");
            unit.y = item.getInt("y");
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            unit.direction = item.optInt("direction", 2);
            unit.level = item.optInt("level", 0);
            unit.unitImage = item.getInt("unitImage");

            unit.idleFrames = idleCache.get(unit.unitImage);
            unit.moveFrames = moveCache.get(unit.unitImage);

            if (unit.idleFrames == null) {
                unit.idleFrames = loadIndexedFrames(
                        context,
                        String.format("sprites/spc_%04d.bin", unit.unitImage),
                        UNIT_W,
                        UNIT_H,
                        IDLE_FRAMES);
                idleCache.put(unit.unitImage, unit.idleFrames);
            }

            if (unit.moveFrames == null) {
                unit.moveFrames = loadIndexedFrames(
                        context,
                        String.format("sprites/mov_%04d.bin", unit.unitImage),
                        UNIT_W,
                        UNIT_H,
                        MOVE_FRAMES);
                moveCache.put(unit.unitImage, unit.moveFrames);
            }

            units.add(unit);
        }

        for (BattleUnit unit : units) {
            if (unit.faction == FACTION_PLAYER) {
                selectedUnit = unit;
                selectedX = unit.x;
                selectedY = unit.y;
                break;
            }
        }
    }

    private byte[] loadBytes(Context context, String assetName) throws IOException {
        try (InputStream in = context.getAssets().open(assetName);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private Bitmap[] loadIndexedFrames(Context context, String assetName,
                                       int width, int height, int frameCount) throws IOException {
        byte[] raw = loadBytes(context, assetName);
        int frameBytes = width * height;
        if (raw.length < frameBytes * frameCount) {
            throw new IOException(assetName + " payload too small: " + raw.length);
        }

        Bitmap[] frames = new Bitmap[frameCount];
        int[] pixels = new int[frameBytes];

        for (int f = 0; f < frameCount; f++) {
            int base = f * frameBytes;
            for (int i = 0; i < frameBytes; i++) {
                int index = raw[base + i] & 0xff;
                if (index == 0) {
                    pixels[i] = Color.TRANSPARENT;
                    continue;
                }

                int q = index * 3;

                // Spalet.e5 uses B,R,G byte order in this engine generation.
                int b = palette[q] & 0xff;
                int r = palette[q + 1] & 0xff;
                int g = palette[q + 2] & 0xff;

                if (r >= 248 && g <= 8 && b >= 248) {
                    pixels[i] = Color.TRANSPARENT;
                }
                else {
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

        boolean moving = updateMovement();

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        canvas.drawBitmap(map, 0, 0, mapPaint);

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

        for (BattleUnit unit : units) {
            drawUnit(canvas, unit);
        }

        canvas.restore();

        int enemies = 0;
        int players = 0;
        for (BattleUnit unit : units) {
            if (unit.faction == FACTION_ENEMY) enemies++;
            if (unit.faction == FACTION_PLAYER) players++;
        }

        canvas.drawText("Native v0.4  |  S_00 실제 초기 배치  |  아군 " + players + " / 적군 " + enemies,
                22, 36, overlayTextPaint);
        canvas.drawText("유닛 터치=선택 · 선택한 아군의 이동 위치 터치 · 드래그=화면 이동 · 두 손가락=확대/축소",
                22, 70, overlayTextPaint);

        if (selectedUnit != null) {
            canvas.drawText(
                    "선택: " + selectedUnit.name + "  ID " + selectedUnit.characterId
                            + "  S이미지 #" + selectedUnit.unitImage
                            + "  (" + selectedUnit.x + "," + selectedUnit.y + ")",
                    22, 104, overlayTextPaint);
        }

        if (moving) {
            postInvalidateDelayed(35L);
        }
    }

    private void drawUnit(Canvas canvas, BattleUnit unit) {
        boolean moving = unit.x != unit.targetX || unit.y != unit.targetY;
        Bitmap[] source = moving ? unit.moveFrames : unit.idleFrames;
        int frameIndex = moving
                ? Math.max(0, Math.min(unit.moveFrame, source.length - 1))
                : 0;
        Bitmap frame = source[frameIndex];

        float spriteX = unit.x * TILE;
        float spriteY = unit.y * TILE;
        canvas.drawBitmap(frame, spriteX, spriteY, spritePaint);

        float hpY = spriteY - 4f;
        hpBackPaint.setColor(0xD0000000);
        if (unit.faction == FACTION_ENEMY) {
            hpPaint.setColor(0xFFFF5555);
        }
        else if (unit.faction == FACTION_ALLY) {
            hpPaint.setColor(0xFF55AAFF);
        }
        else {
            hpPaint.setColor(0xFF55FF55);
        }

        canvas.drawRect(spriteX + 4f, hpY, spriteX + 44f, hpY + 4f, hpBackPaint);
        canvas.drawRect(spriteX + 5f, hpY + 1f, spriteX + 41f, hpY + 3f, hpPaint);

        float cx = spriteX + TILE * 0.5f;
        String label = unit.name;
        float tw = unitTextPaint.measureText(label);
        float labelY = spriteY + TILE + 13f;
        canvas.drawRect(cx - tw * 0.5f - 3f, labelY - 13f, cx + tw * 0.5f + 3f, labelY + 3f,
                labelBackPaint);
        canvas.drawText(label, cx, labelY, unitTextPaint);
    }

    private boolean updateMovement() {
        boolean anyMoving = false;
        long now = SystemClock.uptimeMillis();

        for (BattleUnit unit : units) {
            if (unit.x == unit.targetX && unit.y == unit.targetY) {
                unit.moveFrame = 0;
                continue;
            }

            anyMoving = true;
            if (now - unit.lastMoveStepAt < MOVE_STEP_MS) {
                continue;
            }
            unit.lastMoveStepAt = now;

            if (unit.x < unit.targetX) unit.x++;
            else if (unit.x > unit.targetX) unit.x--;
            else if (unit.y < unit.targetY) unit.y++;
            else if (unit.y > unit.targetY) unit.y--;

            unit.moveFrame = (unit.moveFrame + 1) % unit.moveFrames.length;

            if (unit == selectedUnit) {
                selectedX = unit.x;
                selectedY = unit.y;
            }
        }

        return anyMoving;
    }

    private BattleUnit findUnitAt(int tx, int ty) {
        for (BattleUnit unit : units) {
            if (unit.x == tx && unit.y == ty) {
                return unit;
            }
        }
        return null;
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

                        if (tx >= 0 && ty >= 0
                                && tx < map.getWidth() / 48
                                && ty < map.getHeight() / 48) {
                            BattleUnit hit = findUnitAt(tx, ty);

                            if (hit != null) {
                                selectedUnit = hit;
                                selectedX = hit.x;
                                selectedY = hit.y;
                            }
                            else if (selectedUnit != null && selectedUnit.faction == FACTION_PLAYER) {
                                selectedUnit.targetX = tx;
                                selectedUnit.targetY = ty;
                                selectedUnit.lastMoveStepAt = 0L;
                                selectedX = tx;
                                selectedY = ty;
                            }
                            else {
                                selectedX = tx;
                                selectedY = ty;
                            }
                            invalidate();
                        }
                    }
                    return true;
            }
        }
        return true;
    }

    private static final class BattleUnit {
        int characterId;
        String name;
        int faction;
        int x;
        int y;
        int targetX;
        int targetY;
        int direction;
        int level;
        int unitImage;
        int moveFrame;
        long lastMoveStepAt;
        Bitmap[] idleFrames;
        Bitmap[] moveFrames;
    }
}
