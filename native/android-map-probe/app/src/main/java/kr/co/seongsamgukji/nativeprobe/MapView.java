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
    private static final long MOVE_STEP_MS = 150L;

    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedTilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint allyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enemyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    private final List<BattleUnit> units = new ArrayList<>();
    private final Map<Integer, Bitmap[]> idleSprites = new HashMap<>();
    private final Map<Integer, Bitmap[]> moveSprites = new HashMap<>();

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
    private long lastMoveStepAt = 0L;

    public MapView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);

        try {
            loadBattle(context);
        }
        catch (Exception e) {
            throw new RuntimeException("native battle load failed", e);
        }

        spritePaint.setAntiAlias(false);
        spritePaint.setFilterBitmap(false);

        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        selectedTilePaint.setColor(0x66FFFF00);
        selectedTilePaint.setStyle(Paint.Style.FILL);

        playerPaint.setColor(0xFF4CC9F0);
        playerPaint.setStyle(Paint.Style.STROKE);
        playerPaint.setStrokeWidth(3f);

        allyPaint.setColor(0xFF80ED99);
        allyPaint.setStyle(Paint.Style.STROKE);
        allyPaint.setStrokeWidth(3f);

        enemyPaint.setColor(0xFFFF595E);
        enemyPaint.setStyle(Paint.Style.STROKE);
        enemyPaint.setStrokeWidth(3f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(27f);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        smallTextPaint.setColor(Color.WHITE);
        smallTextPaint.setTextSize(13f);
        smallTextPaint.setTextAlign(Paint.Align.CENTER);
        smallTextPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);

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
        try (InputStream in = context.getAssets().open("map/m000.jpg")) {
            map = BitmapFactory.decodeStream(in);
        }
        if (map == null) throw new IOException("m000.jpg decode failed");

        palette = loadBytes(context, "sprites/spalet_000.bin");
        if (palette.length != 768) throw new IOException("palette size=" + palette.length);

        JSONObject battle = new JSONObject(new String(
                loadBytes(context, "battle/battle0.json"), StandardCharsets.UTF_8));
        JSONArray list = battle.getJSONArray("units");

        for (int i = 0; i < list.length(); i++) {
            JSONObject u = list.getJSONObject(i);
            BattleUnit unit = new BattleUnit(
                    u.getInt("characterId"),
                    u.getString("name"),
                    u.getInt("spriteId"),
                    u.getString("faction"),
                    u.optBoolean("scripted", false),
                    u.getInt("x"),
                    u.getInt("y"),
                    u.optInt("direction", 2));
            units.add(unit);
            ensureSprite(context, unit.spriteId);
        }
    }

    private void ensureSprite(Context context, int spriteId) throws IOException {
        if (!idleSprites.containsKey(spriteId)) {
            String stem = String.format("%03d", spriteId);
            idleSprites.put(spriteId,
                    loadIndexedFrames(context, "sprites/unit_spc_" + stem + ".bin", 48, 48, 5));
            moveSprites.put(spriteId,
                    loadIndexedFrames(context, "sprites/unit_mov_" + stem + ".bin", 48, 48, 11));
        }
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

        updateMovement();

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
                    (selectedY + 1) * TILE), selectedTilePaint);
        }

        for (BattleUnit unit : units) {
            drawUnit(canvas, unit);
        }

        canvas.restore();

        canvas.drawText("Native v0.4  |  S_00 실제 배치 + 인물별 S형상", 24, 38, textPaint);
        canvas.drawText("유비·관우·장비 선택 → 빈 타일 이동 / 드래그 이동 / 두 손가락 확대", 24, 72, textPaint);

        String detail;
        if (selectedUnit != null) {
            detail = selectedUnit.name + "  Data=" + selectedUnit.characterId
                    + "  S형상=" + selectedUnit.spriteId
                    + "  (" + selectedUnit.x + "," + selectedUnit.y + ")";
        }
        else {
            detail = "파랑=아군 조작  초록=우군  빨강=적군  반투명=시나리오 대기/숨김";
        }
        canvas.drawText(detail, 24, 106, textPaint);

        if (hasMovingUnit()) postInvalidateDelayed(35L);
    }

    private void drawUnit(Canvas canvas, BattleUnit unit) {
        Bitmap[] source = unit.isMoving() ? moveSprites.get(unit.spriteId) : idleSprites.get(unit.spriteId);
        if (source == null || source.length == 0) return;

        int frameIndex = unit.isMoving()
                ? Math.max(0, Math.min(unit.moveFrame, source.length - 1))
                : 0;
        Bitmap frame = source[frameIndex];

        float left = unit.x * TILE;
        float top = unit.y * TILE;

        int oldAlpha = spritePaint.getAlpha();
        spritePaint.setAlpha(unit.scripted ? 100 : 255);
        canvas.drawBitmap(frame, left, top, spritePaint);
        spritePaint.setAlpha(oldAlpha);

        Paint ring = unit.isPlayer() ? playerPaint
                : ("ally".equals(unit.faction) ? allyPaint : enemyPaint);
        int ringAlpha = ring.getAlpha();
        ring.setAlpha(unit.scripted ? 105 : 255);
        canvas.drawRect(left + 2, top + 2, left + TILE - 2, top + TILE - 2, ring);
        ring.setAlpha(ringAlpha);

        if (unit == selectedUnit) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFF00);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4f);
            canvas.drawRect(left + 5, top + 5, left + TILE - 5, top + TILE - 5, p);
        }

        smallTextPaint.setAlpha(unit.scripted ? 120 : 255);
        canvas.drawText(unit.name, left + TILE / 2f, top + TILE - 3f, smallTextPaint);
        smallTextPaint.setAlpha(255);
    }

    private void updateMovement() {
        long now = SystemClock.uptimeMillis();
        if (now - lastMoveStepAt < MOVE_STEP_MS) return;

        for (BattleUnit unit : units) {
            if (!unit.isMoving()) {
                unit.moveFrame = 0;
                continue;
            }

            lastMoveStepAt = now;
            if (unit.x < unit.targetX) unit.x++;
            else if (unit.x > unit.targetX) unit.x--;
            else if (unit.y < unit.targetY) unit.y++;
            else if (unit.y > unit.targetY) unit.y--;

            Bitmap[] frames = moveSprites.get(unit.spriteId);
            int count = frames == null ? 1 : frames.length;
            unit.moveFrame = (unit.moveFrame + 1) % count;
            break;
        }
    }

    private boolean hasMovingUnit() {
        for (BattleUnit u : units) if (u.isMoving()) return true;
        return false;
    }

    private BattleUnit unitAt(int tx, int ty) {
        for (int i = units.size() - 1; i >= 0; i--) {
            BattleUnit u = units.get(i);
            if (u.x == tx && u.y == ty && !u.scripted) return u;
        }
        return null;
    }

    private boolean occupied(int tx, int ty, BattleUnit except) {
        for (BattleUnit u : units) {
            if (u != except && !u.scripted && u.x == tx && u.y == ty) return true;
        }
        return false;
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
                    float moved = Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY);
                    if (moved < 24f) {
                        float mx = (event.getX() - offsetX) / scale;
                        float my = (event.getY() - offsetY) / scale;
                        int tx = (int) (mx / TILE);
                        int ty = (int) (my / TILE);

                        if (tx >= 0 && ty >= 0 && tx < map.getWidth() / 48 && ty < map.getHeight() / 48) {
                            selectedX = tx;
                            selectedY = ty;
                            BattleUnit hit = unitAt(tx, ty);

                            if (hit != null && hit.isPlayer()) {
                                selectedUnit = hit;
                            }
                            else if (selectedUnit != null && !occupied(tx, ty, selectedUnit)) {
                                selectedUnit.targetX = tx;
                                selectedUnit.targetY = ty;
                                selectedUnit.moveFrame = 0;
                                lastMoveStepAt = 0L;
                            }
                            invalidate();
                        }
                    }
                    return true;
            }
        }
        return true;
    }
}
