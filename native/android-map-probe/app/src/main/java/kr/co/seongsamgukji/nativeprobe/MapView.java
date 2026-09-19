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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class MapView extends View {
    private static final float TILE = 48f;
    private static final long MOVE_STEP_MS = 150L;
    private static final long ATTACK_FRAME_MS = 105L;
    private static final long ATTACK_ANIMATION_MS = 480L;
    private static final int IMPASSABLE = Integer.MAX_VALUE / 4;

    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitLabelBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reachablePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attackTargetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private final Map<Integer, Bitmap[]> attackSprites = new HashMap<>();

    private Bitmap map;
    private byte[] palette;
    private byte[] terrainCells;
    private byte[] movementCosts;

    private int mapCols;
    private int mapRows;
    private int terrainTypeCount;
    private int movementCostFamilyCount;

    private int[] reachableBest;
    private int[] reachablePrev;

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

    private String lastCombatMessage;
    private long combatMessageUntil;

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

        reachablePaint.setColor(0x553C9DFF);
        reachablePaint.setStyle(Paint.Style.FILL);

        attackTargetPaint.setColor(0x66FF3030);
        attackTargetPaint.setStyle(Paint.Style.FILL);

        selectedTilePaint.setColor(0x66FFFF00);
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
        overlayTextPaint.setTextSize(23f);
        overlayTextPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);

        unitTextPaint.setColor(Color.WHITE);
        unitTextPaint.setTextSize(12f);
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

        mapCols = battle.optInt("widthTiles", map.getWidth() / (int) TILE);
        mapRows = battle.optInt("heightTiles", map.getHeight() / (int) TILE);
        terrainTypeCount = battle.optInt("terrainTypeCount", 30);
        movementCostFamilyCount = battle.optInt("movementCostFamilyCount", 40);

        String terrainFile = battle.optString("terrainFile", "terrain0.bin");
        String movementCostFile = battle.optString(
                "movementCostFile",
                "movement_costs.bin");

        terrainCells = loadBytes(context, "battle/" + terrainFile);
        movementCosts = loadBytes(context, "battle/" + movementCostFile);

        if (terrainCells.length != mapCols * mapRows) {
            throw new IOException(
                    "terrain size=" + terrainCells.length
                            + " expected=" + (mapCols * mapRows));
        }
        if (movementCosts.length
                != movementCostFamilyCount * terrainTypeCount) {
            throw new IOException(
                    "movement cost size=" + movementCosts.length
                            + " expected="
                            + (movementCostFamilyCount * terrainTypeCount));
        }

        JSONArray unitList = battle.getJSONArray("units");
        for (int i = 0; i < unitList.length(); i++) {
            JSONObject u = unitList.getJSONObject(i);
            BattleUnit unit = new BattleUnit(
                    u.getInt("characterId"),
                    u.getString("name"),
                    u.getInt("spriteId"),
                    u.optInt("jobId", 0),
                    u.optInt("jobFamily", 0),
                    u.optInt("movePoints", 1),
                    u.optInt("attackRangeId", 0),
                    u.optInt("level", 1),
                    u.optInt("hpMax", 1),
                    u.optInt("attack", 0),
                    u.optInt("defense", 0),
                    u.getString("faction"),
                    u.optBoolean("scripted", false),
                    u.optBoolean(
                            "visible",
                            !u.optBoolean("scripted", false)),
                    u.getInt("x"),
                    u.getInt("y"),
                    u.optInt("direction", 2));
            units.add(unit);
            ensureSprite(context, unit.spriteId);
        }

        JSONArray eventList = battle.optJSONArray("openingEvents");
        if (eventList != null) {
            for (int i = 0; i < eventList.length(); i++) {
                openingEvents.add(
                        OpeningEvent.fromJson(eventList.getJSONObject(i)));
            }
        }
    }

    private void ensureSprite(Context context, int spriteId)
            throws IOException {
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
        attackSprites.put(
                spriteId,
                loadIndexedFrames(
                        context,
                        "sprites/unit_atk_" + stem + ".bin",
                        64,
                        64,
                        12));
    }

    private byte[] loadBytes(Context context, String assetName)
            throws IOException {
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
            throw new IOException(
                    assetName + " payload too small: " + raw.length);
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

            Bitmap bitmap = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888);
            bitmap.setPixels(
                    pixels,
                    0,
                    width,
                    0,
                    0,
                    width,
                    height);
            frames[f] = bitmap;
        }

        return frames;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (map == null) {
            return;
        }

        float fit = Math.min(
                (float) w / map.getWidth(),
                (float) h / map.getHeight());
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
        long now = SystemClock.uptimeMillis();

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        canvas.drawBitmap(map, 0, 0, mapPaint);

        drawReachableTiles(canvas);
        drawAttackTargets(canvas);

        for (int x = 0; x <= mapCols; x++) {
            canvas.drawLine(
                    x * TILE,
                    0,
                    x * TILE,
                    mapRows * TILE,
                    gridPaint);
        }
        for (int y = 0; y <= mapRows; y++) {
            canvas.drawLine(
                    0,
                    y * TILE,
                    mapCols * TILE,
                    y * TILE,
                    gridPaint);
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
            if (unit.visible && unit.isAlive()) {
                drawUnit(canvas, unit, now);
            }
        }

        canvas.restore();

        int playerCount = 0;
        int allyCount = 0;
        int enemyCount = 0;
        for (BattleUnit unit : units) {
            if (!unit.visible || !unit.isAlive()) {
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
                "Native v0.8 | 지형 이동 + 원본 공격모션 + 물리 공격"
                        + " | 아군 " + playerCount
                        + " / 우군 " + allyCount
                        + " / 적군 " + enemyCount,
                22,
                34,
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
            canvas.drawText(status, 22, 65, overlayTextPaint);
        } else {
            canvas.drawText(
                    "아군 선택 → 파란 타일 이동 · 붉은 적 터치 공격 · 공격 후 해당 유닛 행동 종료",
                    22,
                    65,
                    overlayTextPaint);
        }

        if (openingFinished && selectedUnit != null) {
            String terrainInfo = "";
            if (inBounds(selectedX, selectedY)) {
                int terrainId = terrainAt(selectedX, selectedY);
                int cost = movementCost(
                        selectedUnit,
                        selectedX,
                        selectedY);
                terrainInfo = " · 지형 " + terrainId
                        + " / 이동비용 "
                        + (cost >= IMPASSABLE ? "불가" : cost);
            }

            String rangeInfo = supportsAttackRange(selectedUnit)
                    ? String.valueOf(selectedUnit.attackRangeId)
                    : selectedUnit.attackRangeId + "(미지원)";

            canvas.drawText(
                    "선택: " + selectedUnit.name
                            + " Lv." + selectedUnit.level
                            + " HP " + selectedUnit.hp
                            + "/" + selectedUnit.maxHp
                            + " ATK " + selectedUnit.attack
                            + " DEF " + selectedUnit.defense
                            + " · 범위 " + rangeInfo
                            + terrainInfo,
                    22,
                    96,
                    overlayTextPaint);
        }

        if (lastCombatMessage != null && now < combatMessageUntil) {
            canvas.drawText(
                    lastCombatMessage,
                    22,
                    127,
                    overlayTextPaint);
        }

        if (dialogueText != null) {
            drawDialogueBox(canvas);
        }

        if (moving
                || openingBusy
                || !openingFinished
                || hasActiveAttackAnimation(now)
                || (lastCombatMessage != null && now < combatMessageUntil)) {
            postInvalidateDelayed(35L);
        }
    }

    private void drawReachableTiles(Canvas canvas) {
        if (!openingFinished
                || selectedUnit == null
                || !selectedUnit.isPlayer()
                || !selectedUnit.isAlive()
                || selectedUnit.acted
                || selectedUnit.isMoving()
                || reachableBest == null) {
            return;
        }

        int origin = tileIndex(selectedUnit.x, selectedUnit.y);
        for (int index = 0; index < reachableBest.length; index++) {
            if (index == origin) {
                continue;
            }
            int cost = reachableBest[index];
            if (cost < 0 || cost > selectedUnit.movePoints) {
                continue;
            }

            int x = index % mapCols;
            int y = index / mapCols;
            canvas.drawRect(
                    x * TILE + 1,
                    y * TILE + 1,
                    (x + 1) * TILE - 1,
                    (y + 1) * TILE - 1,
                    reachablePaint);
        }
    }

    private void drawAttackTargets(Canvas canvas) {
        if (!openingFinished
                || selectedUnit == null
                || !selectedUnit.isPlayer()
                || !selectedUnit.isAlive()
                || selectedUnit.acted
                || selectedUnit.isMoving()
                || !supportsAttackRange(selectedUnit)) {
            return;
        }

        for (BattleUnit unit : units) {
            if (!unit.visible
                    || !unit.isAlive()
                    || !unit.isEnemy()
                    || !isInAttackRange(selectedUnit, unit)) {
                continue;
            }

            canvas.drawRect(
                    unit.x * TILE + 1,
                    unit.y * TILE + 1,
                    (unit.x + 1) * TILE - 1,
                    (unit.y + 1) * TILE - 1,
                    attackTargetPaint);
        }
    }

    private void drawUnit(Canvas canvas, BattleUnit unit, long now) {
        Bitmap[] source;
        int frameIndex;
        float left = unit.x * TILE;
        float top = unit.y * TILE;

        if (now < unit.attackUntil) {
            source = attackSprites.get(unit.spriteId);
            int group = attackFrameGroup(unit.direction);
            int local = (int) Math.min(
                    3L,
                    Math.max(
                            0L,
                            (now - unit.attackStartedAt)
                                    / ATTACK_FRAME_MS));
            frameIndex = group + local;
            left -= 8f;
            top -= 8f;
        } else if (now < unit.actionUntil) {
            source = idleSprites.get(unit.spriteId);
            frameIndex = source == null || source.length == 0
                    ? 0
                    : Math.floorMod(unit.actionFrame, source.length);
        } else if (unit.isMoving()) {
            source = moveSprites.get(unit.spriteId);
            frameIndex = source == null || source.length == 0
                    ? 0
                    : Math.max(
                            0,
                            Math.min(unit.moveFrame, source.length - 1));
        } else {
            source = idleSprites.get(unit.spriteId);
            frameIndex = 0;
        }

        if (source == null || source.length == 0) {
            return;
        }

        frameIndex = Math.max(0, Math.min(frameIndex, source.length - 1));
        canvas.drawBitmap(source[frameIndex], left, top, spritePaint);

        float tileLeft = unit.x * TILE;
        float tileTop = unit.y * TILE;
        Paint ring = "player".equals(unit.faction)
                ? playerPaint
                : ("ally".equals(unit.faction) ? allyPaint : enemyPaint);
        canvas.drawRect(
                tileLeft + 2,
                tileTop + 2,
                tileLeft + TILE - 2,
                tileTop + TILE - 2,
                ring);

        if (unit == selectedUnit) {
            canvas.drawRect(
                    tileLeft + 5,
                    tileTop + 5,
                    tileLeft + TILE - 5,
                    tileTop + TILE - 5,
                    selectedUnitPaint);
        }

        float centerX = tileLeft + TILE / 2f;
        float labelY = tileTop + TILE - 3f;
        String label = unit.name + " " + unit.hp + "/" + unit.maxHp;
        float textWidth = unitTextPaint.measureText(label);
        canvas.drawRect(
                centerX - textWidth / 2f - 2f,
                labelY - 13f,
                centerX + textWidth / 2f + 2f,
                labelY + 2f,
                unitLabelBackPaint);
        canvas.drawText(label, centerX, labelY, unitTextPaint);
    }

    private int attackFrameGroup(int direction) {
        if (direction == 0) {
            return 4;
        }
        if (direction == 2) {
            return 0;
        }
        return 8;
    }

    private boolean updateMovement() {
        boolean anyMoving = false;
        long now = SystemClock.uptimeMillis();

        for (BattleUnit unit : units) {
            boolean wasMoving = unit.isMoving();
            if (!wasMoving) {
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

            if (unit.hasPlannedPath()) {
                int next = unit.movePath.get(unit.movePathIndex++);
                unit.x = next % mapCols;
                unit.y = next / mapCols;
                if (!unit.hasPlannedPath()) {
                    unit.clearMovePath();
                }
            } else if (unit.x < unit.targetX) {
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
            int count = frames == null || frames.length == 0
                    ? 1
                    : frames.length;
            unit.moveFrame = (unit.moveFrame + 1) % count;

            if (unit == selectedUnit && openingFinished) {
                selectedX = unit.x;
                selectedY = unit.y;
            }

            if (wasMoving
                    && !unit.isMoving()
                    && unit == selectedUnit
                    && openingFinished) {
                refreshReachable();
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
                    openingWaitUntil = now
                            + Math.max(100L, event.value * 80L);
                    return true;

                case "move": {
                    BattleUnit unit = findUnitByCharacterId(
                            event.characterId);
                    if (unit == null) {
                        openingIndex++;
                        break;
                    }

                    unit.visible = true;
                    unit.clearMovePath();
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
                    BattleUnit unit = findUnitByCharacterId(
                            event.characterId);
                    if (unit != null) {
                        unit.visible = true;
                    }
                    openingIndex++;
                    openingWaitUntil = now + 120L;
                    return true;
                }

                case "hide":
                case "retreat": {
                    BattleUnit unit = findUnitByCharacterId(
                            event.characterId);
                    if (unit != null) {
                        unit.visible = false;
                        unit.clearMovePath();
                        unit.targetX = unit.x;
                        unit.targetY = unit.y;
                    }
                    openingIndex++;
                    openingWaitUntil = now + 140L;
                    return true;
                }

                case "turn": {
                    BattleUnit unit = findUnitByCharacterId(
                            event.characterId);
                    if (unit != null) {
                        int direction = event.direction;
                        if (direction < 0 && event.targetId >= 0) {
                            BattleUnit target = findUnitByCharacterId(
                                    event.targetId);
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
                    BattleUnit unit = findUnitByCharacterId(
                            event.characterId);
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
        refreshReachable();
        invalidate();
    }

    private void selectFirstPlayer() {
        for (BattleUnit unit : units) {
            if (unit.isPlayer() && unit.visible && unit.isAlive()) {
                selectedUnit = unit;
                selectedX = unit.x;
                selectedY = unit.y;
                return;
            }
        }
    }

    private void clearReachable() {
        reachableBest = null;
        reachablePrev = null;
    }

    private void refreshReachable() {
        clearReachable();
        if (!openingFinished
                || selectedUnit == null
                || !selectedUnit.isPlayer()
                || !selectedUnit.visible
                || !selectedUnit.isAlive()
                || selectedUnit.acted
                || selectedUnit.isMoving()
                || selectedUnit.movePoints <= 0) {
            return;
        }

        int total = mapCols * mapRows;
        reachableBest = new int[total];
        reachablePrev = new int[total];
        Arrays.fill(reachableBest, IMPASSABLE);
        Arrays.fill(reachablePrev, -1);

        int start = tileIndex(selectedUnit.x, selectedUnit.y);
        reachableBest[start] = 0;

        PriorityQueue<PathNode> queue = new PriorityQueue<>();
        queue.add(new PathNode(start, 0));

        int[] dx = {0, 1, 0, -1};
        int[] dy = {-1, 0, 1, 0};

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (current.cost != reachableBest[current.index]) {
                continue;
            }

            int cx = current.index % mapCols;
            int cy = current.index / mapCols;

            for (int d = 0; d < 4; d++) {
                int nx = cx + dx[d];
                int ny = cy + dy[d];
                if (!inBounds(nx, ny)) {
                    continue;
                }

                if (occupied(nx, ny, selectedUnit)) {
                    continue;
                }

                int stepCost = movementCost(selectedUnit, nx, ny);
                if (stepCost >= IMPASSABLE) {
                    continue;
                }

                int nextCost = current.cost + stepCost;
                if (nextCost > selectedUnit.movePoints) {
                    continue;
                }

                int next = tileIndex(nx, ny);
                if (nextCost >= reachableBest[next]) {
                    continue;
                }

                reachableBest[next] = nextCost;
                reachablePrev[next] = current.index;
                queue.add(new PathNode(next, nextCost));
            }
        }
    }

    private boolean planSelectedMove(int tx, int ty) {
        if (selectedUnit == null
                || !selectedUnit.isPlayer()
                || !selectedUnit.isAlive()
                || selectedUnit.acted
                || selectedUnit.isMoving()
                || reachableBest == null
                || !inBounds(tx, ty)) {
            return false;
        }

        int target = tileIndex(tx, ty);
        if (reachableBest[target] >= IMPASSABLE
                || reachableBest[target] > selectedUnit.movePoints
                || occupied(tx, ty, selectedUnit)) {
            return false;
        }

        int start = tileIndex(selectedUnit.x, selectedUnit.y);
        if (target == start) {
            return false;
        }

        List<Integer> reverse = new ArrayList<>();
        int cursor = target;
        while (cursor != start && cursor >= 0) {
            reverse.add(cursor);
            cursor = reachablePrev[cursor];
        }
        if (cursor != start) {
            return false;
        }

        Collections.reverse(reverse);
        selectedUnit.clearMovePath();
        selectedUnit.movePath.addAll(reverse);
        selectedUnit.targetX = tx;
        selectedUnit.targetY = ty;
        selectedUnit.lastMoveStepAt = 0L;
        clearReachable();
        return true;
    }

    private boolean supportsAttackRange(BattleUnit unit) {
        return unit.attackRangeId == 0 || unit.attackRangeId == 1;
    }

    private boolean isInAttackRange(
            BattleUnit attacker,
            BattleUnit target) {
        int dx = Math.abs(target.x - attacker.x);
        int dy = Math.abs(target.y - attacker.y);

        if (attacker.attackRangeId == 0) {
            return dx + dy == 1;
        }
        if (attacker.attackRangeId == 1) {
            return Math.max(dx, dy) == 1 && (dx + dy) > 0;
        }
        return false;
    }

    private boolean canAttack(
            BattleUnit attacker,
            BattleUnit target) {
        return openingFinished
                && attacker != null
                && target != null
                && attacker.isPlayer()
                && attacker.isAlive()
                && target.isEnemy()
                && target.visible
                && target.isAlive()
                && !attacker.acted
                && !attacker.isMoving()
                && supportsAttackRange(attacker)
                && isInAttackRange(attacker, target);
    }

    private void performAttack(
            BattleUnit attacker,
            BattleUnit target) {
        if (!canAttack(attacker, target)) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        attacker.direction = directionToward(attacker, target);
        attacker.attackStartedAt = now;
        attacker.attackUntil = now + ATTACK_ANIMATION_MS;
        attacker.acted = true;

        target.actionFrame = 1;
        target.actionUntil = now + ATTACK_ANIMATION_MS;

        // v0.8 deliberately implements only the verified core physical
        // difference. Penetration, terrain combat affinity, equipment,
        // crit/combo, hit/evasion and counterattack are subsequent slices.
        int damage = Math.max(0, attacker.attack - target.defense);
        target.hp = Math.max(0, target.hp - damage);

        String suffix;
        if (!target.isAlive()) {
            suffix = " · 격파";
            target.visible = false;
            target.clearMovePath();
            target.targetX = target.x;
            target.targetY = target.y;
        } else if (damage == 0) {
            suffix = " · 방어";
        } else {
            suffix = "";
        }

        lastCombatMessage = attacker.name
                + " → " + target.name
                + " : 피해 " + damage
                + " (ATK " + attacker.attack
                + " - DEF " + target.defense + ")"
                + suffix;
        combatMessageUntil = now + 2200L;

        selectedUnit = attacker;
        selectedX = attacker.x;
        selectedY = attacker.y;
        clearReachable();
        invalidate();
    }

    private boolean hasActiveAttackAnimation(long now) {
        for (BattleUnit unit : units) {
            if (now < unit.attackUntil || now < unit.actionUntil) {
                return true;
            }
        }
        return false;
    }

    private int terrainAt(int x, int y) {
        if (!inBounds(x, y)) {
            return -1;
        }
        return terrainCells[tileIndex(x, y)] & 0xff;
    }

    private int movementCost(BattleUnit unit, int x, int y) {
        int terrainId = terrainAt(x, y);
        if (terrainId < 0
                || terrainId >= terrainTypeCount
                || unit.jobFamily < 0
                || unit.jobFamily >= movementCostFamilyCount) {
            return IMPASSABLE;
        }

        int index = unit.jobFamily * terrainTypeCount + terrainId;
        int raw = movementCosts[index] & 0xff;

        if (raw <= 0 || raw >= 255) {
            return IMPASSABLE;
        }
        return raw;
    }

    private boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < mapCols && y < mapRows;
    }

    private int tileIndex(int x, int y) {
        return y * mapCols + x;
    }

    private BattleUnit findUnitAt(int tx, int ty) {
        for (int i = units.size() - 1; i >= 0; i--) {
            BattleUnit unit = units.get(i);
            if (unit.visible
                    && unit.isAlive()
                    && unit.x == tx
                    && unit.y == ty) {
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
            if (unit != except
                    && unit.visible
                    && unit.isAlive()
                    && unit.x == tx
                    && unit.y == ty) {
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
        String speaker = dialogueSpeaker == null
                || dialogueSpeaker.isEmpty()
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
        canvas.drawText(
                "▼ 터치",
                right - 18f,
                bottom - 13f,
                dialogueTextPaint);
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
                if (dialogueTextPaint.measureText(candidate) > maxWidth
                        && line.length() > 0) {
                    canvas.drawText(
                            line.toString(),
                            x,
                            y,
                            dialogueTextPaint);
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
                canvas.drawText(
                        line.toString(),
                        x,
                        y,
                        dialogueTextPaint);
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

                        if (inBounds(tx, ty)) {
                            selectedX = tx;
                            selectedY = ty;
                            BattleUnit hit = findUnitAt(tx, ty);

                            if (hit != null) {
                                if (selectedUnit != null
                                        && selectedUnit.isPlayer()
                                        && hit.isEnemy()
                                        && canAttack(selectedUnit, hit)) {
                                    performAttack(selectedUnit, hit);
                                } else {
                                    selectedUnit = hit;
                                    selectedX = hit.x;
                                    selectedY = hit.y;
                                    if (hit.isPlayer()) {
                                        refreshReachable();
                                    } else {
                                        clearReachable();
                                    }
                                }
                            } else if (selectedUnit != null
                                    && selectedUnit.isPlayer()) {
                                planSelectedMove(tx, ty);
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

    private static final class PathNode
            implements Comparable<PathNode> {
        final int index;
        final int cost;

        PathNode(int index, int cost) {
            this.index = index;
            this.cost = cost;
        }

        @Override
        public int compareTo(PathNode other) {
            return Integer.compare(cost, other.cost);
        }
    }
}
