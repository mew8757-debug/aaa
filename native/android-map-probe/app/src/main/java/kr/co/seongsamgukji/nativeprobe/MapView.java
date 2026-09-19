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
    private static final long MOVE_STEP_MS = 150L;

    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitLabelBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedTilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedUnitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint allyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enemyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dialogueBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dialogueNamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dialogueTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private final List<BattleUnit> units = new ArrayList<>();
    private final List<OpeningEvent> openingEvents = new ArrayList<>();
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

    private int openingIndex = 0;
    private long openingWaitUntil = 0L;
    private boolean openingFinished = false;
    private BattleUnit scriptedMovingUnit;
    private String dialogueSpeaker;
    private String dialogueText;
    private int musicTrack = -1;
    private int lastSound = -1;

    public MapView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);

        try {
            loadBattle(context);
        } catch (Exception e) {
            throw new RuntimeException("native battle load failed", e);
        }

        spritePaint.setAntiAlias(false);
        spritePaint.setFilterBitmap(false);

        gridPaint.setColor(0x26FFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        selectedTilePaint.setColor(0x55FFFF00);
        selectedTilePaint.setStyle(Paint.Style.FILL);

        selectedUnitPaint.setColor(0xFFFFFF00);
        selectedUnitPaint.setStyle(Paint.Style.STROKE);
        selectedUnitPaint.setStrokeWidth(4f);

        playerPaint.setColor(0xFF4CC9F0);
        playerPaint.setStyle(Paint.Style.STROKE);
        playerPaint.setStrokeWidth(3f);

        allyPaint.setColor(0xFF80ED99);
        allyPaint.setStyle(Paint.Style.STROKE);
        allyPaint.setStrokeWidth(3f);

        enemyPaint.setColor(0xFFFF595E);
        enemyPaint.setStyle(Paint.Style.STROKE);
        enemyPaint.setStrokeWidth(3f);

        overlayTextPaint.setColor(Color.WHITE);
        overlayTextPaint.setTextSize(25f);
        overlayTextPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        unitTextPaint.setColor(Color.WHITE);
        unitTextPaint.setTextSize(13f);
        unitTextPaint.setTextAlign(Paint.Align.CENTER);
        unitTextPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);

        unitLabelBackPaint.setColor(0xA0000000);
        unitLabelBackPaint.setStyle(Paint.Style.FILL);

        dialogueBackPaint.setColor(0xE6101720);
        dialogueBackPaint.setStyle(Paint.Style.FILL);

        dialogueNamePaint.setColor(0xFFFFDD6E);
        dialogueNamePaint.setTextSize(30f);
        dialogueNamePaint.setFakeBoldText(true);

        dialogueTextPaint.setColor(Color.WHITE);
        dialogueTextPaint.setTextSize(27f);

        scaleDetector = new ScaleGestureDetector(
                context,
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

        openingWaitUntil = SystemClock.uptimeMillis() + 350L;
        if (openingEvents.isEmpty()) {
            finishOpening();
        }
    }

    private void loadBattle(Context context) throws Exception {
        try (InputStream in = context.getAssets().open("map/m000.jpg")) {
            map = BitmapFactory.decodeStream(in);
        }
        if (map == null) {
            throw new IOException("m000.jpg decode failed");
        }

        palette = loadBytes(context, "sprites/spalet_000.bin");
        if (palette.length != 768) {
            throw new IOException("palette size=" + palette.length);
        }

        JSONObject battle = new JSONObject(new String(
                loadBytes(context, "battle/battle0.json"),
                StandardCharsets.UTF_8));

        JSONArray unitList = battle.getJSONArray("units");
        for (int i = 0; i < unitList.length(); i++) {
            JSONObject u = unitList.getJSONObject(i);
            BattleUnit unit = new BattleUnit(
                    u.getInt("characterId"),
                    u.getString("name"),
                    u.getInt("spriteId"),
                    u.getString("faction"),
                    u.optBoolean("scripted", false),
                    u.optBoolean("visible", !u.optBoolean("scripted", false)),
                    u.getInt("x"),
                    u.getInt("y"),
                    u.optInt("direction", 2));
            units.add(unit);
            ensureSprite(context, unit.spriteId);
        }

        JSONArray eventList = battle.optJSONArray("openingEvents");
        if (eventList != null) {
            for (int i = 0; i < eventList.length(); i++) {
                openingEvents.add(OpeningEvent.fromJson(eventList.getJSONObject(i)));
            }
        }
    }

    private void ensureSprite(Context context, int spriteId) throws IOException {
        if (idleSprites.containsKey(spriteId)) {
            return;
        }

        String stem = String.format("%03d", spriteId);
        idleSprites.put(
                spriteId,
                loadIndexedFrames(
                        context,
                        "sprites/unit_spc_" + stem + ".bin",
                        48,
                        48,
                        5));
        moveSprites.put(
                spriteId,
                loadIndexedFrames(
                        context,
                        "sprites/unit_mov_" + stem + ".bin",
                        48,
                        48,
                        11));
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

    private Bitmap[] loadIndexedFrames(
            Context context,
            String assetName,
            int width,
            int height,
            int frameCount) throws IOException {
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
                } else {
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
        if (map == null) {
            return;
        }

        float fit = Math.min((float) w / map.getWidth(), (float) h / map.getHeight());
        scale = Math.min(1f, fit);
        offsetX = (w - map.getWidth() * scale) * 0.5f;
        offsetY = (h - map.getHeight() * scale) * 0.5f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (map == null) {
            return;
        }

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
            canvas.drawRect(
                    new RectF(
                            selectedX * TILE,
                            selectedY * TILE,
                            (selectedX + 1) * TILE,
                            (selectedY + 1) * TILE),
                    selectedTilePaint);
        }

        for (BattleUnit unit : units) {
            if (unit.visible) {
                drawUnit(canvas, unit);
            }
        }

        canvas.restore();

        int playerCount = 0;
        int allyCount = 0;
        int enemyCount = 0;
        for (BattleUnit unit : units) {
            if (!unit.visible) {
                continue;
            }
            if ("player".equals(unit.faction)) {
                playerCount++;
            } else if ("ally".equals(unit.faction)) {
                allyCount++;
            } else if ("enemy".equals(unit.faction)) {
                enemyCount++;
            }
        }

        canvas.drawText(
                "Native v0.6  |  S_00 원본 배치 + 스프라이트 + 오프닝 이벤트"
                        + "  |  아군 " + playerCount
                        + " / 우군 " + allyCount
                        + " / 적군 " + enemyCount,
                22,
                36,
                overlayTextPaint);

        if (!openingFinished) {
            String status = dialogueText != null
                    ? "원본 대사 재생 중 · 화면 터치 = 다음 대사"
                    : "원본 S_00 이벤트 실행 중";
            if (musicTrack >= 0) {
                status += " · BGM " + musicTrack;
            }
            if (lastSound >= 0) {
                status += " · SFX " + lastSound;
            }
            canvas.drawText(status, 22, 70, overlayTextPaint);
        } else {
            canvas.drawText(
                    "오프닝 완료 · 아군 선택 → 빈 타일 이동 · 드래그 이동 · 두 손가락 확대",
                    22,
                    70,
                    overlayTextPaint);
        }

        if (openingFinished && selectedUnit != null) {
            canvas.drawText(
                    "선택: " + selectedUnit.name
                            + "  Data=" + selectedUnit.characterId
                            + "  S형상=" + selectedUnit.spriteId
                            + "  (" + selectedUnit.x + "," + selectedUnit.y + ")",
                    22,
                    104,
                    overlayTextPaint);
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
        Bitmap[] source;
        int frameIndex;

        if (now < unit.actionUntil) {
            source = idleSprites.get(unit.spriteId);
            frameIndex = source == null || source.length == 0
                    ? 0
                    : Math.abs(unit.actionFrame) % source.length;
        } else if (unit.isMoving()) {
            source = moveSprites.get(unit.spriteId);
            frameIndex = source == null || source.length == 0
                    ? 0
                    : Math.max(0, Math.min(unit.moveFrame, source.length - 1));
        } else {
            source = idleSprites.get(unit.spriteId);
            frameIndex = 0;
        }

        if (source == null || source.length == 0) {
            return;
        }

        Bitmap frame = source[frameIndex];
        float left = unit.x * TILE;
        float top = unit.y * TILE;

        canvas.drawBitmap(frame, left, top, spritePaint);

        Paint ring = "player".equals(unit.faction)
                ? playerPaint
                : ("ally".equals(unit.faction) ? allyPaint : enemyPaint);
        canvas.drawRect(left + 2, top + 2, left + TILE - 2, top + TILE - 2, ring);

        if (unit == selectedUnit) {
            canvas.drawRect(
                    left + 5,
                    top + 5,
                    left + TILE - 5,
                    top + TILE - 5,
                    selectedUnitPaint);
        }

        float centerX = left + TILE / 2f;
        float labelY = top + TILE - 3f;
        float textWidth = unitTextPaint.measureText(unit.name);
        canvas.drawRect(
                centerX - textWidth / 2f - 2f,
                labelY - 13f,
                centerX + textWidth / 2f + 2f,
                labelY + 2f,
                unitLabelBackPaint);
        canvas.drawText(unit.name, centerX, labelY, unitTextPaint);
    }

    private boolean updateMovement() {
        boolean anyMoving = false;
        long now = SystemClock.uptimeMillis();

        for (BattleUnit unit : units) {
            if (!unit.isMoving()) {
                unit.moveFrame = 0;
                continue;
            }

            anyMoving = true;
            if (now - unit.lastMoveStepAt < MOVE_STEP_MS) {
                continue;
            }
            unit.lastMoveStepAt = now;

            int oldX = unit.x;
            int oldY = unit.y;

            if (unit.x < unit.targetX) {
                unit.x++;
            } else if (unit.x > unit.targetX) {
                unit.x--;
            } else if (unit.y < unit.targetY) {
                unit.y++;
            } else if (unit.y > unit.targetY) {
                unit.y--;
            }

            if (unit.x > oldX) {
                unit.direction = 1;
            } else if (unit.x < oldX) {
                unit.direction = 3;
            } else if (unit.y > oldY) {
                unit.direction = 2;
            } else if (unit.y < oldY) {
                unit.direction = 0;
            }

            Bitmap[] frames = moveSprites.get(unit.spriteId);
            int count = frames == null || frames.length == 0 ? 1 : frames.length;
            unit.moveFrame = (unit.moveFrame + 1) % count;

            if (unit == selectedUnit && openingFinished) {
                selectedX = unit.x;
                selectedY = unit.y;
            }
        }

        return anyMoving;
    }

    private boolean pumpOpeningEvents() {
        if (openingFinished) {
            return false;
        }
        if (dialogueText != null) {
            return true;
        }

        long now = SystemClock.uptimeMillis();

        if (scriptedMovingUnit != null) {
            if (!scriptedMovingUnit.isMoving()) {
                scriptedMovingUnit = null;
                openingIndex++;
                openingWaitUntil = now + 90L;
            } else {
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
                    if (unit == null) {
                        openingIndex++;
                        break;
                    }

                    unit.visible = true;
                    if (event.x != Integer.MIN_VALUE) {
                        unit.targetX = event.x;
                    }
                    if (event.y != Integer.MIN_VALUE) {
                        unit.targetY = event.y;
                    }
                    if (event.direction >= 0) {
                        unit.direction = event.direction;
                    }
                    unit.lastMoveStepAt = 0L;

                    if (unit.isMoving()) {
                        scriptedMovingUnit = unit;
                        return true;
                    }

                    openingIndex++;
                    openingWaitUntil = now + 90L;
                    return true;
                }

                case "reveal": {
                    BattleUnit unit = findUnitByCharacterId(event.characterId);
                    if (unit != null) {
                        unit.visible = true;
                    }
                    openingIndex++;
                    openingWaitUntil = now + 120L;
                    return true;
                }

                case "hide":
                case "retreat": {
                    BattleUnit unit = findUnitByCharacterId(event.characterId);
                    if (unit != null) {
                        unit.visible = false;
                        unit.targetX = unit.x;
                        unit.targetY = unit.y;
                    }
                    openingIndex++;
                    openingWaitUntil = now + 140L;
                    return true;
                }

                case "turn": {
                    BattleUnit unit = findUnitByCharacterId(event.characterId);
                    if (unit != null) {
                        int direction = event.direction;
                        if (direction < 0 && event.targetId >= 0) {
                            BattleUnit target = findUnitByCharacterId(event.targetId);
                            if (target != null) {
                                direction = directionToward(unit, target);
                            }
                        }
                        if (direction >= 0) {
                            unit.direction = direction;
                        }
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
        invalidate();
    }

    private void selectFirstPlayer() {
        for (BattleUnit unit : units) {
            if (unit.isPlayer() && unit.visible) {
                selectedUnit = unit;
                selectedX = unit.x;
                selectedY = unit.y;
                return;
            }
        }
    }

    private BattleUnit findUnitAt(int tx, int ty) {
        for (int i = units.size() - 1; i >= 0; i--) {
            BattleUnit unit = units.get(i);
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

    private boolean occupied(int tx, int ty, BattleUnit except) {
        for (BattleUnit unit : units) {
            if (unit != except && unit.visible && unit.x == tx && unit.y == ty) {
                return true;
            }
        }
        return false;
    }

    private void advanceDialogue() {
        if (dialogueText == null) {
            return;
        }
        dialogueSpeaker = null;
        dialogueText = null;
        openingIndex++;
        openingWaitUntil = SystemClock.uptimeMillis() + 80L;
        invalidate();
    }

    private void drawDialogueBox(Canvas canvas) {
        float left = 24f;
        float right = getWidth() - 24f;
        float bottom = getHeight() - 22f;
        float top = Math.max(130f, bottom - 190f);

        canvas.drawRoundRect(
                new RectF(left, top, right, bottom),
                16f,
                16f,
                dialogueBackPaint);

        float x = left + 22f;
        float y = top + 39f;
        String speaker = dialogueSpeaker == null || dialogueSpeaker.isEmpty()
                ? "대사"
                : dialogueSpeaker;
        canvas.drawText(speaker, x, y, dialogueNamePaint);

        y += 42f;
        drawWrappedText(
                canvas,
                dialogueText,
                x,
                y,
                right - left - 44f,
                35f);

        dialogueTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("▼ 터치", right - 18f, bottom - 13f, dialogueTextPaint);
        dialogueTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawWrappedText(
            Canvas canvas,
            String text,
            float x,
            float y,
            float maxWidth,
            float lineHeight) {
        if (text == null) {
            return;
        }

        int maxLines = 3;
        int lines = 0;
        String[] paragraphs = text.replace("\r", "").split("\n", -1);

        for (String paragraph : paragraphs) {
            if (lines >= maxLines) {
                break;
            }

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
                    if (lines >= maxLines) {
                        return;
                    }
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
                    float moved = Math.abs(event.getX() - downX)
                            + Math.abs(event.getY() - downY);

                    if (!openingFinished) {
                        if (moved < 24f && dialogueText != null) {
                            advanceDialogue();
                        }
                        return true;
                    }

                    if (moved < 24f) {
                        float mx = (event.getX() - offsetX) / scale;
                        float my = (event.getY() - offsetY) / scale;
                        int tx = (int) (mx / TILE);
                        int ty = (int) (my / TILE);

                        int cols = map.getWidth() / (int) TILE;
                        int rows = map.getHeight() / (int) TILE;
                        if (tx >= 0 && ty >= 0 && tx < cols && ty < rows) {
                            selectedX = tx;
                            selectedY = ty;
                            BattleUnit hit = findUnitAt(tx, ty);

                            if (hit != null) {
                                selectedUnit = hit;
                                selectedX = hit.x;
                                selectedY = hit.y;
                            } else if (selectedUnit != null
                                    && selectedUnit.isPlayer()
                                    && !occupied(tx, ty, selectedUnit)) {
                                selectedUnit.targetX = tx;
                                selectedUnit.targetY = ty;
                                selectedUnit.lastMoveStepAt = 0L;
                            }
                            invalidate();
                        }
                    }
                    return true;

                default:
                    break;
            }
        }

        return true;
    }
}
