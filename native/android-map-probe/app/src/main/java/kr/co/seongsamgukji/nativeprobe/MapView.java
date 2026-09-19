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
    private static final long MOVE_STEP_MS = 150L;

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
    private final Paint dialogueBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dialogueNamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dialogueTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    private final List<BattleUnit> units = new ArrayList<>();
    private final List<OpeningEvent> openingEvents = new ArrayList<>();
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

    private int openingIndex = 0;
    private boolean openingFinished = false;
    private long openingWaitUntil = 0L;
    private BattleUnit scriptedMovingUnit;
    private String dialogueSpeaker;
    private String dialogueText;
    private int musicTrack = -1;
    private int lastSound = -1;

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

        gridPaint.setColor(0x2AFFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        selectedPaint.setColor(0x66FFFF00);
        selectedPaint.setStyle(Paint.Style.FILL);

        hpBackPaint.setColor(0xD0000000);
        hpPaint.setColor(0xFF55FF55);

        labelBackPaint.setColor(0xAA000000);

        overlayTextPaint.setColor(Color.WHITE);
        overlayTextPaint.setTextSize(25f);
        overlayTextPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        unitTextPaint.setColor(Color.WHITE);
        unitTextPaint.setTextSize(12f);
        unitTextPaint.setTextAlign(Paint.Align.CENTER);
        unitTextPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);

        dialogueBackPaint.setColor(0xE61A1A1A);
        dialogueNamePaint.setColor(0xFFFFD86B);
        dialogueNamePaint.setTextSize(30f);
        dialogueNamePaint.setFakeBoldText(true);
        dialogueTextPaint.setColor(Color.WHITE);
        dialogueTextPaint.setTextSize(27f);

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
            unit.visible = item.optBoolean("visible", true);

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

        JSONArray events = root.optJSONArray("openingEvents");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject item = events.getJSONObject(i);
                OpeningEvent event = new OpeningEvent();
                event.type = item.getString("type");
                event.characterId = item.optInt("characterId", -1);
                event.targetId = item.optInt("targetId", -1);
                event.x = item.optInt("x", Integer.MIN_VALUE);
                event.y = item.optInt("y", Integer.MIN_VALUE);
                event.direction = item.optInt("direction", -1);
                event.value = item.optInt("value", 0);
                event.speaker = item.optString("speaker", "");
                event.text = item.optString("text", "");
                openingEvents.add(event);
            }
        }

        openingFinished = openingEvents.isEmpty();
        selectFirstPlayer();
        if (!openingFinished) {
            openingWaitUntil = SystemClock.uptimeMillis() + 450L;
        }
    }

    private void selectFirstPlayer() {
        for (BattleUnit unit : units) {
            if (unit.faction == FACTION_PLAYER && unit.visible) {
                selectedUnit = unit;
                selectedX = unit.x;
                selectedY = unit.y;
                return;
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
        boolean openingBusy = pumpOpeningEvents();

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

        if (openingFinished && selectedX >= 0 && selectedY >= 0) {
            canvas.drawRect(new RectF(
                    selectedX * TILE,
                    selectedY * TILE,
                    (selectedX + 1) * TILE,
                    (selectedY + 1) * TILE), selectedPaint);
        }

        for (BattleUnit unit : units) {
            if (unit.visible) {
                drawUnit(canvas, unit);
            }
        }

        canvas.restore();

        int enemies = 0;
        int players = 0;
        int allies = 0;
        for (BattleUnit unit : units) {
            if (!unit.visible) continue;
            if (unit.faction == FACTION_ENEMY) enemies++;
            else if (unit.faction == FACTION_ALLY) allies++;
            else if (unit.faction == FACTION_PLAYER) players++;
        }

        canvas.drawText(
                "Native v0.5  |  S_00 원본 오프닝 이벤트  |  아군 " + players
                        + " / 우군 " + allies + " / 적군 " + enemies,
                22, 36, overlayTextPaint);

        if (!openingFinished) {
            String status = dialogueText != null
                    ? "원본 대사 재생 중 · 화면 터치 = 다음 대사"
                    : "원본 S_00 이벤트 실행 중";
            if (musicTrack >= 0) status += " · BGM " + musicTrack;
            canvas.drawText(status, 22, 70, overlayTextPaint);
        }
        else {
            canvas.drawText(
                    "오프닝 재생 완료 · 유닛 터치=선택 · 빈 타일 터치=선택한 아군 이동 · 드래그/핀치",
                    22, 70, overlayTextPaint);
        }

        if (openingFinished && selectedUnit != null) {
            canvas.drawText(
                    "선택: " + selectedUnit.name + "  ID " + selectedUnit.characterId
                            + "  Unit #" + selectedUnit.unitImage
                            + "  (" + selectedUnit.x + "," + selectedUnit.y + ")",
                    22, 104, overlayTextPaint);
        }

        if (dialogueText != null) {
            drawDialogueBox(canvas);
        }

        if (moving || openingBusy || !openingFinished) {
            postInvalidateDelayed(35L);
        }
    }

    private void drawUnit(Canvas canvas, BattleUnit unit) {
        long now = SystemClock.uptimeMillis();
        boolean moving = unit.x != unit.targetX || unit.y != unit.targetY;

        Bitmap[] source;
        int frameIndex;
        if (now < unit.actionUntil && unit.idleFrames.length > 0) {
            source = unit.idleFrames;
            frameIndex = Math.abs(unit.actionFrame) % source.length;
        }
        else if (moving) {
            source = unit.moveFrames;
            frameIndex = Math.max(0, Math.min(unit.moveFrame, source.length - 1));
        }
        else {
            source = unit.idleFrames;
            frameIndex = 0;
        }

        Bitmap frame = source[frameIndex];
        float spriteX = unit.x * TILE;
        float spriteY = unit.y * TILE;
        canvas.drawBitmap(frame, spriteX, spriteY, spritePaint);

        float hpY = spriteY - 4f;
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
        canvas.drawRect(
                cx - tw * 0.5f - 3f,
                labelY - 13f,
                cx + tw * 0.5f + 3f,
                labelY + 3f,
                labelBackPaint);
        canvas.drawText(label, cx, labelY, unitTextPaint);
    }

    private void drawDialogueBox(Canvas canvas) {
        float left = 24f;
        float right = getWidth() - 24f;
        float bottom = getHeight() - 22f;
        float top = Math.max(130f, bottom - 190f);

        canvas.drawRoundRect(new RectF(left, top, right, bottom), 16f, 16f, dialogueBackPaint);

        float x = left + 22f;
        float y = top + 39f;
        String speaker = dialogueSpeaker == null || dialogueSpeaker.isEmpty() ? "대사" : dialogueSpeaker;
        canvas.drawText(speaker, x, y, dialogueNamePaint);

        y += 42f;
        drawWrappedText(canvas, dialogueText, x, y, right - left - 44f, 35f);

        dialogueTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("▼ 터치", right - 18f, bottom - 13f, dialogueTextPaint);
        dialogueTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawWrappedText(Canvas canvas, String text, float x, float y, float maxWidth, float lineHeight) {
        if (text == null) return;

        int maxLines = 3;
        int lines = 0;
        String[] paragraphs = text.replace("\r", "").split("\n", -1);

        for (String paragraph : paragraphs) {
            if (lines >= maxLines) break;
            if (paragraph.isEmpty()) {
                y += lineHeight;
                lines++;
                continue;
            }

            StringBuilder line = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                char ch = paragraph.charAt(i);
                String candidate = line.toString() + ch;
                if (dialogueTextPaint.measureText(candidate) > maxWidth && line.length() > 0) {
                    canvas.drawText(line.toString(), x, y, dialogueTextPaint);
                    y += lineHeight;
                    lines++;
                    if (lines >= maxLines) return;
                    line.setLength(0);
                }
                line.append(ch);
            }

            if (line.length() > 0 && lines < maxLines) {
                canvas.drawText(line.toString(), x, y, dialogueTextPaint);
                y += lineHeight;
                lines++;
            }
        }
    }

    private boolean pumpOpeningEvents() {
        if (openingFinished) return false;
        if (dialogueText != null) return true;

        long now = SystemClock.uptimeMillis();

        if (scriptedMovingUnit != null) {
            if (scriptedMovingUnit.x == scriptedMovingUnit.targetX
                    && scriptedMovingUnit.y == scriptedMovingUnit.targetY) {
                scriptedMovingUnit = null;
                openingIndex++;
                openingWaitUntil = now + 90L;
            }
            else {
                return true;
            }
        }

        if (now < openingWaitUntil) {
            return true;
        }

        while (openingIndex < openingEvents.size()) {
            OpeningEvent event = openingEvents.get(openingIndex);

            switch (event.type) {
                case "dialogue":
                    dialogueSpeaker = event.speaker;
                    dialogueText = event.text;
                    return true;

                case "delay":
                    openingIndex++;
                    openingWaitUntil = now + Math.max(100L, event.value * 80L);
                    return true;

                case "move": {
                    BattleUnit unit = findUnitByCharacterId(event.characterId);
                    if (unit != null) {
                        unit.visible = true;
                        if (event.x != Integer.MIN_VALUE) unit.targetX = event.x;
                        if (event.y != Integer.MIN_VALUE) unit.targetY = event.y;
                        if (event.direction >= 0) unit.direction = event.direction;
                        unit.lastMoveStepAt = 0L;
                        scriptedMovingUnit = unit;
                        return true;
                    }
                    openingIndex++;
                    break;
                }

                case "reveal": {
                    BattleUnit unit = findUnitByCharacterId(event.characterId);
                    if (unit != null) unit.visible = true;
                    openingIndex++;
                    openingWaitUntil = now + 120L;
                    return true;
                }

                case "hide":
                case "retreat": {
                    BattleUnit unit = findUnitByCharacterId(event.characterId);
                    if (unit != null) unit.visible = false;
                    openingIndex++;
                    openingWaitUntil = now + 140L;
                    return true;
                }

                case "turn": {
                    BattleUnit unit = findUnitByCharacterId(event.characterId);
                    if (unit != null) {
                        int dir = event.direction;
                        if (dir < 0 && event.targetId >= 0) {
                            BattleUnit target = findUnitByCharacterId(event.targetId);
                            if (target != null) {
                                dir = directionToward(unit, target);
                            }
                        }
                        if (dir >= 0) unit.direction = dir;
                    }
                    openingIndex++;
                    openingWaitUntil = now + 130L;
                    return true;
                }

                case "action": {
                    BattleUnit unit = findUnitByCharacterId(event.characterId);
                    if (unit != null) {
                        unit.actionFrame = event.value;
                        unit.actionUntil = now + 420L;
                    }
                    openingIndex++;
                    openingWaitUntil = now + 420L;
                    return true;
                }

                case "music":
                    musicTrack = event.value;
                    openingIndex++;
                    break;

                case "sound":
                    lastSound = event.value;
                    openingIndex++;
                    break;

                case "end":
                    openingIndex = openingEvents.size();
                    finishOpening();
                    return false;

                default:
                    openingIndex++;
                    break;
            }
        }

        finishOpening();
        return false;
    }

    private int directionToward(BattleUnit from, BattleUnit to) {
        int dx = to.x - from.x;
        int dy = to.y - from.y;
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? 1 : 3;
        }
        return dy >= 0 ? 2 : 0;
    }

    private void finishOpening() {
        openingFinished = true;
        dialogueSpeaker = null;
        dialogueText = null;
        scriptedMovingUnit = null;
        selectFirstPlayer();
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

            if (unit == selectedUnit && openingFinished) {
                selectedX = unit.x;
                selectedY = unit.y;
            }
        }

        return anyMoving;
    }

    private BattleUnit findUnitAt(int tx, int ty) {
        for (BattleUnit unit : units) {
            if (unit.visible && unit.x == tx && unit.y == ty) {
                return unit;
            }
        }
        return null;
    }

    private BattleUnit findUnitByCharacterId(int characterId) {
        for (BattleUnit unit : units) {
            if (unit.characterId == characterId) {
                return unit;
            }
        }
        return null;
    }

    private void advanceDialogue() {
        if (dialogueText == null) return;
        dialogueSpeaker = null;
        dialogueText = null;
        openingIndex++;
        openingWaitUntil = SystemClock.uptimeMillis() + 80L;
        invalidate();
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

                    if (!openingFinished) {
                        if (move < 24f && dialogueText != null) {
                            advanceDialogue();
                        }
                        return true;
                    }

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
        int actionFrame;
        long actionUntil;
        long lastMoveStepAt;
        boolean visible;
        Bitmap[] idleFrames;
        Bitmap[] moveFrames;
    }

    private static final class OpeningEvent {
        String type;
        int characterId;
        int targetId;
        int x;
        int y;
        int direction;
        int value;
        String speaker;
        String text;
    }
}
