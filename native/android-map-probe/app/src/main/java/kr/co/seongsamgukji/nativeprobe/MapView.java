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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class MapView extends View {
    private static final float TILE = 48f;
    private static final long MOVE_STEP_MS = 150L;
    private static final long ATTACK_FRAME_MS = 105L;
    private static final long ATTACK_ANIMATION_MS = 480L;
    private static final long ENEMY_PAUSE_MS = 260L;
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
    private final Paint endTurnBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endTurnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF endTurnButton = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final List<BattleUnit> units = new ArrayList<>();
    private final List<OpeningEvent> openingEvents = new ArrayList<>();
    private final List<OpeningEvent> phaseTransitionEvents = new ArrayList<>();
    private final List<Integer> protectedCharacterIds = new ArrayList<>();
    private final List<JSONObject> battleEvents = new ArrayList<>();
    private final Set<Integer> firedBattleSections = new HashSet<>();
    private final Set<Integer> reinforcementCharacterIds = new HashSet<>();
    private final Map<Integer, Integer> scenarioVariables = new HashMap<>();
    private final Map<Integer, Integer> integerVariables = new HashMap<>();
    private final Map<Integer, Integer> globalValues = new HashMap<>();
    private final Map<Integer, Integer> itemInventory = new HashMap<>();
    private final List<JSONArray> battleActionStack = new ArrayList<>();
    private final List<Integer> battleActionIndexStack = new ArrayList<>();
    private final List<Boolean> battleConditionalStack = new ArrayList<>();
    private final Map<Integer, Bitmap[]> idleSprites = new HashMap<>();
    private final Map<Integer, Bitmap[]> moveSprites = new HashMap<>();
    private final Map<Integer, Bitmap[]> attackSprites = new HashMap<>();

    private final List<BattleUnit> enemyTurnOrder = new ArrayList<>();

    private Bitmap map;
    private byte[] palette;
    private byte[] terrainCells;
    private byte[] baseTerrainCells;
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
    private int scriptedEffectX = -1;
    private int scriptedEffectY = -1;
    private int scriptedEffectId = -1;
    private long scriptedEffectUntil = 0L;

    private String lastCombatMessage;
    private long combatMessageUntil;

    private boolean playerTurn = true;
    private int round = 1;
    private int enemyTurnIndex = 0;
    private BattleUnit activeEnemy;
    private BattleUnit activeEnemyTarget;
    private boolean activeEnemyAttackPending;
    private long enemyNextActionAt;

    private BattleUnit pendingCounterAttacker;
    private BattleUnit pendingCounterTarget;
    private long pendingCounterAt;

    private int battlePhase = 1;
    private int turnLimit = 3;
    private int phase2TurnLimit = 15;
    private int phase1GoalX = 6;
    private int phase1GoalY = 13;
    private int villageFailX = 13;
    private int villageFailY = 11;
    private String objectiveText = "";
    private String objectivePopupText = "";
    private String phase2ObjectiveText = "";
    private String phase2PopupText = "";
    private boolean phaseTransitionActive = false;
    private int phaseEventIndex = 0;
    private long phaseEventWaitUntil = 0L;
    private BattleUnit phaseMovingUnit;
    private boolean battleEnded = false;
    private boolean battleVictory = false;
    private String battleResultText = "";

    private JSONObject activeBattleEvent;
    private JSONArray activeBattleActions;
    private JSONArray victoryOutcomeActions;
    private JSONArray defeatOutcomeActions;
    private JSONArray postBattleOutcomeActions;
    private JSONObject s01DefeatOutcomeEvents;
    private JSONArray s01GenericDefeatActions;
    private JSONArray r01StoryScenes;
    private JSONArray r02StoryScenes;
    private int activeBattleActionIndex = 0;
    private long battleEventWaitUntil = 0L;
    private BattleUnit battleEventMovingUnit;
    private boolean scriptEventActive = false;
    private BattleUnit duelFirstUnit;
    private BattleUnit duelSecondUnit;
    private boolean lastBattleConditionalTaken = false;
    private boolean outcomeFlowActive = false;
    private String outcomeStage = "";
    private boolean menuEnabled = true;
    private boolean paletteResetApplied = false;
    private boolean s00Complete = false;
    private boolean r01StoryActive = false;
    private boolean r02StoryActive = false;
    private boolean s01Ready = false;
    private boolean s02Ready = false;
    private int currentBattleIndex = 0;
    private String battleMode = "s00-two-phase";
    private int r01StorySceneIndex = 0;
    private int r02StorySceneIndex = 0;
    private String storyTitle = "";
    private String storyLocation = "";
    private JSONObject activeChoiceAction;

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

        endTurnBackPaint.setColor(0xDD24344D);
        endTurnBackPaint.setStyle(Paint.Style.FILL);

        endTurnTextPaint.setColor(Color.WHITE);
        endTurnTextPaint.setTextSize(27f);
        endTurnTextPaint.setFakeBoldText(true);
        endTurnTextPaint.setTextAlign(Paint.Align.CENTER);

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
        currentBattleIndex = 0;
        battleMode = battle.optString(
                "battleMode",
                "s00-two-phase");

        mapCols = battle.optInt("widthTiles", map.getWidth() / (int) TILE);
        mapRows = battle.optInt("heightTiles", map.getHeight() / (int) TILE);
        terrainTypeCount = battle.optInt("terrainTypeCount", 30);
        movementCostFamilyCount = battle.optInt(
                "movementCostFamilyCount",
                40);

        String terrainFile = battle.optString(
                "terrainFile",
                "terrain0.bin");
        String movementCostFile = battle.optString(
                "movementCostFile",
                "movement_costs.bin");

        terrainCells = loadBytes(context, "battle/" + terrainFile);
        baseTerrainCells = terrainCells.clone();
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
                            + (movementCostFamilyCount
                            * terrainTypeCount));
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
            unit.aiPolicy = u.optInt("aiPolicy", unit.aiPolicy);
            units.add(unit);
            if (u.optBoolean("reinforcement", false)) {
                reinforcementCharacterIds.add(unit.characterId);
            }
            ensureSprite(context, unit.spriteId);
        }

        JSONArray eventList = battle.optJSONArray("openingEvents");
        if (eventList != null) {
            for (int i = 0; i < eventList.length(); i++) {
                openingEvents.add(
                        OpeningEvent.fromJson(
                                eventList.getJSONObject(i)));
            }
        }

        JSONObject objectives = battle.optJSONObject("battleObjectives");
        if (objectives != null) {
            JSONObject phase1 = objectives.optJSONObject("phase1");
            if (phase1 != null) {
                objectiveText = phase1.optString("objectiveText", "");
                objectivePopupText = phase1.optString("popupText", "");
                turnLimit = phase1.optInt("turnLimit", 3);

                JSONObject goal = phase1.optJSONObject("goal");
                if (goal != null) {
                    phase1GoalX = goal.optInt("x", phase1GoalX);
                    phase1GoalY = goal.optInt("y", phase1GoalY);
                }

                JSONObject failure = phase1.optJSONObject("villageFailure");
                if (failure != null) {
                    villageFailX = failure.optInt("x", villageFailX);
                    villageFailY = failure.optInt("y", villageFailY);
                }
            }

            JSONObject phase2 = objectives.optJSONObject("phase2");
            if (phase2 != null) {
                phase2ObjectiveText = phase2.optString("objectiveText", "");
                phase2PopupText = phase2.optString("popupText", "");
                phase2TurnLimit = phase2.optInt("turnLimit", 15);
            }

            JSONArray protectedIds = objectives.optJSONArray(
                    "protectedCharacterIds");
            if (protectedIds != null) {
                for (int i = 0; i < protectedIds.length(); i++) {
                    protectedCharacterIds.add(protectedIds.optInt(i));
                }
            }

            JSONArray transition = objectives.optJSONArray(
                    "phase1TransitionEvents");
            if (transition != null) {
                for (int i = 0; i < transition.length(); i++) {
                    phaseTransitionEvents.add(
                            OpeningEvent.fromJson(
                                    transition.getJSONObject(i)));
                }
            }
        }

        JSONObject outcomeEvents = battle.optJSONObject("outcomeEvents");
        if (outcomeEvents != null) {
            JSONObject victory = outcomeEvents.optJSONObject("victory");
            JSONObject defeat = outcomeEvents.optJSONObject("defeat");
            JSONObject postBattle = outcomeEvents.optJSONObject("postBattle");
            if (victory != null && victory.optBoolean("supported", false)) {
                victoryOutcomeActions = victory.optJSONArray("actions");
            }
            if (defeat != null && defeat.optBoolean("supported", false)) {
                defeatOutcomeActions = defeat.optJSONArray("actions");
            }
            if (postBattle != null
                    && postBattle.optBoolean("supported", false)) {
                postBattleOutcomeActions = postBattle.optJSONArray("actions");
            }
        }

        JSONObject r01Story = battle.optJSONObject("r01Story");
        if (r01Story != null
                && r01Story.optBoolean("supported", false)) {
            r01StoryScenes = r01Story.optJSONArray("scenes");
        }

        JSONArray compiledEvents = battle.optJSONArray("battleEvents");
        if (compiledEvents != null) {
            for (int i = 0; i < compiledEvents.length(); i++) {
                JSONObject event = compiledEvents.optJSONObject(i);
                if (event != null
                        && event.optBoolean("coreSupported", false)) {
                    battleEvents.add(event);
                }
            }
        }
    }


    private void enterS01Battle() {
        try {
            loadS01Battle(getContext());
            lastCombatMessage = "R_01 완료 · S_01 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            r01StoryActive = false;
            endBattle(
                    false,
                    "S_01 로드 실패 · " + e.getClass().getSimpleName());
        }
    }

    private void enterS02Battle() {
        try {
            loadS02Battle(getContext());
            lastCombatMessage = "R_02 완료 · S_02 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            r02StoryActive = false;
            endBattle(
                    false,
                    "S_02 로드 실패 · " + e.getClass().getSimpleName());
        }
    }

    private void loadS01Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle1.json",
                1,
                "m001.jpg",
                "terrain1.bin");
    }

    private void loadS02Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle2.json",
                2,
                "m002.jpg",
                "terrain2.bin");
    }

    private void loadFollowupBattle(
            Context context,
            String battleFile,
            int battleIndex,
            String defaultMap,
            String defaultTerrain) throws Exception {
        JSONObject battle = new JSONObject(new String(
                loadBytes(context, "battle/" + battleFile),
                StandardCharsets.UTF_8));

        String mapName = battle.optString("map", defaultMap);
        try (InputStream in = context.getAssets().open("map/" + mapName)) {
            map = BitmapFactory.decodeStream(in);
        }
        if (map == null) {
            throw new IOException(mapName + " decode failed");
        }

        currentBattleIndex = battleIndex;
        battleMode = battle.optString(
                "battleMode",
                "enemy-annihilation");
        mapCols = battle.optInt(
                "widthTiles",
                map.getWidth() / (int) TILE);
        mapRows = battle.optInt(
                "heightTiles",
                map.getHeight() / (int) TILE);
        terrainTypeCount = battle.optInt("terrainTypeCount", 30);
        movementCostFamilyCount = battle.optInt(
                "movementCostFamilyCount",
                40);

        terrainCells = loadBytes(
                context,
                "battle/" + battle.optString(
                        "terrainFile",
                        defaultTerrain));
        baseTerrainCells = terrainCells.clone();
        movementCosts = loadBytes(
                context,
                "battle/" + battle.optString(
                        "movementCostFile",
                        "movement_costs.bin"));

        if (terrainCells.length != mapCols * mapRows) {
            throw new IOException(
                    currentBattleLabel()
                            + " terrain size=" + terrainCells.length
                            + " expected=" + (mapCols * mapRows));
        }
        if (movementCosts.length
                != movementCostFamilyCount * terrainTypeCount) {
            throw new IOException(
                    currentBattleLabel()
                            + " movement cost size=" + movementCosts.length);
        }

        units.clear();
        reinforcementCharacterIds.clear();
        battleEvents.clear();
        firedBattleSections.clear();
        protectedCharacterIds.clear();
        phaseTransitionEvents.clear();
        openingEvents.clear();
        enemyTurnOrder.clear();

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
            unit.aiPolicy = u.optInt(
                    "aiPolicy",
                    unit.aiPolicy);
            units.add(unit);
            if (u.optBoolean("reinforcement", false)) {
                reinforcementCharacterIds.add(unit.characterId);
            }
            ensureSprite(context, unit.spriteId);
        }

        objectiveText = "";
        objectivePopupText = "";
        phase2ObjectiveText = "";
        phase2PopupText = "";
        turnLimit = 20;
        phase2TurnLimit = 20;

        JSONObject objectives = battle.optJSONObject(
                "battleObjectives");
        if (objectives != null) {
            JSONObject phase1 = objectives.optJSONObject("phase1");
            if (phase1 != null) {
                objectiveText = phase1.optString(
                        "objectiveText",
                        "");
                objectivePopupText = phase1.optString(
                        "popupText",
                        "");
                turnLimit = phase1.optInt("turnLimit", 20);
            }
            JSONObject phase2 = objectives.optJSONObject("phase2");
            if (phase2 != null) {
                phase2ObjectiveText = phase2.optString(
                        "objectiveText",
                        "");
                phase2PopupText = phase2.optString(
                        "popupText",
                        "");
                phase2TurnLimit = phase2.optInt(
                        "turnLimit",
                        turnLimit);
            }
            JSONArray protectedIds = objectives.optJSONArray(
                    "protectedCharacterIds");
            if (protectedIds != null) {
                for (int i = 0; i < protectedIds.length(); i++) {
                    protectedCharacterIds.add(
                            protectedIds.optInt(i));
                }
            }
        }

        JSONArray compiledEvents = battle.optJSONArray("battleEvents");
        if (compiledEvents != null) {
            for (int i = 0; i < compiledEvents.length(); i++) {
                JSONObject event = compiledEvents.optJSONObject(i);
                if (event != null
                        && event.optBoolean("coreSupported", false)) {
                    battleEvents.add(event);
                }
            }
        }

        victoryOutcomeActions = null;
        defeatOutcomeActions = null;
        postBattleOutcomeActions = null;
        s01DefeatOutcomeEvents = null;
        s01GenericDefeatActions = null;
        r02StoryScenes = null;

        JSONObject s01Outcomes = battle.optJSONObject("outcomeEvents");
        if (s01Outcomes != null) {
            JSONObject victory = s01Outcomes.optJSONObject("victory");
            JSONObject postBattle = s01Outcomes.optJSONObject("postBattle");
            JSONObject genericDefeat = s01Outcomes.optJSONObject(
                    "genericDefeat");
            if (victory != null && victory.optBoolean("supported", false)) {
                victoryOutcomeActions = victory.optJSONArray("actions");
            }
            if (postBattle != null
                    && postBattle.optBoolean("supported", false)) {
                postBattleOutcomeActions = postBattle.optJSONArray("actions");
            }
            if (genericDefeat != null
                    && genericDefeat.optBoolean("supported", false)) {
                s01GenericDefeatActions = genericDefeat.optJSONArray("actions");
            }
            s01DefeatOutcomeEvents = s01Outcomes.optJSONObject(
                    "defeatByCharacter");
        }

        JSONObject r02Story = battle.optJSONObject("r02Story");
        if (r02Story != null
                && r02Story.optBoolean("supported", false)) {
            r02StoryScenes = r02Story.optJSONArray("scenes");
        }

        outcomeFlowActive = false;
        outcomeStage = "";
        r01StoryActive = false;
        r02StoryActive = false;
        s01Ready = false;
        s02Ready = false;
        r01StorySceneIndex = 0;
        r02StorySceneIndex = 0;
        activeChoiceAction = null;
        storyTitle = "";
        storyLocation = "";

        openingIndex = 0;
        openingWaitUntil = 0L;
        openingFinished = false;
        scriptedMovingUnit = null;
        dialogueSpeaker = null;
        dialogueText = null;
        musicTrack = -1;
        lastSound = -1;
        scriptedEffectX = -1;
        scriptedEffectY = -1;
        scriptedEffectId = -1;
        scriptedEffectUntil = 0L;

        battlePhase = 1;
        phaseTransitionActive = false;
        phaseEventIndex = 0;
        phaseEventWaitUntil = 0L;
        phaseMovingUnit = null;
        battleEnded = false;
        battleVictory = false;
        battleResultText = "";
        scriptEventActive = false;
        activeBattleEvent = null;
        activeBattleActions = null;
        activeBattleActionIndex = 0;
        battleActionStack.clear();
        battleActionIndexStack.clear();
        battleConditionalStack.clear();
        lastBattleConditionalTaken = false;
        duelFirstUnit = null;
        duelSecondUnit = null;
        battleEventMovingUnit = null;
        battleEventWaitUntil = 0L;
        pendingCounterAttacker = null;
        pendingCounterTarget = null;
        pendingCounterAt = 0L;
        activeEnemy = null;
        activeEnemyTarget = null;
        activeEnemyAttackPending = false;
        enemyTurnIndex = 0;
        playerTurn = true;
        round = 1;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        clearReachable();

        float fit = Math.min(
                getWidth() > 0
                        ? (float) getWidth() / map.getWidth()
                        : 1f,
                getHeight() > 0
                        ? (float) getHeight() / map.getHeight()
                        : 1f);
        scale = Math.min(1f, fit);
        offsetX = (getWidth() - map.getWidth() * scale) * 0.5f;
        offsetY = (getHeight() - map.getHeight() * scale) * 0.5f;

        finishOpening();
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

        long now = SystemClock.uptimeMillis();
        boolean moving = updateMovement();
        boolean openingBusy = pumpOpeningEvents();
        checkBattleState();
        boolean phaseBusy = pumpPhaseTransitionEvents();
        boolean sceneEventBusy = pumpBattleScriptEvents();
        boolean counterBusy = updatePendingCounter(now);
        boolean enemyBusy = updateEnemyTurn(now);

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        canvas.drawBitmap(map, 0, 0, mapPaint);

        drawReachableTiles(canvas);
        drawAttackTargets(canvas);
        if (now < scriptedEffectUntil
                && inBounds(scriptedEffectX, scriptedEffectY)) {
            canvas.drawRect(
                    scriptedEffectX * TILE + 2,
                    scriptedEffectY * TILE + 2,
                    (scriptedEffectX + 1) * TILE - 2,
                    (scriptedEffectY + 1) * TILE - 2,
                    attackTargetPaint);
        }

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

        String header;
        if (r01StoryActive) {
            header = "Native v2.8 | R_01 Scene "
                    + Math.min(
                    r01StorySceneIndex + 1,
                    r01StoryScenes == null
                            ? 1
                            : r01StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r02StoryActive) {
            header = "Native v2.8 | R_02 Scene "
                    + Math.min(
                    r02StorySceneIndex + 1,
                    r02StoryScenes == null
                            ? 1
                            : r02StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else {
            header = "Native v2.8 | " + round + "/" + turnLimit + "턴 "
                    + (playerTurn ? "아군" : "적군")
                    + " | 단계 " + battlePhase
                    + " | 아군 " + playerCount
                    + " / 우군 " + allyCount
                    + " / 적군 " + enemyCount;
        }
        canvas.drawText(
                header,
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
        } else if (r01StoryActive || r02StoryActive) {
            String status = "원본 " + currentStoryLabel()
                    + " 스토리 재생 중";
            if (!storyLocation.isEmpty()) {
                status += " · " + storyLocation;
            }
            if (activeChoiceAction != null) {
                status += " · 선택";
            }
            canvas.drawText(status, 22, 65, overlayTextPaint);
        } else if (battleEnded) {
            canvas.drawText(
                    "전투 " + (battleVictory ? "승리" : "패배")
                            + " · " + battleResultText,
                    22,
                    65,
                    overlayTextPaint);
        } else if (phaseTransitionActive) {
            canvas.drawText(
                    "원본 S_00 목표 전환 이벤트 재생 중",
                    22,
                    65,
                    overlayTextPaint);
        } else if (scriptEventActive) {
            int section = activeBattleEvent == null
                    ? -1
                    : activeBattleEvent.optInt("section", -1);
            canvas.drawText(
                    "원본 " + currentBattleLabel() + " 전장 이벤트 재생 중"
                            + (section > 0 ? " · Section " + section : ""),
                    22,
                    65,
                    overlayTextPaint);
        } else if (playerTurn) {
            String objective = objectivePopupText == null
                    || objectivePopupText.isEmpty()
                    ? (battlePhase == 1
                    ? "마을에 도착하라!"
                    : "적군을 전멸시켜라!")
                    : objectivePopupText;
            canvas.drawText(
                    "목표: " + objective
                            + " · 이동 1회 / 공격 / 반격 / 턴 종료",
                    22,
                    65,
                    overlayTextPaint);
        } else {
            canvas.drawText(
                    "목표: " + objectivePopupText
                            + " · 적군 자동 행동 중",
                    22,
                    65,
                    overlayTextPaint);
        }

        if (openingFinished
                && !r01StoryActive
                && !r02StoryActive
                && selectedUnit != null) {
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

        if (openingFinished
                && playerTurn
                && !battleEnded
                && !phaseTransitionActive
                && !scriptEventActive) {
            drawEndTurnButton(canvas);
        }

        if (dialogueText != null) {
            drawDialogueBox(canvas);
        }
        if (activeChoiceAction != null) {
            drawStoryChoiceBox(canvas);
        }

        if (moving
                || openingBusy
                || phaseBusy
                || sceneEventBusy
                || counterBusy
                || enemyBusy
                || !openingFinished
                || r01StoryActive
                || r02StoryActive
                || activeChoiceAction != null
                || !playerTurn
                || hasActiveAttackAnimation(now)
                || now < scriptedEffectUntil
                || (lastCombatMessage != null
                && now < combatMessageUntil)) {
            postInvalidateDelayed(35L);
        }
    }

    private void drawEndTurnButton(Canvas canvas) {
        float right = getWidth() - 22f;
        float bottom = getHeight() - 22f;
        float left = Math.max(22f, right - 178f);
        float top = Math.max(145f, bottom - 58f);
        endTurnButton.set(left, top, right, bottom);

        canvas.drawRoundRect(
                endTurnButton,
                14f,
                14f,
                endTurnBackPaint);
        float baseline = endTurnButton.centerY()
                - (endTurnTextPaint.ascent()
                + endTurnTextPaint.descent()) / 2f;
        canvas.drawText(
                "턴 종료",
                endTurnButton.centerX(),
                baseline,
                endTurnTextPaint);
    }

    private void drawReachableTiles(Canvas canvas) {
        if (!openingFinished
                || battleEnded
                || phaseTransitionActive
                || scriptEventActive
                || !playerTurn
                || selectedUnit == null
                || !selectedUnit.isPlayer()
                || !selectedUnit.isAlive()
                || selectedUnit.moved
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
                || battleEnded
                || phaseTransitionActive
                || scriptEventActive
                || !playerTurn
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
                    : Math.floorMod(
                            unit.actionFrame,
                            source.length);
        } else if (unit.isMoving()) {
            source = moveSprites.get(unit.spriteId);
            frameIndex = source == null || source.length == 0
                    ? 0
                    : Math.max(
                            0,
                            Math.min(
                                    unit.moveFrame,
                                    source.length - 1));
        } else {
            source = idleSprites.get(unit.spriteId);
            frameIndex = 0;
        }

        if (source == null || source.length == 0) {
            return;
        }

        frameIndex = Math.max(
                0,
                Math.min(frameIndex, source.length - 1));
        canvas.drawBitmap(source[frameIndex], left, top, spritePaint);

        float tileLeft = unit.x * TILE;
        float tileTop = unit.y * TILE;
        Paint ring = "player".equals(unit.faction)
                ? playerPaint
                : ("ally".equals(unit.faction)
                ? allyPaint
                : enemyPaint);
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
        String label = unit.name + " "
                + unit.hp + "/" + unit.maxHp;
        float textWidth = unitTextPaint.measureText(label);
        canvas.drawRect(
                centerX - textWidth / 2f - 2f,
                labelY - 13f,
                centerX + textWidth / 2f + 2f,
                labelY + 2f,
                unitLabelBackPaint);
        canvas.drawText(
                label,
                centerX,
                labelY,
                unitTextPaint);
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
                    && openingFinished
                    && playerTurn) {
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
                                direction = directionToward(
                                        unit,
                                        target);
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


    private boolean pumpBattleScriptEvents() {
        if (!openingFinished
                || battleEnded
                || phaseTransitionActive) {
            return false;
        }

        long now = SystemClock.uptimeMillis();

        if (scriptEventActive) {
            if (dialogueText != null || activeChoiceAction != null) {
                return true;
            }

            if (battleEventMovingUnit != null) {
                if (battleEventMovingUnit.isMoving()) {
                    return true;
                }
                battleEventMovingUnit = null;
                activeBattleActionIndex++;
                battleEventWaitUntil = now + 80L;
            }

            if (now < battleEventWaitUntil) {
                return true;
            }

            while (activeBattleActions != null
                    && activeBattleActionIndex
                    < activeBattleActions.length()) {
                JSONObject action = activeBattleActions.optJSONObject(
                        activeBattleActionIndex);
                if (action == null) {
                    activeBattleActionIndex++;
                    continue;
                }

                String type = action.optString("type", "");
                switch (type) {
                    case "sequence":
                        if (!enterNestedBattleActions(
                                action.optJSONArray("actions"))) {
                            activeBattleActionIndex++;
                        }
                        break;

                    case "conditionalVariables": {
                        boolean taken = scriptVariableConditionSatisfied(action);
                        lastBattleConditionalTaken = taken;
                        if (taken && enterNestedBattleActions(
                                action.optJSONArray("actions"))) {
                            break;
                        }
                        activeBattleActionIndex++;
                        break;
                    }

                    case "conditionalTrigger": {
                        JSONObject trigger = action.optJSONObject("trigger");
                        boolean taken = trigger != null
                                && battleTriggerSatisfied(trigger);
                        lastBattleConditionalTaken = taken;
                        if (taken && enterNestedBattleActions(
                                action.optJSONArray("actions"))) {
                            break;
                        }
                        activeBattleActionIndex++;
                        break;
                    }

                    case "elseBranch": {
                        boolean taken = !lastBattleConditionalTaken;
                        lastBattleConditionalTaken = taken;
                        if (taken && enterNestedBattleActions(
                                action.optJSONArray("actions"))) {
                            break;
                        }
                        activeBattleActionIndex++;
                        break;
                    }

                    case "choice":
                        activeChoiceAction = action;
                        return true;

                    case "storyTitle":
                        storyTitle = action.optString("text", "");
                        lastCombatMessage = currentStoryLabel()
                                + " · " + storyTitle;
                        combatMessageUntil = now + 1500L;
                        activeBattleActionIndex++;
                        break;

                    case "storyLocation":
                        storyLocation = action.optString("text", "");
                        lastCombatMessage = "장소 · " + storyLocation;
                        combatMessageUntil = now + 1300L;
                        activeBattleActionIndex++;
                        break;

                    case "storyMapText":
                        lastCombatMessage = action.optString(
                                "text",
                                "지도 연출");
                        combatMessageUntil = now + 1200L;
                        activeBattleActionIndex++;
                        break;

                    case "storyBackground":
                    case "storyVisual":
                        activeBattleActionIndex++;
                        break;

                    case "globalValueOp":
                        applyGlobalValueAction(action);
                        activeBattleActionIndex++;
                        break;

                    case "deploymentLimit":
                        activeBattleActionIndex++;
                        break;

                    case "deploymentTest":
                        lastCombatMessage = "원본 출전 확인 · "
                                + (r02StoryActive
                                ? "S_02 준비"
                                : "S_01 준비");
                        combatMessageUntil = now + 1000L;
                        activeBattleActionIndex++;
                        break;

                    case "dialogue":
                        dialogueSpeaker = action.optString("speaker", "");
                        dialogueText = action.optString("text", "");
                        return true;

                    case "delay":
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now
                                + Math.max(
                                100L,
                                action.optInt("value", 1) * 80L);
                        return true;

                    case "move": {
                        BattleUnit unit = findUnitByCharacterId(
                                action.optInt("characterId", -1));
                        if (unit == null) {
                            activeBattleActionIndex++;
                            break;
                        }
                        unit.visible = true;
                        unit.clearMovePath();
                        unit.targetX = action.optInt("x", unit.x);
                        unit.targetY = action.optInt("y", unit.y);
                        int direction = action.optInt("direction", -1);
                        if (direction >= 0) {
                            unit.direction = direction;
                        }
                        unit.lastMoveStepAt = 0L;
                        if (unit.isMoving()) {
                            battleEventMovingUnit = unit;
                            return true;
                        }
                        activeBattleActionIndex++;
                        break;
                    }

                    case "reveal": {
                        BattleUnit unit = findUnitByCharacterId(
                                action.optInt("characterId", -1));
                        if (unit != null) {
                            unit.visible = true;
                        }
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 100L;
                        return true;
                    }

                    case "hide":
                    case "retreat": {
                        BattleUnit unit = findUnitByCharacterId(
                                action.optInt("characterId", -1));
                        if (unit != null) {
                            unit.visible = false;
                            unit.clearMovePath();
                            unit.targetX = unit.x;
                            unit.targetY = unit.y;
                        }
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 100L;
                        return true;
                    }

                    case "hideArea":
                        applyHideAreaAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 120L;
                        return true;

                    case "kill": {
                        BattleUnit unit = findUnitByCharacterId(
                                action.optInt("characterId", -1));
                        if (unit != null) {
                            unit.hp = 0;
                            unit.visible = false;
                            unit.clearMovePath();
                            unit.targetX = unit.x;
                            unit.targetY = unit.y;
                        }
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 120L;
                        return true;
                    }

                    case "revive": {
                        BattleUnit unit = findUnitByCharacterId(
                                action.optInt("characterId", -1));
                        if (unit != null) {
                            unit.hp = unit.maxHp;
                            unit.visible = true;
                            unit.x = action.optInt("x", unit.x);
                            unit.y = action.optInt("y", unit.y);
                            unit.targetX = unit.x;
                            unit.targetY = unit.y;
                            int direction = action.optInt(
                                    "direction",
                                    unit.direction);
                            if (direction >= 0) {
                                unit.direction = direction;
                            }
                        }
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 120L;
                        return true;
                    }

                    case "turn": {
                        BattleUnit unit = findUnitByCharacterId(
                                action.optInt("characterId", -1));
                        if (unit != null) {
                            int direction = action.optInt("direction", -1);
                            int targetId = action.optInt("targetId", -1);
                            if (direction < 0 && targetId >= 0) {
                                BattleUnit target =
                                        findUnitByCharacterId(targetId);
                                if (target != null) {
                                    direction = directionToward(unit, target);
                                }
                            }
                            if (direction >= 0) {
                                unit.direction = direction;
                            }
                        }
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 100L;
                        return true;
                    }

                    case "action": {
                        BattleUnit unit = findUnitByCharacterId(
                                action.optInt("characterId", -1));
                        if (unit != null) {
                            unit.actionFrame = action.optInt("value", 0);
                            unit.actionUntil = now + 420L;
                        }
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 420L;
                        return true;
                    }

                    case "spellEffect":
                        scriptedEffectX = action.optInt("x", -1);
                        scriptedEffectY = action.optInt("y", -1);
                        scriptedEffectId = action.optInt("effectId", -1);
                        scriptedEffectUntil = now + 520L;
                        if (action.optBoolean("focus", false)
                                && inBounds(
                                scriptedEffectX,
                                scriptedEffectY)) {
                            selectedX = scriptedEffectX;
                            selectedY = scriptedEffectY;
                        }
                        lastCombatMessage = "법술 연출 "
                                + scriptedEffectId
                                + " · (" + scriptedEffectX
                                + "," + scriptedEffectY + ")";
                        combatMessageUntil = now + 900L;
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 520L;
                        return true;

                    case "sound":
                        lastSound = action.optInt("value", -1);
                        activeBattleActionIndex++;
                        break;

                    case "music":
                        musicTrack = action.optInt("value", -1);
                        activeBattleActionIndex++;
                        break;

                    case "reward": {
                        int itemId = action.optInt("value", -1);
                        if (itemId >= 0) {
                            itemInventory.put(
                                    itemId,
                                    itemInventory.getOrDefault(itemId, 0) + 1);
                        }
                        lastCombatMessage = "원본 보상 이벤트 · 아이템 "
                                + itemId
                                + " → 인물 "
                                + action.optInt("targetId", -1);
                        combatMessageUntil = now + 1800L;
                        activeBattleActionIndex++;
                        break;
                    }

                    case "aiPolicy":
                        applyAiPolicyAction(action);
                        activeBattleActionIndex++;
                        break;

                    case "relativeMove":
                        applyRelativeMoveAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 120L;
                        return true;

                    case "battlefieldObject":
                        applyBattlefieldObjectAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 160L;
                        return true;

                    case "unitAttributeTransfer":
                        applyUnitAttributeTransferAction(action);
                        activeBattleActionIndex++;
                        break;

                    case "intVariableOp":
                        applyIntegerVariableAction(action);
                        activeBattleActionIndex++;
                        break;

                    case "statusChange":
                        applyStatusChangeAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 160L;
                        return true;

                    case "duelStart":
                        startScriptedDuel(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 240L;
                        return true;

                    case "duelIntro":
                    case "duelDialogue":
                        showDuelDialogue(action);
                        return true;

                    case "duelGesture":
                        applyDuelGesture(action, now);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 320L;
                        return true;

                    case "duelAttack":
                    case "duelCharge":
                        applyDuelAttack(action, now);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 420L;
                        return true;

                    case "duelClash":
                        lastCombatMessage = "일기토 · 공방";
                        combatMessageUntil = now + 800L;
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 260L;
                        return true;

                    case "duelDefeat":
                        applyDuelDefeat(action, now);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 420L;
                        return true;

                    case "duelEnd":
                        duelFirstUnit = null;
                        duelSecondUnit = null;
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 160L;
                        return true;

                    case "loot": {
                        JSONArray slots = action.optJSONArray("slots");
                        StringBuilder rewardText = new StringBuilder();
                        if (slots != null) {
                            for (int i = 0; i < slots.length(); i++) {
                                JSONObject slot = slots.optJSONObject(i);
                                if (slot == null) {
                                    continue;
                                }
                                int itemId = slot.optInt("itemId", -1);
                                if (itemId < 0) {
                                    continue;
                                }
                                itemInventory.put(
                                        itemId,
                                        itemInventory.getOrDefault(
                                                itemId,
                                                0) + 1);
                                if (rewardText.length() > 0) {
                                    rewardText.append(", ");
                                }
                                rewardText.append(itemId);
                            }
                        }
                        lastCombatMessage = rewardText.length() == 0
                                ? "원본 전리품 정산"
                                : "전리품 · 아이템 " + rewardText;
                        combatMessageUntil = now + 1600L;
                        activeBattleActionIndex++;
                        break;
                    }

                    case "battleEndMarker":
                        lastCombatMessage = "원본 전투 종료 처리";
                        combatMessageUntil = now + 900L;
                        activeBattleActionIndex++;
                        break;

                    case "battleFailureMarker":
                        lastCombatMessage = "원본 패배 처리";
                        combatMessageUntil = now + 900L;
                        activeBattleActionIndex++;
                        break;

                    case "sceneEnd":
                        lastCombatMessage = "원본 Scene 종료";
                        combatMessageUntil = now + 700L;
                        activeBattleActionIndex++;
                        break;

                    case "menu":
                        menuEnabled = action.optBoolean("enabled", false);
                        activeBattleActionIndex++;
                        break;

                    case "paletteReset":
                        paletteResetApplied = true;
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 100L;
                        return true;

                    case "setVariable":
                        scenarioVariables.put(
                                action.optInt("variableId", -1),
                                action.optInt("value", 0));
                        activeBattleActionIndex++;
                        break;

                    case "turnLimit":
                        turnLimit = Math.max(
                                1,
                                action.optInt("value", turnLimit));
                        activeBattleActionIndex++;
                        break;

                    case "objective":
                        objectiveText = action.optString(
                                "text",
                                objectiveText);
                        activeBattleActionIndex++;
                        break;

                    case "objectivePopup":
                        objectivePopupText = action.optString(
                                "text",
                                objectivePopupText);
                        activeBattleActionIndex++;
                        break;

                    default:
                        activeBattleActionIndex++;
                        break;
                }
            }

            if (restoreParentBattleActions()) {
                return true;
            }

            finishBattleScriptEvent();
            return false;
        }

        if (dialogueText != null
                || pendingCounterAttacker != null
                || hasActiveAttackAnimation(now)
                || anyUnitMoving()) {
            return false;
        }

        for (JSONObject event : battleEvents) {
            int section = event.optInt("section", -1);
            if (section < 0 || firedBattleSections.contains(section)) {
                continue;
            }
            if (battleEventConditionsSatisfied(event)) {
                startBattleScriptEvent(event);
                return true;
            }
        }

        return false;
    }



    private boolean enterNestedBattleActions(JSONArray actions) {
        if (actions == null || actions.length() == 0) {
            return false;
        }
        battleActionStack.add(activeBattleActions);
        battleActionIndexStack.add(activeBattleActionIndex + 1);
        battleConditionalStack.add(lastBattleConditionalTaken);
        activeBattleActions = actions;
        activeBattleActionIndex = 0;
        lastBattleConditionalTaken = false;
        return true;
    }

    private boolean restoreParentBattleActions() {
        if (battleActionStack.isEmpty()) {
            return false;
        }
        int last = battleActionStack.size() - 1;
        activeBattleActions = battleActionStack.remove(last);
        activeBattleActionIndex = battleActionIndexStack.remove(last);
        lastBattleConditionalTaken = battleConditionalStack.remove(last);
        return true;
    }

    private boolean scriptVariableConditionSatisfied(JSONObject action) {
        JSONArray requiredTrue = action.optJSONArray(
                "requireTrueVariables");
        if (requiredTrue != null) {
            for (int i = 0; i < requiredTrue.length(); i++) {
                int id = requiredTrue.optInt(i, -1);
                if (id >= 0
                        && scenarioVariables.getOrDefault(id, 0) == 0) {
                    return false;
                }
            }
        }

        JSONArray requiredFalse = action.optJSONArray(
                "requireFalseVariables");
        if (requiredFalse != null) {
            for (int i = 0; i < requiredFalse.length(); i++) {
                int id = requiredFalse.optInt(i, -1);
                if (id >= 0
                        && scenarioVariables.getOrDefault(id, 0) != 0) {
                    return false;
                }
            }
        }
        return true;
    }



    private void applyHideAreaAction(JSONObject action) {
        int left = Math.min(
                action.optInt("x1", 0),
                action.optInt("x2", mapCols - 1));
        int right = Math.max(
                action.optInt("x1", 0),
                action.optInt("x2", mapCols - 1));
        int top = Math.min(
                action.optInt("y1", 0),
                action.optInt("y2", mapRows - 1));
        int bottom = Math.max(
                action.optInt("y1", 0),
                action.optInt("y2", mapRows - 1));
        int camp = action.optInt("camp", 6);

        for (BattleUnit unit : units) {
            if (unit.x < left
                    || unit.x > right
                    || unit.y < top
                    || unit.y > bottom
                    || !matchesCamp(unit, camp)) {
                continue;
            }
            unit.visible = false;
            unit.clearMovePath();
            unit.targetX = unit.x;
            unit.targetY = unit.y;
        }
    }

    private void applyBattlefieldObjectAction(JSONObject action) {
        int x = action.optInt("x", -1);
        int y = action.optInt("y", -1);
        if (!inBounds(x, y)) {
            return;
        }

        int index = tileIndex(x, y);
        boolean visible = action.optBoolean("visible", true);
        int terrainId = action.optInt("terrainId", terrainAt(x, y));

        if (visible
                && terrainId >= 0
                && terrainId < terrainTypeCount) {
            terrainCells[index] = (byte) terrainId;
        } else if (!visible
                && baseTerrainCells != null
                && index < baseTerrainCells.length) {
            terrainCells[index] = baseTerrainCells[index];
        }

        if (action.optBoolean("focus", false)) {
            selectedX = x;
            selectedY = y;
        }

        lastCombatMessage = "전장 물체 "
                + action.optInt("objectId", -1)
                + (visible ? " 표시" : " 해제")
                + " · 지형 " + (terrainCells[index] & 0xff)
                + " · (" + x + "," + y + ")";
        combatMessageUntil = SystemClock.uptimeMillis() + 1000L;

        if (selectedUnit != null && selectedUnit.isPlayer()) {
            refreshReachable();
        }
    }

    private void applyUnitAttributeTransferAction(JSONObject action) {
        int variableId = action.optInt("variableId", -1);
        int direction = action.optInt("direction", 0);
        int characterId = action.optInt("characterId", -1);
        int attribute = action.optInt("attribute", -1);
        if (variableId < 0) {
            return;
        }

        BattleUnit unit = findUnitByCharacterId(characterId);
        if (unit == null) {
            return;
        }

        if (direction == 0) {
            int value;
            if (attribute == 7) {
                value = unit.maxHp;
            } else if (attribute == 32) {
                value = unit.direction;
            } else if (attribute == 33) {
                value = unit.hp;
            } else {
                return;
            }
            integerVariables.put(variableId, value);
            return;
        }

        if (direction == 1) {
            int value = integerVariables.getOrDefault(variableId, 0);
            if (attribute == 32) {
                unit.direction = value;
                return;
            }
            if (attribute == 33) {
                unit.hp = Math.max(0, Math.min(unit.maxHp, value));
                if (unit.hp > 0) {
                    unit.visible = true;
                }
            }
        }
    }

    private void applyGlobalValueAction(JSONObject action) {
        int id = action.optInt("globalId", -1);
        if (id < 0) {
            return;
        }
        int current = globalValues.getOrDefault(id, 0);
        int value = action.optInt("value", 0);
        int operation = action.optInt("operation", 0);
        int next;
        if (operation == 1) {
            next = current + value;
        } else if (operation == 2) {
            next = current - value;
        } else {
            next = value;
        }
        globalValues.put(id, next);
    }

    private void chooseStoryOption(int optionIndex) {
        if (activeChoiceAction == null || optionIndex < 0) {
            return;
        }

        JSONArray cases = activeChoiceAction.optJSONArray("cases");
        JSONObject selectedCase = null;
        int wantedValue = optionIndex + 1;
        if (cases != null) {
            for (int i = 0; i < cases.length(); i++) {
                JSONObject candidate = cases.optJSONObject(i);
                if (candidate != null
                        && candidate.optInt("value", i + 1)
                        == wantedValue) {
                    selectedCase = candidate;
                    break;
                }
            }
        }

        activeChoiceAction = null;
        if (selectedCase != null
                && enterNestedBattleActions(
                selectedCase.optJSONArray("actions"))) {
            battleEventWaitUntil = SystemClock.uptimeMillis() + 80L;
        } else {
            activeBattleActionIndex++;
        }
        invalidate();
    }

    private boolean handleStoryChoiceTap(float x, float y) {
        if (activeChoiceAction == null) {
            return false;
        }
        JSONArray options = activeChoiceAction.optJSONArray("options");
        if (options == null || options.length() == 0) {
            activeChoiceAction = null;
            activeBattleActionIndex++;
            invalidate();
            return true;
        }

        float left = 48f;
        float right = getWidth() - 48f;
        float rowHeight = 62f;
        float totalHeight = rowHeight * options.length();
        float top = Math.max(92f, (getHeight() - totalHeight) * 0.5f);

        if (x < left || x > right || y < top
                || y > top + totalHeight) {
            return true;
        }

        int index = (int) ((y - top) / rowHeight);
        index = Math.max(0, Math.min(index, options.length() - 1));
        chooseStoryOption(index);
        return true;
    }

    private void applyIntegerVariableAction(JSONObject action) {
        int id = action.optInt("variableId", -1);
        if (id < 0) {
            return;
        }

        int current = integerVariables.getOrDefault(id, 0);
        int value = action.optInt("value", 0);
        int operation = action.optInt("operation", 2);
        int next = current;

        switch (operation) {
            case 0:
                next = current + value;
                break;
            case 1:
                next = current - value;
                break;
            case 2:
                next = value;
                break;
            case 3:
                next = current * value;
                break;
            case 4:
                if (value != 0) {
                    next = current / value;
                }
                break;
            case 5:
                if (value != 0) {
                    next = current % value;
                }
                break;
            case 6:
                next = value;
                break;
            default:
                return;
        }
        integerVariables.put(id, next);
    }

    private void applyStatusChangeAction(JSONObject action) {
        int targetMode = action.optInt("targetMode", 0);

        if (targetMode == 0) {
            BattleUnit unit = findUnitByCharacterId(
                    action.optInt("characterId", -1));
            if (unit != null) {
                applyStatusChange(unit, action);
            }
            return;
        }

        if (targetMode != 2) {
            return;
        }

        int left = Math.min(
                action.optInt("x1", 0),
                action.optInt("x2", mapCols - 1));
        int right = Math.max(
                action.optInt("x1", 0),
                action.optInt("x2", mapCols - 1));
        int top = Math.min(
                action.optInt("y1", 0),
                action.optInt("y2", mapRows - 1));
        int bottom = Math.max(
                action.optInt("y1", 0),
                action.optInt("y2", mapRows - 1));
        int camp = action.optInt("camp", 6);

        for (BattleUnit unit : units) {
            if (!unit.visible
                    || !unit.isAlive()
                    || unit.x < left
                    || unit.x > right
                    || unit.y < top
                    || unit.y > bottom
                    || !matchesCamp(unit, camp)) {
                continue;
            }
            applyStatusChange(unit, action);
        }
    }

    private void applyStatusChange(
            BattleUnit unit,
            JSONObject action) {
        int condition = action.optInt("condition", 6);
        int change = action.optInt("change", 3);

        if (change >= 0 && change <= 2) {
            switch (condition) {
                case 0:
                    unit.attackCondition = change;
                    break;
                case 1:
                    unit.defenseCondition = change;
                    break;
                case 2:
                    unit.spiritCondition = change;
                    break;
                case 3:
                    unit.burstCondition = change;
                    break;
                case 4:
                    unit.moraleCondition = change;
                    break;
                default:
                    break;
            }
        }

        int rawMask = action.optInt("debuffMask", 0);
        int statusBits = rawMask & 0x7f;
        if (statusBits != 0) {
            if (rawMask < 128) {
                unit.debuffMask |= statusBits;
            } else {
                unit.debuffMask &= ~statusBits;
            }
        }

        if ((unit.debuffMask & 0x08) != 0) {
            lastCombatMessage = unit.name + " · 혼란";
            combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
        }
    }

    private void startScriptedDuel(JSONObject action) {
        duelFirstUnit = findUnitByCharacterId(
                action.optInt("firstCharacterId", -1));
        duelSecondUnit = findUnitByCharacterId(
                action.optInt("secondCharacterId", -1));

        String first = duelFirstUnit == null
                ? "?"
                : duelFirstUnit.name;
        String second = duelSecondUnit == null
                ? "?"
                : duelSecondUnit.name;
        lastCombatMessage = "일기토 · " + first + " vs " + second;
        combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
    }

    private BattleUnit duelUnitForSide(int side) {
        return side == 0 ? duelSecondUnit : duelFirstUnit;
    }

    private void showDuelDialogue(JSONObject action) {
        BattleUnit unit = duelUnitForSide(action.optInt("side", 0));
        dialogueSpeaker = unit == null ? "일기토" : unit.name;
        dialogueText = action.optString("text", "");

        int gesture = action.optInt("gesture", -1);
        if (unit != null && gesture >= 0) {
            unit.actionFrame = gesture;
            unit.actionUntil = SystemClock.uptimeMillis() + 420L;
        }
    }

    private void applyDuelGesture(JSONObject action, long now) {
        BattleUnit unit = duelUnitForSide(action.optInt("side", 0));
        if (unit == null) {
            return;
        }
        unit.actionFrame = action.optInt("gesture", 0);
        unit.actionUntil = now + 420L;
    }

    private void applyDuelAttack(JSONObject action, long now) {
        BattleUnit unit = duelUnitForSide(action.optInt("side", 0));
        if (unit == null) {
            return;
        }
        unit.attackStartedAt = now;
        unit.attackUntil = now + ATTACK_ANIMATION_MS;
        lastCombatMessage = "일기토 · "
                + unit.name
                + (action.optBoolean("critical", false)
                ? " 치명타 연출"
                : " 공격 연출");
        combatMessageUntil = now + 900L;
    }

    private void applyDuelDefeat(JSONObject action, long now) {
        BattleUnit unit = duelUnitForSide(action.optInt("side", 0));
        if (unit == null) {
            return;
        }
        unit.actionFrame = 10;
        unit.actionUntil = now + 520L;
        lastCombatMessage = "일기토 · " + unit.name + " 패배 연출";
        combatMessageUntil = now + 1000L;
    }

    private void applyRelativeMoveAction(JSONObject action) {
        BattleUnit unit = findUnitByCharacterId(
                action.optInt("characterId", -1));
        BattleUnit anchor = findUnitByCharacterId(
                action.optInt("anchorCharacterId", -1));
        if (unit == null || anchor == null) {
            return;
        }

        int x = anchor.x + action.optInt("offsetX", 0);
        int y = anchor.y + action.optInt("offsetY", 0);
        if (!inBounds(x, y)) {
            return;
        }

        boolean revive = action.optBoolean("revive", false);
        if (revive) {
            unit.hp = unit.maxHp;
            unit.visible = true;
        }

        unit.clearMovePath();
        unit.x = x;
        unit.y = y;
        unit.targetX = x;
        unit.targetY = y;
        unit.lastMoveStepAt = 0L;

        int direction = action.optInt("direction", -1);
        if (direction >= 0) {
            unit.direction = direction;
        }

        lastCombatMessage = unit.name
                + " → " + anchor.name
                + " 상대 위치 이동";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void applyAiPolicyAction(JSONObject action) {
        int targetMode = action.optInt("targetMode", 0);

        if (targetMode != 1) {
            BattleUnit unit = findUnitByCharacterId(
                    action.optInt("characterId", -1));
            if (unit != null) {
                applyAiPolicy(unit, action);
            }
            return;
        }

        int x1 = action.optInt("x1", 0);
        int y1 = action.optInt("y1", 0);
        int x2 = action.optInt("x2", mapCols - 1);
        int y2 = action.optInt("y2", mapRows - 1);
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        int camp = action.optInt("camp", 6);

        for (BattleUnit unit : units) {
            if (!unit.visible
                    || !unit.isAlive()
                    || unit.x < left
                    || unit.x > right
                    || unit.y < top
                    || unit.y > bottom
                    || !matchesCamp(unit, camp)) {
                continue;
            }
            applyAiPolicy(unit, action);
        }
    }

    private void applyAiPolicy(
            BattleUnit unit,
            JSONObject action) {
        unit.aiPolicy = action.optInt("policy", unit.aiPolicy);
        unit.aiTargetCharacterId = action.optInt(
                "targetCharacterId",
                -1);
        unit.aiTargetX = action.optInt("targetX", -1);
        unit.aiTargetY = action.optInt("targetY", -1);

        lastCombatMessage = unit.name
                + " AI 정책 " + unit.aiPolicy;
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }


    private void prepareScriptActionSequence(JSONArray actions) {
        activeBattleActions = actions;
        activeBattleActionIndex = 0;
        battleActionStack.clear();
        battleActionIndexStack.clear();
        battleConditionalStack.clear();
        lastBattleConditionalTaken = false;
        activeChoiceAction = null;
        duelFirstUnit = null;
        duelSecondUnit = null;
        battleEventWaitUntil = SystemClock.uptimeMillis() + 80L;
        battleEventMovingUnit = null;
        scriptEventActive = true;
    }

    private void startVictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_00 적군 전멸");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "victory";
        battleVictory = true;
        pendingCounterAttacker = null;
        pendingCounterTarget = null;
        pendingCounterAt = 0L;
        activeEnemy = null;
        activeEnemyTarget = null;
        activeEnemyAttackPending = false;
        enemyTurnOrder.clear();
        clearReachable();

        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_00 승리 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startPostBattleCleanup() {
        outcomeStage = "postBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS00Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_00 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS00Outcome() {
        outcomeFlowActive = false;
        outcomeStage = "complete";
        s00Complete = true;

        if (r01StoryScenes != null && r01StoryScenes.length() > 0) {
            startR01Story();
        } else {
            endBattle(true, "S_00 원본 승리 흐름 완료");
        }
    }

    private void startR01Story() {
        r01StoryActive = true;
        r01StorySceneIndex = 0;
        s01Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        clearReachable();
        startR01StoryScene();
    }

    private void startR01StoryScene() {
        if (!r01StoryActive || r01StoryScenes == null) {
            return;
        }
        if (r01StorySceneIndex >= r01StoryScenes.length()) {
            r01StoryActive = false;
            s01Ready = true;
            enterS01Battle();
            return;
        }

        JSONObject scene = r01StoryScenes.optJSONObject(r01StorySceneIndex);
        if (scene == null) {
            r01StorySceneIndex++;
            startR01StoryScene();
            return;
        }

        JSONArray actions = scene.optJSONArray("actions");
        prepareScriptActionSequence(actions);
        int sceneNumber = scene.optInt("scene", r01StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_01 Scene " + sceneNumber
                + ("departure".equals(kind) ? " · 출전" : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR01StoryScene() {
        r01StorySceneIndex++;
        startR01StoryScene();
    }


    private String currentStoryLabel() {
        if (r02StoryActive) {
            return "R_02";
        }
        return "R_01";
    }

    private void startS01VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            startR02Story();
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s01Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_01 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS01DefeatOutcome(
            int characterId,
            String fallbackReason) {
        if (battleEnded || outcomeFlowActive) {
            return;
        }

        JSONArray actions = null;
        if (s01DefeatOutcomeEvents != null && characterId >= 0) {
            JSONObject entry = s01DefeatOutcomeEvents.optJSONObject(
                    String.valueOf(characterId));
            if (entry != null && entry.optBoolean("supported", false)) {
                actions = entry.optJSONArray("actions");
            }
        }
        if (actions == null && s01GenericDefeatActions != null) {
            actions = s01GenericDefeatActions;
        }
        if (actions == null || actions.length() == 0) {
            endBattle(false, fallbackReason);
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s01Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_01 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void stopBattleForOutcome() {
        pendingCounterAttacker = null;
        pendingCounterTarget = null;
        pendingCounterAt = 0L;
        activeEnemy = null;
        activeEnemyTarget = null;
        activeEnemyAttackPending = false;
        enemyTurnOrder.clear();
        clearReachable();
    }

    private void startS01PostBattleCleanup() {
        outcomeStage = "s01PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS01Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_01 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS01Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_01 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r02StoryScenes != null && r02StoryScenes.length() > 0) {
            startR02Story();
        } else {
            endBattle(true, "S_01 원본 승리 흐름 완료");
        }
    }

    private void startR02Story() {
        outcomeFlowActive = false;
        r02StoryActive = true;
        r02StorySceneIndex = 0;
        s02Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR02StoryScene();
    }

    private void startR02StoryScene() {
        if (!r02StoryActive || r02StoryScenes == null) {
            return;
        }
        if (r02StorySceneIndex >= r02StoryScenes.length()) {
            r02StoryActive = false;
            s02Ready = true;
            enterS02Battle();
            return;
        }

        JSONObject scene = r02StoryScenes.optJSONObject(r02StorySceneIndex);
        if (scene == null) {
            r02StorySceneIndex++;
            startR02StoryScene();
            return;
        }

        JSONArray actions = scene.optJSONArray("actions");
        prepareScriptActionSequence(actions);
        int sceneNumber = scene.optInt(
                "scene",
                r02StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_02 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR02StoryScene() {
        r02StorySceneIndex++;
        startR02StoryScene();
    }

    private String currentBattleLabel() {
        if (currentBattleIndex == 2) {
            return "S_02";
        }
        if (currentBattleIndex == 1) {
            return "S_01";
        }
        return "S_00";
    }

    private void startBattleScriptEvent(JSONObject event) {
        activeBattleEvent = event;
        prepareScriptActionSequence(event.optJSONArray("actions"));

        int section = event.optInt("section", -1);
        if (section >= 0) {
            firedBattleSections.add(section);
        }

        clearReachable();
        lastCombatMessage = currentBattleLabel()
                + " Section " + section + " 이벤트 발동";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
        invalidate();
    }

    private void finishBattleScriptEvent() {
        activeBattleEvent = null;
        activeBattleActions = null;
        activeBattleActionIndex = 0;
        battleActionStack.clear();
        battleActionIndexStack.clear();
        battleConditionalStack.clear();
        lastBattleConditionalTaken = false;
        duelFirstUnit = null;
        duelSecondUnit = null;
        battleEventMovingUnit = null;
        battleEventWaitUntil = 0L;
        scriptEventActive = false;

        if (outcomeFlowActive) {
            if ("victory".equals(outcomeStage)) {
                startPostBattleCleanup();
                invalidate();
                return;
            }
            if ("postBattle".equals(outcomeStage)) {
                finishS00Outcome();
                return;
            }
            if ("s01Victory".equals(outcomeStage)
                    || "s01Defeat".equals(outcomeStage)) {
                startS01PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s01PostBattle".equals(outcomeStage)) {
                finishS01Outcome();
                return;
            }
        }

        if (r01StoryActive) {
            finishR01StoryScene();
            return;
        }
        if (r02StoryActive) {
            finishR02StoryScene();
            return;
        }

        checkBattleState();
        if (!battleEnded
                && !phaseTransitionActive
                && playerTurn) {
            if (selectedUnit == null
                    || !selectedUnit.visible
                    || !selectedUnit.isAlive()) {
                selectFirstPlayer();
            }
            refreshReachable();
        }
        invalidate();
    }

    private boolean battleEventConditionsSatisfied(JSONObject event) {
        JSONArray trueVariables = event.optJSONArray(
                "requireTrueVariables");
        if (trueVariables != null) {
            for (int i = 0; i < trueVariables.length(); i++) {
                int id = trueVariables.optInt(i, -1);
                if (id >= 0
                        && scenarioVariables.getOrDefault(id, 0) == 0) {
                    return false;
                }
            }
        }

        JSONArray falseVariables = event.optJSONArray(
                "requireFalseVariables");
        if (falseVariables != null) {
            for (int i = 0; i < falseVariables.length(); i++) {
                int id = falseVariables.optInt(i, -1);
                if (id >= 0
                        && scenarioVariables.getOrDefault(id, 0) != 0) {
                    return false;
                }
            }
        }

        JSONArray triggers = event.optJSONArray("triggers");
        if (triggers == null || triggers.length() == 0) {
            return false;
        }

        for (int i = 0; i < triggers.length(); i++) {
            JSONObject trigger = triggers.optJSONObject(i);
            if (trigger == null || !battleTriggerSatisfied(trigger)) {
                return false;
            }
        }
        return true;
    }

    private boolean battleTriggerSatisfied(JSONObject trigger) {
        String type = trigger.optString("type", "");

        switch (type) {
            case "position":
                return anyMatchingUnitAt(
                        trigger.optInt("personCode", -1),
                        trigger.optInt("x", -1),
                        trigger.optInt("y", -1));

            case "area":
                return anyMatchingUnitInArea(
                        trigger.optInt("personCode", -1),
                        trigger.optInt("x1", -1),
                        trigger.optInt("y1", -1),
                        trigger.optInt("x2", -1),
                        trigger.optInt("y2", -1));

            case "unitHpEqualsZero": {
                BattleUnit unit = findUnitByCharacterId(
                        trigger.optInt("characterId", -1));
                return unit != null && unit.hp <= 0;
            }

            case "roundCompare":
                return compareScenarioInt(
                        round,
                        trigger.optInt("value", 0),
                        trigger.optInt("compare", 2));

            case "side": {
                int side = trigger.optInt("side", -1);
                if (side == 0) {
                    return playerTurn;
                }
                if (side == 1) {
                    return false;
                }
                return !playerTurn;
            }

            case "campCount": {
                int count = countCampUnits(
                        trigger.optInt("camp", 6),
                        trigger.optBoolean("area", false),
                        trigger.optInt("x1", 0),
                        trigger.optInt("y1", 0),
                        trigger.optInt("x2", mapCols - 1),
                        trigger.optInt("y2", mapRows - 1));
                return compareScenarioInt(
                        count,
                        trigger.optInt("value", 0),
                        trigger.optInt("compare", 2));
            }

            case "adjacent": {
                BattleUnit first = findUnitByCharacterId(
                        trigger.optInt("firstCharacterId", -1));
                BattleUnit second = findUnitByCharacterId(
                        trigger.optInt("secondCharacterId", -1));
                if (first == null
                        || second == null
                        || !first.visible
                        || !second.visible
                        || !first.isAlive()
                        || !second.isAlive()) {
                    return false;
                }

                int distance = Math.abs(first.x - second.x)
                        + Math.abs(first.y - second.y);
                if (distance != 1) {
                    return false;
                }

                if (trigger.optBoolean("requireAttackable", false)) {
                    return supportsAttackRange(first)
                            && isInAttackRange(first, second);
                }
                return true;
            }

            default:
                return false;
        }
    }

    private boolean compareScenarioInt(
            int actual,
            int expected,
            int compare) {
        if (compare == 0) {
            return actual >= expected;
        }
        if (compare == 1) {
            return actual < expected;
        }
        return actual == expected;
    }

    private boolean anyMatchingUnitAt(
            int personCode,
            int x,
            int y) {
        for (BattleUnit unit : units) {
            if (unit.visible
                    && unit.isAlive()
                    && unit.x == x
                    && unit.y == y
                    && matchesPersonCode(unit, personCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyMatchingUnitInArea(
            int personCode,
            int x1,
            int y1,
            int x2,
            int y2) {
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);

        for (BattleUnit unit : units) {
            if (unit.visible
                    && unit.isAlive()
                    && unit.x >= left
                    && unit.x <= right
                    && unit.y >= top
                    && unit.y <= bottom
                    && matchesPersonCode(unit, personCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPersonCode(
            BattleUnit unit,
            int personCode) {
        if (personCode == 1024) {
            return true;
        }
        if (personCode == 1025) {
            return !unit.isEnemy();
        }
        if (personCode == 1026) {
            return unit.isEnemy();
        }
        if (personCode == 1027) {
            return unit == selectedUnit && unit.isPlayer();
        }
        return unit.characterId == personCode;
    }

    private int countCampUnits(
            int camp,
            boolean area,
            int x1,
            int y1,
            int x2,
            int y2) {
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        int count = 0;

        for (BattleUnit unit : units) {
            if (!unit.visible || !unit.isAlive()) {
                continue;
            }
            if (area
                    && (unit.x < left
                    || unit.x > right
                    || unit.y < top
                    || unit.y > bottom)) {
                continue;
            }
            if (matchesCamp(unit, camp)) {
                count++;
            }
        }
        return count;
    }

    private boolean matchesCamp(BattleUnit unit, int camp) {
        boolean reinforcement = reinforcementCharacterIds.contains(
                unit.characterId);

        switch (camp) {
            case 0:
                return unit.isPlayer();
            case 1:
                return "ally".equals(unit.faction);
            case 2:
                return unit.isEnemy() && !reinforcement;
            case 3:
                return unit.isEnemy() && reinforcement;
            case 4:
                return !unit.isEnemy();
            case 5:
                return unit.isEnemy();
            case 6:
                return true;
            default:
                return false;
        }
    }

    private boolean anyUnitMoving() {
        for (BattleUnit unit : units) {
            if (unit.visible
                    && unit.isAlive()
                    && unit.isMoving()) {
                return true;
            }
        }
        return false;
    }

    private boolean pumpPhaseTransitionEvents() {
        if (!phaseTransitionActive || battleEnded) {
            return false;
        }
        if (dialogueText != null) {
            return true;
        }

        long now = SystemClock.uptimeMillis();

        if (phaseMovingUnit != null) {
            if (!phaseMovingUnit.isMoving()) {
                phaseMovingUnit = null;
                phaseEventIndex++;
                phaseEventWaitUntil = now + 90L;
            } else {
                return true;
            }
        }

        if (now < phaseEventWaitUntil) {
            return true;
        }

        while (phaseEventIndex < phaseTransitionEvents.size()) {
            OpeningEvent event = phaseTransitionEvents.get(phaseEventIndex);

            switch (event.type) {
                case "dialogue":
                    dialogueSpeaker = event.speaker;
                    dialogueText = event.text;
                    return true;

                case "delay":
                    phaseEventIndex++;
                    phaseEventWaitUntil = now
                            + Math.max(100L, event.value * 80L);
                    return true;

                case "move": {
                    BattleUnit unit = findUnitByCharacterId(
                            event.characterId);
                    if (unit == null) {
                        phaseEventIndex++;
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
                        phaseMovingUnit = unit;
                        return true;
                    }
                    phaseEventIndex++;
                    phaseEventWaitUntil = now + 90L;
                    return true;
                }

                case "reveal": {
                    BattleUnit unit = findUnitByCharacterId(
                            event.characterId);
                    if (unit != null) {
                        unit.visible = true;
                    }
                    phaseEventIndex++;
                    phaseEventWaitUntil = now + 120L;
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
                    phaseEventIndex++;
                    phaseEventWaitUntil = now + 140L;
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
                    phaseEventIndex++;
                    phaseEventWaitUntil = now + 130L;
                    return true;
                }

                case "action": {
                    BattleUnit unit = findUnitByCharacterId(
                            event.characterId);
                    if (unit != null) {
                        unit.actionFrame = event.value;
                        unit.actionUntil = now + 420L;
                    }
                    phaseEventIndex++;
                    phaseEventWaitUntil = now + 420L;
                    return true;
                }

                case "music":
                    musicTrack = event.value;
                    phaseEventIndex++;
                    break;

                case "sound":
                    lastSound = event.value;
                    phaseEventIndex++;
                    break;

                case "reward":
                    lastCombatMessage = "원본 보상 이벤트 · 아이템 "
                            + event.value + " → 인물 " + event.targetId;
                    combatMessageUntil = now + 1800L;
                    phaseEventIndex++;
                    break;

                case "setVariable":
                    if (event.variableId >= 0) {
                        scenarioVariables.put(event.variableId, event.value);
                    }
                    phaseEventIndex++;
                    break;

                case "turnLimit":
                    turnLimit = Math.max(1, event.value);
                    phaseEventIndex++;
                    break;

                case "objective":
                    objectiveText = event.text;
                    phaseEventIndex++;
                    break;

                case "objectivePopup":
                    objectivePopupText = event.text;
                    phaseEventIndex++;
                    break;

                case "phaseComplete":
                    battlePhase = Math.max(2, event.value);
                    turnLimit = phase2TurnLimit;
                    if (phase2ObjectiveText != null
                            && !phase2ObjectiveText.isEmpty()) {
                        objectiveText = phase2ObjectiveText;
                    }
                    if (phase2PopupText != null
                            && !phase2PopupText.isEmpty()) {
                        objectivePopupText = phase2PopupText;
                    }
                    phaseEventIndex++;
                    finishPhaseTransition();
                    return false;

                default:
                    phaseEventIndex++;
                    break;
            }
        }

        finishPhaseTransition();
        return false;
    }

    private void startPhaseTransition() {
        if (battleEnded || phaseTransitionActive || battlePhase != 1) {
            return;
        }
        phaseTransitionActive = true;
        phaseEventIndex = 0;
        phaseEventWaitUntil = SystemClock.uptimeMillis() + 120L;
        phaseMovingUnit = null;
        pendingCounterAttacker = null;
        pendingCounterTarget = null;
        pendingCounterAt = 0L;
        clearReachable();
        lastCombatMessage = "마을 도착 · 원본 목표 전환 이벤트";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void finishPhaseTransition() {
        phaseTransitionActive = false;
        phaseMovingUnit = null;
        battlePhase = 2;
        turnLimit = phase2TurnLimit;
        lastCombatMessage = "2단계 · "
                + (phase2PopupText == null
                || phase2PopupText.isEmpty()
                ? "적군을 전멸시켜라!"
                : phase2PopupText);
        combatMessageUntil = SystemClock.uptimeMillis() + 2200L;
        if (playerTurn && !battleEnded) {
            selectFirstPlayer();
            refreshReachable();
        }
        invalidate();
    }

    private void checkBattleState() {
        if (!openingFinished
                || battleEnded
                || r01StoryActive
                || r02StoryActive
                || phaseTransitionActive
                || scriptEventActive) {
            return;
        }

        if (currentBattleIndex == 1) {
            int[] criticalIds = {118, 0, 36};
            for (int characterId : criticalIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS01DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }

            if (round > turnLimit) {
                startS01DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }

            if (!hasAnyAliveFriendly()) {
                startS01DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            if ("enemy-annihilation".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS01VictoryOutcome();
            }
            return;
        }

        for (int characterId : protectedCharacterIds) {
            BattleUnit unit = findUnitByCharacterId(characterId);
            if (unit != null && !unit.isAlive()) {
                endBattle(
                        false,
                        unit.name + " 사망 · 원본 패배 조건");
                return;
            }
        }

        if (round > turnLimit) {
            endBattle(
                    false,
                    turnLimit + "턴 초과 · 원본 패배 조건");
            return;
        }

        if ("enemy-annihilation".equals(battleMode)) {
            if (!hasAnyAliveEnemy()) {
                endBattle(
                        true,
                        currentBattleLabel()
                                + " 적군 전멸 · 원본 승리 조건");
            }
            return;
        }

        if (battlePhase == 1) {
            for (BattleUnit unit : units) {
                if (unit.visible
                        && unit.isAlive()
                        && !unit.isEnemy()
                        && unit.x == phase1GoalX
                        && unit.y == phase1GoalY) {
                    startPhaseTransition();
                    return;
                }
            }

            for (BattleUnit unit : units) {
                if (unit.visible
                        && unit.isAlive()
                        && unit.isEnemy()
                        && unit.x == villageFailX
                        && unit.y == villageFailY) {
                    endBattle(
                            false,
                            "마을이 점령당했다 · 원본 좌표 이벤트");
                    return;
                }
            }
        } else if (!hasVisibleAliveEnemy()) {
            startVictoryOutcome();
        }
    }

    private boolean hasAnyAliveFriendly() {
        for (BattleUnit unit : units) {
            if (unit.isAlive() && !unit.isEnemy()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyAliveEnemy() {
        for (BattleUnit unit : units) {
            if (unit.isAlive() && unit.isEnemy()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVisibleAliveEnemy() {
        for (BattleUnit unit : units) {
            if (unit.visible
                    && unit.isAlive()
                    && unit.isEnemy()) {
                return true;
            }
        }
        return false;
    }

    private void endBattle(boolean victory, String reason) {
        if (battleEnded) {
            return;
        }
        battleEnded = true;
        battleVictory = victory;
        battleResultText = reason;
        phaseTransitionActive = false;
        scriptEventActive = false;
        activeChoiceAction = null;
        activeBattleEvent = null;
        activeBattleActions = null;
        battleActionStack.clear();
        battleActionIndexStack.clear();
        battleConditionalStack.clear();
        lastBattleConditionalTaken = false;
        duelFirstUnit = null;
        duelSecondUnit = null;
        battleEventMovingUnit = null;
        pendingCounterAttacker = null;
        pendingCounterTarget = null;
        pendingCounterAt = 0L;
        activeEnemy = null;
        activeEnemyTarget = null;
        activeEnemyAttackPending = false;
        enemyTurnOrder.clear();
        clearReachable();

        for (BattleUnit unit : units) {
            unit.clearMovePath();
            unit.targetX = unit.x;
            unit.targetY = unit.y;
        }

        lastCombatMessage = (victory ? "승리 · " : "패배 · ")
                + reason;
        combatMessageUntil = SystemClock.uptimeMillis() + 4000L;
        invalidate();
    }

    private int directionToward(
            BattleUnit from,
            BattleUnit to) {
        int dx = to.x - from.x;
        int dy = to.y - from.y;
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? 1 : 3;
        }
        return dy >= 0 ? 2 : 0;
    }

    private void finishOpening() {
        openingFinished = true;
        playerTurn = true;
        round = 1;
        dialogueSpeaker = null;
        dialogueText = null;
        scriptedMovingUnit = null;
        resetSideTurnState(false);
        selectFirstPlayer();
        refreshReachable();
        invalidate();
    }

    private void selectFirstPlayer() {
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;

        for (BattleUnit unit : units) {
            if (unit.isPlayer()
                    && unit.visible
                    && unit.isAlive()) {
                selectedUnit = unit;
                selectedX = unit.x;
                selectedY = unit.y;
                return;
            }
        }
    }

    private void resetSideTurnState(boolean enemySide) {
        for (BattleUnit unit : units) {
            if (unit.isEnemy() == enemySide) {
                unit.moved = false;
                unit.acted = false;
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
                || !playerTurn
                || selectedUnit == null
                || !selectedUnit.isPlayer()
                || !selectedUnit.visible
                || !selectedUnit.isAlive()
                || selectedUnit.moved
                || selectedUnit.acted
                || selectedUnit.isMoving()
                || selectedUnit.movePoints <= 0) {
            return;
        }

        PathSearch path = computeReachability(selectedUnit);
        reachableBest = path.best;
        reachablePrev = path.prev;
    }

    private PathSearch computeReachability(BattleUnit unit) {
        int total = mapCols * mapRows;
        int[] best = new int[total];
        int[] prev = new int[total];
        Arrays.fill(best, IMPASSABLE);
        Arrays.fill(prev, -1);

        int start = tileIndex(unit.x, unit.y);
        best[start] = 0;

        PriorityQueue<PathNode> queue = new PriorityQueue<>();
        queue.add(new PathNode(start, 0));

        int[] dx = {0, 1, 0, -1};
        int[] dy = {-1, 0, 1, 0};

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (current.cost != best[current.index]) {
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

                if (occupied(nx, ny, unit)) {
                    continue;
                }

                int stepCost = movementCost(unit, nx, ny);
                if (stepCost >= IMPASSABLE) {
                    continue;
                }

                int nextCost = current.cost + stepCost;
                if (nextCost > unit.movePoints) {
                    continue;
                }

                int next = tileIndex(nx, ny);
                if (nextCost >= best[next]) {
                    continue;
                }

                best[next] = nextCost;
                prev[next] = current.index;
                queue.add(new PathNode(next, nextCost));
            }
        }

        return new PathSearch(best, prev);
    }

    private boolean planSelectedMove(int tx, int ty) {
        if (selectedUnit == null
                || !playerTurn
                || !selectedUnit.isPlayer()
                || !selectedUnit.isAlive()
                || selectedUnit.moved
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

        int start = tileIndex(
                selectedUnit.x,
                selectedUnit.y);
        if (target == start) {
            return false;
        }

        List<Integer> path = reconstructPath(
                start,
                target,
                reachablePrev);
        if (path.isEmpty()) {
            return false;
        }

        selectedUnit.clearMovePath();
        selectedUnit.movePath.addAll(path);
        selectedUnit.targetX = tx;
        selectedUnit.targetY = ty;
        selectedUnit.lastMoveStepAt = 0L;
        selectedUnit.moved = true;
        clearReachable();
        return true;
    }

    private List<Integer> reconstructPath(
            int start,
            int target,
            int[] prev) {
        List<Integer> reverse = new ArrayList<>();
        int cursor = target;

        while (cursor != start && cursor >= 0) {
            reverse.add(cursor);
            cursor = prev[cursor];
        }
        if (cursor != start) {
            return Collections.emptyList();
        }

        Collections.reverse(reverse);
        return reverse;
    }

    private boolean supportsAttackRange(BattleUnit unit) {
        return unit.attackRangeId == 0
                || unit.attackRangeId == 1;
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
            return Math.max(dx, dy) == 1
                    && (dx + dy) > 0;
        }
        return false;
    }

    private boolean opposingSides(
            BattleUnit a,
            BattleUnit b) {
        return a.isEnemy() != b.isEnemy();
    }

    private boolean canStrike(
            BattleUnit attacker,
            BattleUnit target) {
        return attacker != null
                && target != null
                && attacker.visible
                && target.visible
                && attacker.isAlive()
                && target.isAlive()
                && opposingSides(attacker, target)
                && !attacker.isMoving()
                && supportsAttackRange(attacker)
                && isInAttackRange(attacker, target);
    }

    private boolean canPlayerAttack(
            BattleUnit attacker,
            BattleUnit target) {
        return openingFinished
                && !battleEnded
                && !phaseTransitionActive
                && !scriptEventActive
                && playerTurn
                && attacker != null
                && attacker.isPlayer()
                && !attacker.acted
                && canStrike(attacker, target);
    }

    private void performPlayerAttack(
            BattleUnit attacker,
            BattleUnit target) {
        if (!canPlayerAttack(attacker, target)) {
            return;
        }

        resolveStrike(
                attacker,
                target,
                false,
                true);
        scheduleCounterIfPossible(
                target,
                attacker);

        selectedUnit = attacker;
        selectedX = attacker.x;
        selectedY = attacker.y;
        clearReachable();
        invalidate();
    }

    private void performEnemyAttack(
            BattleUnit attacker,
            BattleUnit target) {
        if (!canStrike(attacker, target)
                || !attacker.isEnemy()) {
            return;
        }

        resolveStrike(
                attacker,
                target,
                false,
                true);
        scheduleCounterIfPossible(
                target,
                attacker);
    }

    private void resolveStrike(
            BattleUnit attacker,
            BattleUnit target,
            boolean counter,
            boolean consumeAction) {
        if (!canStrike(attacker, target)) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        attacker.direction = directionToward(
                attacker,
                target);
        attacker.attackStartedAt = now;
        attacker.attackUntil = now + ATTACK_ANIMATION_MS;

        if (consumeAction) {
            attacker.acted = true;
        }

        target.actionFrame = 1;
        target.actionUntil = now + ATTACK_ANIMATION_MS;

        int damage = Math.max(
                0,
                attacker.attack - target.defense);
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

        lastCombatMessage = (counter ? "반격 · " : "")
                + attacker.name
                + " → " + target.name
                + " : 피해 " + damage
                + " (ATK " + attacker.attack
                + " - DEF " + target.defense + ")"
                + suffix;
        combatMessageUntil = now + 2200L;
    }

    private void scheduleCounterIfPossible(
            BattleUnit defender,
            BattleUnit attacker) {
        pendingCounterAttacker = null;
        pendingCounterTarget = null;
        pendingCounterAt = 0L;

        if (canStrike(defender, attacker)) {
            pendingCounterAttacker = defender;
            pendingCounterTarget = attacker;
            pendingCounterAt = SystemClock.uptimeMillis()
                    + ATTACK_ANIMATION_MS;
        }
    }

    private boolean updatePendingCounter(long now) {
        if (pendingCounterAttacker == null
                || pendingCounterTarget == null) {
            return false;
        }

        if (now < pendingCounterAt) {
            return true;
        }

        BattleUnit counterAttacker = pendingCounterAttacker;
        BattleUnit counterTarget = pendingCounterTarget;
        pendingCounterAttacker = null;
        pendingCounterTarget = null;
        pendingCounterAt = 0L;

        if (canStrike(counterAttacker, counterTarget)) {
            resolveStrike(
                    counterAttacker,
                    counterTarget,
                    true,
                    false);

            if (!counterTarget.isAlive()
                    && counterTarget == selectedUnit) {
                selectFirstPlayer();
                refreshReachable();
            }
            return true;
        }

        return false;
    }

    private boolean hasActiveAttackAnimation(long now) {
        for (BattleUnit unit : units) {
            if (now < unit.attackUntil
                    || now < unit.actionUntil) {
                return true;
            }
        }
        return false;
    }

    private boolean canEndPlayerTurn() {
        if (!openingFinished
                || battleEnded
                || phaseTransitionActive
                || scriptEventActive
                || !playerTurn
                || pendingCounterAttacker != null) {
            return false;
        }

        long now = SystemClock.uptimeMillis();
        if (hasActiveAttackAnimation(now)) {
            return false;
        }

        for (BattleUnit unit : units) {
            if (unit.isPlayer()
                    && unit.visible
                    && unit.isAlive()
                    && unit.isMoving()) {
                return false;
            }
        }
        return true;
    }

    private void beginEnemyTurn() {
        if (!canEndPlayerTurn()) {
            return;
        }

        playerTurn = false;
        clearReachable();
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;

        resetSideTurnState(true);
        enemyTurnOrder.clear();
        for (BattleUnit unit : units) {
            if (unit.isEnemy()
                    && unit.visible
                    && unit.isAlive()) {
                enemyTurnOrder.add(unit);
            }
        }
        Collections.sort(
                enemyTurnOrder,
                (a, b) -> Integer.compare(
                        a.characterId,
                        b.characterId));

        enemyTurnIndex = 0;
        activeEnemy = null;
        activeEnemyTarget = null;
        activeEnemyAttackPending = false;
        enemyNextActionAt = SystemClock.uptimeMillis()
                + ENEMY_PAUSE_MS;

        lastCombatMessage = round + "턴 · 적군 행동 시작";
        combatMessageUntil = SystemClock.uptimeMillis()
                + 1200L;
        invalidate();
    }

    private boolean updateEnemyTurn(long now) {
        if (!openingFinished
                || battleEnded
                || phaseTransitionActive
                || scriptEventActive
                || playerTurn) {
            return false;
        }

        if (pendingCounterAttacker != null
                || hasActiveAttackAnimation(now)) {
            return true;
        }

        if (activeEnemy != null) {
            if (!activeEnemy.visible
                    || !activeEnemy.isAlive()) {
                finishActiveEnemy(now);
                return true;
            }

            if (activeEnemy.isMoving()) {
                return true;
            }

            if (activeEnemyAttackPending) {
                activeEnemyAttackPending = false;

                BattleUnit target = findAiAttackTarget(activeEnemy);
                activeEnemyTarget = target;
                if (target != null
                        && canStrike(activeEnemy, target)) {
                    performEnemyAttack(activeEnemy, target);
                    return true;
                }
            }

            finishActiveEnemy(now);
            return true;
        }

        if (now < enemyNextActionAt) {
            return true;
        }

        while (enemyTurnIndex < enemyTurnOrder.size()) {
            BattleUnit enemy = enemyTurnOrder.get(
                    enemyTurnIndex);
            if (!enemy.visible
                    || !enemy.isAlive()
                    || enemy.acted) {
                enemyTurnIndex++;
                continue;
            }

            if ((enemy.debuffMask & 0x08) != 0) {
                enemy.acted = true;
                lastCombatMessage = enemy.name + " · 혼란으로 행동 불가";
                combatMessageUntil = now + 900L;
                enemyTurnIndex++;
                enemyNextActionAt = now + ENEMY_PAUSE_MS;
                return true;
            }

            activeEnemy = enemy;

            BattleUnit immediateTarget = findAiAttackTarget(enemy);
            if (immediateTarget != null) {
                activeEnemyTarget = immediateTarget;
                performEnemyAttack(enemy, immediateTarget);
                return true;
            }

            BattleUnit combatTarget = findAiCombatTarget(enemy);
            activeEnemyTarget = combatTarget;

            if (!enemy.moved
                    && planAiPolicyMove(enemy, combatTarget)) {
                activeEnemyAttackPending = true;
                return true;
            }

            finishActiveEnemy(now);
            return true;
        }

        finishEnemyTurn();
        return false;
    }

    private void finishActiveEnemy(long now) {
        if (activeEnemy != null) {
            activeEnemy.acted = true;
        }
        activeEnemy = null;
        activeEnemyTarget = null;
        activeEnemyAttackPending = false;
        enemyTurnIndex++;
        enemyNextActionAt = now + ENEMY_PAUSE_MS;
    }

    private void finishEnemyTurn() {
        playerTurn = true;
        round++;
        enemyTurnOrder.clear();
        enemyTurnIndex = 0;
        activeEnemy = null;
        activeEnemyTarget = null;
        activeEnemyAttackPending = false;
        pendingCounterAttacker = null;
        pendingCounterTarget = null;
        pendingCounterAt = 0L;

        resetSideTurnState(false);
        checkBattleState();
        if (!battleEnded
                && !phaseTransitionActive
                && !scriptEventActive) {
            selectFirstPlayer();
            refreshReachable();
        }

        lastCombatMessage = battleEnded
                ? battleResultText
                : round + "턴 · 아군 행동 시작";
        combatMessageUntil = SystemClock.uptimeMillis()
                + 1200L;
        invalidate();
    }

    private BattleUnit findNearestOpponent(BattleUnit from) {
        BattleUnit best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (BattleUnit candidate : units) {
            if (!candidate.visible
                    || !candidate.isAlive()
                    || !opposingSides(from, candidate)) {
                continue;
            }

            int distance = Math.abs(candidate.x - from.x)
                    + Math.abs(candidate.y - from.y);

            if (best == null
                    || distance < bestDistance
                    || (distance == bestDistance
                    && candidate.characterId
                    < best.characterId)) {
                best = candidate;
                bestDistance = distance;
            }
        }

        return best;
    }

    private BattleUnit findAiCombatTarget(BattleUnit unit) {
        if (unit == null) {
            return null;
        }

        if (unit.aiPolicy == 3
                && unit.aiTargetCharacterId >= 0) {
            BattleUnit specified = findUnitByCharacterId(
                    unit.aiTargetCharacterId);
            if (specified != null
                    && specified.visible
                    && specified.isAlive()
                    && opposingSides(unit, specified)) {
                return specified;
            }
        }

        return findNearestOpponent(unit);
    }

    private BattleUnit findAiAttackTarget(BattleUnit unit) {
        if (unit == null) {
            return null;
        }

        if (unit.aiPolicy == 3) {
            BattleUnit specified = findAiCombatTarget(unit);
            return specified != null
                    && canStrike(unit, specified)
                    ? specified
                    : null;
        }

        BattleUnit best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (BattleUnit candidate : units) {
            if (!candidate.visible
                    || !candidate.isAlive()
                    || !opposingSides(unit, candidate)
                    || !canStrike(unit, candidate)) {
                continue;
            }

            int distance = Math.abs(candidate.x - unit.x)
                    + Math.abs(candidate.y - unit.y);
            if (best == null
                    || distance < bestDistance
                    || (distance == bestDistance
                    && candidate.characterId < best.characterId)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean planAiPolicyMove(
            BattleUnit unit,
            BattleUnit combatTarget) {
        if (unit == null) {
            return false;
        }

        switch (unit.aiPolicy) {
            case 0:
            case 2:
                unit.moved = true;
                return false;

            case 3:
                return combatTarget != null
                        && planAiMove(unit, combatTarget);

            case 4:
            case 6:
                if (inBounds(unit.aiTargetX, unit.aiTargetY)) {
                    return planAiMoveToward(
                            unit,
                            unit.aiTargetX,
                            unit.aiTargetY);
                }
                unit.moved = true;
                return false;

            case 5: {
                BattleUnit follow = findUnitByCharacterId(
                        unit.aiTargetCharacterId);
                if (follow != null
                        && follow.visible
                        && follow.isAlive()) {
                    return planAiMoveToward(
                            unit,
                            follow.x,
                            follow.y);
                }
                unit.moved = true;
                return false;
            }

            case 1:
            default:
                return combatTarget != null
                        && planAiMove(unit, combatTarget);
        }
    }

    private boolean planAiMove(
            BattleUnit unit,
            BattleUnit target) {
        if (target == null) {
            return false;
        }
        return planAiMoveToward(unit, target.x, target.y);
    }

    private boolean planAiMoveToward(
            BattleUnit unit,
            int goalX,
            int goalY) {
        if (unit == null
                || !unit.visible
                || !unit.isAlive()
                || unit.moved
                || unit.acted
                || unit.isMoving()
                || unit.movePoints <= 0
                || !inBounds(goalX, goalY)) {
            return false;
        }

        PathSearch search = computeReachability(unit);
        int start = tileIndex(unit.x, unit.y);
        int bestIndex = start;
        int bestDistance = Math.abs(goalX - unit.x)
                + Math.abs(goalY - unit.y);
        int bestCost = 0;

        for (int index = 0;
             index < search.best.length;
             index++) {
            int cost = search.best[index];
            if (cost >= IMPASSABLE
                    || cost > unit.movePoints) {
                continue;
            }

            int x = index % mapCols;
            int y = index / mapCols;
            if (index != start
                    && occupied(x, y, unit)) {
                continue;
            }

            int distance = Math.abs(goalX - x)
                    + Math.abs(goalY - y);

            if (distance < bestDistance
                    || (distance == bestDistance
                    && cost < bestCost)) {
                bestDistance = distance;
                bestCost = cost;
                bestIndex = index;
            }
        }

        if (bestIndex == start) {
            unit.moved = true;
            return false;
        }

        List<Integer> path = reconstructPath(
                start,
                bestIndex,
                search.prev);
        if (path.isEmpty()) {
            unit.moved = true;
            return false;
        }

        unit.clearMovePath();
        unit.movePath.addAll(path);
        unit.targetX = bestIndex % mapCols;
        unit.targetY = bestIndex / mapCols;
        unit.lastMoveStepAt = 0L;
        unit.moved = true;
        return true;
    }

    private int terrainAt(int x, int y) {
        if (!inBounds(x, y)) {
            return -1;
        }
        return terrainCells[tileIndex(x, y)] & 0xff;
    }

    private int movementCost(
            BattleUnit unit,
            int x,
            int y) {
        int terrainId = terrainAt(x, y);
        if (terrainId < 0
                || terrainId >= terrainTypeCount
                || unit.jobFamily < 0
                || unit.jobFamily
                >= movementCostFamilyCount) {
            return IMPASSABLE;
        }

        int index = unit.jobFamily
                * terrainTypeCount
                + terrainId;
        int raw = movementCosts[index] & 0xff;

        if (raw <= 0 || raw >= 255) {
            return IMPASSABLE;
        }
        return raw;
    }

    private boolean inBounds(int x, int y) {
        return x >= 0
                && y >= 0
                && x < mapCols
                && y < mapRows;
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

    private boolean occupied(
            int tx,
            int ty,
            BattleUnit except) {
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

        if (phaseTransitionActive) {
            phaseEventIndex++;
            phaseEventWaitUntil = SystemClock.uptimeMillis() + 80L;
        } else if (scriptEventActive) {
            activeBattleActionIndex++;
            battleEventWaitUntil = SystemClock.uptimeMillis() + 80L;
        } else {
            openingIndex++;
            openingWaitUntil = SystemClock.uptimeMillis() + 80L;
        }
        invalidate();
    }


    private void drawStoryChoiceBox(Canvas canvas) {
        if (activeChoiceAction == null) {
            return;
        }
        JSONArray options = activeChoiceAction.optJSONArray("options");
        if (options == null || options.length() == 0) {
            return;
        }

        float left = 48f;
        float right = getWidth() - 48f;
        float rowHeight = 62f;
        float totalHeight = rowHeight * options.length();
        float top = Math.max(92f, (getHeight() - totalHeight) * 0.5f);
        float bottom = top + totalHeight;

        canvas.drawRoundRect(
                new RectF(left, top, right, bottom),
                16f,
                16f,
                dialogueBackPaint);

        dialogueTextPaint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < options.length(); i++) {
            float rowTop = top + i * rowHeight;
            if (i > 0) {
                canvas.drawLine(
                        left + 12f,
                        rowTop,
                        right - 12f,
                        rowTop,
                        gridPaint);
            }
            String text = options.optString(i, "선택 " + (i + 1));
            float baseline = rowTop + rowHeight * 0.5f
                    - (dialogueTextPaint.ascent()
                    + dialogueTextPaint.descent()) / 2f;
            canvas.drawText(
                    (i + 1) + ". " + text,
                    left + 22f,
                    baseline,
                    dialogueTextPaint);
        }
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
        canvas.drawText(
                speaker,
                x,
                y,
                dialogueNamePaint);

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
        String[] paragraphs = text.replace("\r", "")
                .split("\n", -1);

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
                if (dialogueTextPaint.measureText(candidate)
                        > maxWidth
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

            if (line.length() > 0
                    && lines < maxLines) {
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

        if (event.getPointerCount() == 1
                && !scaleDetector.isInProgress()) {
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
                    float moved = Math.abs(
                            event.getX() - downX)
                            + Math.abs(
                            event.getY() - downY);

                    if (!openingFinished) {
                        if (moved < 24f
                                && dialogueText != null) {
                            advanceDialogue();
                        }
                        return true;
                    }

                    if (moved >= 24f) {
                        return true;
                    }

                    if (phaseTransitionActive || scriptEventActive) {
                        if (moved < 24f && activeChoiceAction != null) {
                            handleStoryChoiceTap(
                                    event.getX(),
                                    event.getY());
                        } else if (moved < 24f && dialogueText != null) {
                            advanceDialogue();
                        }
                        return true;
                    }

                    if (battleEnded || !playerTurn) {
                        return true;
                    }

                    long now = SystemClock.uptimeMillis();
                    if (pendingCounterAttacker != null
                            || hasActiveAttackAnimation(now)) {
                        return true;
                    }

                    if (endTurnButton.contains(
                            event.getX(),
                            event.getY())) {
                        beginEnemyTurn();
                        return true;
                    }

                    float mx = (event.getX() - offsetX)
                            / scale;
                    float my = (event.getY() - offsetY)
                            / scale;
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
                                    && canPlayerAttack(
                                    selectedUnit,
                                    hit)) {
                                performPlayerAttack(
                                        selectedUnit,
                                        hit);
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
                    return true;

                default:
                    break;
            }
        }

        return true;
    }

    private static final class PathSearch {
        final int[] best;
        final int[] prev;

        PathSearch(int[] best, int[] prev) {
            this.best = best;
            this.prev = prev;
        }
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
