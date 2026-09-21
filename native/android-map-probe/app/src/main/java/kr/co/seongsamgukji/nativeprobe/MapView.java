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
import java.util.Random;
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
    private final Set<Integer> joinedCharacterIds = new HashSet<>();
    private final Map<Integer, Integer> integerVariables = new HashMap<>();
    private final Map<Integer, Integer> rImageOverrides = new HashMap<>();
    private final Map<Integer, Integer> portraitOverrides = new HashMap<>();
    private final Map<Integer, Integer> globalValues = new HashMap<>();
    private final Map<Integer, Integer> itemInventory = new HashMap<>();
    private final Random scenarioRandom = new Random();
    private final Map<Integer, int[]> equipmentState = new HashMap<>();
    private final Map<Integer, Integer> weaponExperienceState = new HashMap<>();
    private int pendingScenarioJump = -1;
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
    private int highlightedCharacterId = -1;
    private long highlightUntil = 0L;
    private int highlightAreaX1 = -1;
    private int highlightAreaY1 = -1;
    private int highlightAreaX2 = -1;
    private int highlightAreaY2 = -1;
    private long highlightAreaUntil = 0L;

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
    private JSONArray s23GenericVictoryActions;
    private JSONObject s01DefeatOutcomeEvents;
    private JSONArray s01GenericDefeatActions;
    private JSONArray r01StoryScenes;
    private JSONArray r02StoryScenes;
    private JSONArray r03StoryScenes;
    private JSONArray r05StoryScenes;
    private JSONArray r06StoryScenes;
    private JSONArray r07StoryScenes;
    private JSONArray r08StoryScenes;
    private JSONArray r09StoryScenes;
    private JSONArray r10StoryScenes;
    private JSONArray r11StoryScenes;
    private JSONArray r12StoryScenes;
    private JSONArray r13StoryScenes;
    private JSONArray r14StoryScenes;
    private JSONArray r15StoryScenes;
    private JSONArray r16StoryScenes;
    private JSONArray r17StoryScenes;
    private JSONArray r18StoryScenes;
    private JSONArray r19StoryScenes;
    private JSONArray r20StoryScenes;
    private JSONArray r21StoryScenes;
    private JSONArray r22StoryScenes;
    private JSONArray r23StoryScenes;
    private JSONArray r24StoryScenes;
    private JSONArray r25StoryScenes;
    private JSONObject s10AttackVictoryEvents;
    private JSONObject s18VictoryOutcomeEvents;
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
    private boolean r03StoryActive = false;
    private boolean r05StoryActive = false;
    private boolean r06StoryActive = false;
    private boolean r07StoryActive = false;
    private boolean r08StoryActive = false;
    private boolean r09StoryActive = false;
    private boolean r10StoryActive = false;
    private boolean r11StoryActive = false;
    private boolean r12StoryActive = false;
    private boolean r13StoryActive = false;
    private boolean r14StoryActive = false;
    private boolean r15StoryActive = false;
    private boolean r16StoryActive = false;
    private boolean r17StoryActive = false;
    private boolean r18StoryActive = false;
    private boolean r19StoryActive = false;
    private boolean r20StoryActive = false;
    private boolean r21StoryActive = false;
    private boolean r22StoryActive = false;
    private boolean r23StoryActive = false;
    private boolean r24StoryActive = false;
    private boolean r25StoryActive = false;
    private boolean s01Ready = false;
    private boolean s02Ready = false;
    private boolean s03Ready = false;
    private boolean s05Ready = false;
    private boolean s06Ready = false;
    private boolean s07Ready = false;
    private boolean s08Ready = false;
    private boolean s09Ready = false;
    private boolean s10Ready = false;
    private boolean s11Ready = false;
    private boolean s12Ready = false;
    private boolean s13Ready = false;
    private boolean s14Ready = false;
    private boolean s15Ready = false;
    private boolean s16Ready = false;
    private boolean s17Ready = false;
    private boolean s18Ready = false;
    private boolean s19Ready = false;
    private boolean s20Ready = false;
    private boolean s21Ready = false;
    private boolean s22Ready = false;
    private boolean s23Ready = false;
    private boolean s24Ready = false;
    private boolean s25Ready = false;
    private boolean s10DefenseRoute = false;
    private boolean s12AnnihilationRoute = false;
    private int s12RetreatVariable = 2;
    private int s12AnnihilationVariable = 3;
    private JSONArray s12RetreatGoals;
    private JSONObject s12VictoryByRouteEvents;
    private boolean scriptedBattleFailure = false;
    private int currentBattleIndex = 0;
    private String battleMode = "s00-two-phase";
    private int rescueCharacterId = -1;
    private int rescueGoalX = -1;
    private int rescueGoalY = -1;
    private int killTargetCharacterId = -1;
    private String killTargetName = "";
    private int killTargetSignalSection = -1;
    private int s09CaoCaoDefeatVariable = -1;
    private int s09DirectVictoryVariable = -1;
    private int s09DirectVictoryCompletionVariable = -1;
    private int r01StorySceneIndex = 0;
    private int r02StorySceneIndex = 0;
    private int r03StorySceneIndex = 0;
    private int r05StorySceneIndex = 0;
    private int r06StorySceneIndex = 0;
    private int r07StorySceneIndex = 0;
    private int r08StorySceneIndex = 0;
    private int r09StorySceneIndex = 0;
    private int r10StorySceneIndex = 0;
    private int r11StorySceneIndex = 0;
    private int r12StorySceneIndex = 0;
    private int r13StorySceneIndex = 0;
    private int r14StorySceneIndex = 0;
    private int r15StorySceneIndex = 0;
    private int r16StorySceneIndex = 0;
    private int r17StorySceneIndex = 0;
    private int r18StorySceneIndex = 0;
    private int r19StorySceneIndex = 0;
    private int r20StorySceneIndex = 0;
    private int r21StorySceneIndex = 0;
    private int r22StorySceneIndex = 0;
    private int r23StorySceneIndex = 0;
    private int r24StorySceneIndex = 0;
    private int r25StorySceneIndex = 0;
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
            unit.maxMp = Math.max(0, u.optInt("mpMax", 0));
            unit.mp = unit.maxMp;
            unit.aiPolicy = u.optInt("aiPolicy", unit.aiPolicy);
            units.add(unit);
            if (u.optBoolean("reinforcement", false)) {
                reinforcementCharacterIds.add(unit.characterId);
            }
            ensureSprite(context, unit.activeSpriteId);
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
            JSONObject genericVictory = outcomeEvents.optJSONObject(
                    "genericVictory");
            JSONObject defeat = outcomeEvents.optJSONObject("defeat");
            JSONObject postBattle = outcomeEvents.optJSONObject("postBattle");
            if (victory != null && victory.optBoolean("supported", false)) {
                victoryOutcomeActions = victory.optJSONArray("actions");
            }
            if (defeat != null && defeat.optBoolean("supported", false)) {
                defeatOutcomeActions = defeat.optJSONArray("actions");
            }
            if (genericVictory != null
                    && genericVictory.optBoolean("supported", false)) {
                s23GenericVictoryActions = genericVictory.optJSONArray(
                        "actions");
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

        if (currentBattleIndex == 22) {
            JSONObject routeModel = battle.optJSONObject("routeModel");
            if (routeModel != null) {
                killTargetSignalSection = routeModel.optInt(
                        "targetDefeatSignalSection",
                        -1);
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

    private void enterS03Battle() {
        try {
            loadS03Battle(getContext());
            lastCombatMessage = "R_03 완료 · S_03 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            r03StoryActive = false;
            endBattle(
                    false,
                    "S_03 로드 실패 · " + e.getClass().getSimpleName());
        }
    }

    private void loadS03Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle3.json",
                3,
                "m003.jpg",
                "terrain3.bin");
    }

    private void enterS04Battle() {
        try {
            loadS04Battle(getContext());
            lastCombatMessage = "S_03 완료 · S_04 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_04 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS04Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle4.json",
                4,
                "m004.jpg",
                "terrain4.bin");
    }

    private void enterS05Battle() {
        try {
            loadS05Battle(getContext());
            lastCombatMessage = "R_05 완료 · S_05 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_05 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS05Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle5.json",
                5,
                "m005.jpg",
                "terrain5.bin");
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
            unit.maxMp = Math.max(0, u.optInt("mpMax", 0));
            unit.mp = unit.maxMp;
            unit.aiPolicy = u.optInt(
                    "aiPolicy",
                    unit.aiPolicy);
            units.add(unit);
            if (u.optBoolean("reinforcement", false)) {
                reinforcementCharacterIds.add(unit.characterId);
            }
            ensureSprite(context, unit.activeSpriteId);
        }

        objectiveText = "";
        objectivePopupText = "";
        phase2ObjectiveText = "";
        phase2PopupText = "";
        turnLimit = 20;
        phase2TurnLimit = 20;
        rescueCharacterId = -1;
        rescueGoalX = -1;
        rescueGoalY = -1;
        killTargetCharacterId = -1;
        killTargetName = "";
        killTargetSignalSection = -1;
        s09CaoCaoDefeatVariable = -1;
        s09DirectVictoryVariable = -1;
        s09DirectVictoryCompletionVariable = -1;

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
                JSONObject goal = phase1.optJSONObject("goal");
                if (goal != null
                        && "rescue-character".equals(
                        goal.optString("type", ""))) {
                    rescueCharacterId = goal.optInt(
                            "characterId",
                            -1);
                    rescueGoalX = goal.optInt("x", -1);
                    rescueGoalY = goal.optInt("y", -1);
                } else if (goal != null
                        && ("kill-character".equals(
                        goal.optString("type", ""))
                        || "kill-character-or-annihilate".equals(
                        goal.optString("type", "")))) {
                    killTargetCharacterId = goal.optInt(
                            "characterId",
                            -1);
                    killTargetName = goal.optString("name", "");
                } else if (goal != null
                        && "s09-xuzhou-rescue".equals(
                        goal.optString("type", ""))) {
                    s09CaoCaoDefeatVariable = goal.optInt(
                            "caoCaoDefeatVariable",
                            56);
                    s09DirectVictoryVariable = goal.optInt(
                            "directVictoryVariable",
                            0);
                    s09DirectVictoryCompletionVariable = goal.optInt(
                            "directVictoryCompletionVariable",
                            609);
                }
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
        s23GenericVictoryActions = null;
        s01DefeatOutcomeEvents = null;
        s01GenericDefeatActions = null;
        r02StoryScenes = null;
        r03StoryScenes = null;
        r05StoryScenes = null;
        r06StoryScenes = null;
        r07StoryScenes = null;
        r08StoryScenes = null;
        r09StoryScenes = null;
        r10StoryScenes = null;
        r11StoryScenes = null;
        r12StoryScenes = null;
        r13StoryScenes = null;
        r14StoryScenes = null;
        r15StoryScenes = null;
        r16StoryScenes = null;
        r17StoryScenes = null;
        r18StoryScenes = null;
        r19StoryScenes = null;
        r20StoryScenes = null;
        r21StoryScenes = null;
        r22StoryScenes = null;
        r23StoryScenes = null;
        r24StoryScenes = null;
        r25StoryScenes = null;
        s10AttackVictoryEvents = null;
        s18VictoryOutcomeEvents = null;
        s12VictoryByRouteEvents = null;
        s12RetreatGoals = null;
        s12RetreatVariable = 2;
        s12AnnihilationVariable = 3;
        s12AnnihilationRoute = false;

        JSONObject s01Outcomes = battle.optJSONObject("outcomeEvents");
        if (s01Outcomes != null) {
            JSONObject victory = s01Outcomes.optJSONObject("victory");
            JSONObject genericVictory = s01Outcomes.optJSONObject(
                    "genericVictory");
            JSONObject postBattle = s01Outcomes.optJSONObject("postBattle");
            JSONObject genericDefeat = s01Outcomes.optJSONObject(
                    "genericDefeat");
            if (victory != null && victory.optBoolean("supported", false)) {
                victoryOutcomeActions = victory.optJSONArray("actions");
            }
            if (genericVictory != null
                    && genericVictory.optBoolean("supported", false)) {
                s23GenericVictoryActions = genericVictory.optJSONArray(
                        "actions");
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
            s10AttackVictoryEvents = s01Outcomes.optJSONObject(
                    "attackVictoryByCharacter");
            s18VictoryOutcomeEvents = s01Outcomes.optJSONObject(
                    "victoryByCharacter");
            s12VictoryByRouteEvents = s01Outcomes.optJSONObject(
                    "victoryByRoute");
        }

        if (currentBattleIndex == 12) {
            JSONObject routeModel = battle.optJSONObject("routeModel");
            if (routeModel != null) {
                s12RetreatVariable = routeModel.optInt(
                        "retreatVariable",
                        2);
                s12AnnihilationVariable = routeModel.optInt(
                        "annihilationVariable",
                        3);
                s12RetreatGoals = routeModel.optJSONArray(
                        "retreatGoals");
            }

            s12AnnihilationRoute =
                    scenarioVariables.getOrDefault(
                            s12AnnihilationVariable,
                            0) != 0;

            if (s12AnnihilationRoute) {
                battlePhase = 2;
                if (phase2ObjectiveText != null
                        && !phase2ObjectiveText.isEmpty()) {
                    objectiveText = phase2ObjectiveText;
                }
                if (phase2PopupText != null
                        && !phase2PopupText.isEmpty()) {
                    objectivePopupText = phase2PopupText;
                }
                turnLimit = phase2TurnLimit;
            } else {
                protectedCharacterIds.clear();
                protectedCharacterIds.add(0);
            }

            if (s12VictoryByRouteEvents != null) {
                String key = s12AnnihilationRoute
                        ? String.valueOf(s12AnnihilationVariable)
                        : String.valueOf(s12RetreatVariable);
                JSONObject routeVictory =
                        s12VictoryByRouteEvents.optJSONObject(key);
                if (routeVictory != null
                        && routeVictory.optBoolean("supported", false)) {
                    victoryOutcomeActions =
                            routeVictory.optJSONArray("actions");
                }
            }
        }

        JSONObject r02Story = battle.optJSONObject("r02Story");
        if (r02Story != null
                && r02Story.optBoolean("supported", false)) {
            r02StoryScenes = r02Story.optJSONArray("scenes");
        }
        JSONObject r03Story = battle.optJSONObject("r03Story");
        if (r03Story != null
                && r03Story.optBoolean("supported", false)) {
            r03StoryScenes = r03Story.optJSONArray("scenes");
        }
        JSONObject r05Story = battle.optJSONObject("r05Story");
        if (r05Story != null
                && r05Story.optBoolean("supported", false)) {
            r05StoryScenes = r05Story.optJSONArray("scenes");
        }
        JSONObject r06Story = battle.optJSONObject("r06Story");
        if (r06Story != null
                && r06Story.optBoolean("supported", false)) {
            r06StoryScenes = r06Story.optJSONArray("scenes");
        }
        JSONObject r07Story = battle.optJSONObject("r07Story");
        if (r07Story != null
                && r07Story.optBoolean("supported", false)) {
            r07StoryScenes = r07Story.optJSONArray("scenes");
        }
        JSONObject r08Story = battle.optJSONObject("r08Story");
        if (r08Story != null
                && r08Story.optBoolean("supported", false)) {
            r08StoryScenes = r08Story.optJSONArray("scenes");
        }
        JSONObject r09Story = battle.optJSONObject("r09Story");
        if (r09Story != null
                && r09Story.optBoolean("supported", false)) {
            r09StoryScenes = r09Story.optJSONArray("scenes");
        }
        JSONObject r10Story = battle.optJSONObject("r10Story");
        if (r10Story != null
                && r10Story.optBoolean("supported", false)) {
            r10StoryScenes = r10Story.optJSONArray("scenes");
        }
        JSONObject r11Story = battle.optJSONObject("r11Story");
        if (r11Story != null
                && r11Story.optBoolean("supported", false)) {
            r11StoryScenes = r11Story.optJSONArray("scenes");
        }
        JSONObject r12Story = battle.optJSONObject("r12Story");
        if (r12Story != null
                && r12Story.optBoolean("supported", false)) {
            r12StoryScenes = r12Story.optJSONArray("scenes");
        }
        JSONObject r13Story = battle.optJSONObject("r13Story");
        if (r13Story != null
                && r13Story.optBoolean("supported", false)) {
            r13StoryScenes = r13Story.optJSONArray("scenes");
        }
        JSONObject r14Story = battle.optJSONObject("r14Story");
        if (r14Story != null
                && r14Story.optBoolean("supported", false)) {
            r14StoryScenes = r14Story.optJSONArray("scenes");
        }
        JSONObject r15Story = battle.optJSONObject("r15Story");
        if (r15Story != null
                && r15Story.optBoolean("supported", false)) {
            r15StoryScenes = r15Story.optJSONArray("scenes");
        }
        JSONObject r16Story = battle.optJSONObject("r16Story");
        if (r16Story != null
                && r16Story.optBoolean("supported", false)) {
            r16StoryScenes = r16Story.optJSONArray("scenes");
        }
        JSONObject r17Story = battle.optJSONObject("r17Story");
        if (r17Story != null
                && r17Story.optBoolean("supported", false)) {
            r17StoryScenes = r17Story.optJSONArray("scenes");
        }
        JSONObject r18Story = battle.optJSONObject("r18Story");
        if (r18Story != null
                && r18Story.optBoolean("supported", false)) {
            r18StoryScenes = r18Story.optJSONArray("scenes");
        }
        JSONObject r19Story = battle.optJSONObject("r19Story");
        if (r19Story != null
                && r19Story.optBoolean("supported", false)) {
            r19StoryScenes = r19Story.optJSONArray("scenes");
        }
        JSONObject r20Story = battle.optJSONObject("r20Story");
        if (r20Story != null
                && r20Story.optBoolean("supported", false)) {
            r20StoryScenes = r20Story.optJSONArray("scenes");
        }
        JSONObject r21Story = battle.optJSONObject("r21Story");
        if (r21Story != null
                && r21Story.optBoolean("supported", false)) {
            r21StoryScenes = r21Story.optJSONArray("scenes");
        }
        JSONObject r22Story = battle.optJSONObject("r22Story");
        if (r22Story != null
                && r22Story.optBoolean("supported", false)) {
            r22StoryScenes = r22Story.optJSONArray("scenes");
        }
        JSONObject r23Story = battle.optJSONObject("r23Story");
        if (r23Story != null
                && r23Story.optBoolean("supported", false)) {
            r23StoryScenes = r23Story.optJSONArray("scenes");
        }

        JSONObject r24Story = battle.optJSONObject("r24Story");
        if (r24Story != null
                && r24Story.optBoolean("supported", false)) {
            r24StoryScenes = r24Story.optJSONArray("scenes");
        }
        JSONObject r25Story = battle.optJSONObject("r25Story");
        if (r25Story != null
                && r25Story.optBoolean("supported", false)) {
            r25StoryScenes = r25Story.optJSONArray("scenes");
        }

        outcomeFlowActive = false;
        outcomeStage = "";
        r01StoryActive = false;
        r02StoryActive = false;
        r03StoryActive = false;
        r05StoryActive = false;
        r06StoryActive = false;
        r07StoryActive = false;
        r08StoryActive = false;
        r09StoryActive = false;
        r10StoryActive = false;
        r11StoryActive = false;
        r12StoryActive = false;
        r13StoryActive = false;
        r14StoryActive = false;
        r15StoryActive = false;
        r16StoryActive = false;
        r17StoryActive = false;
        r18StoryActive = false;
        r19StoryActive = false;
        r20StoryActive = false;
        r21StoryActive = false;
        r22StoryActive = false;
        r23StoryActive = false;
        r24StoryActive = false;
        r25StoryActive = false;
        s01Ready = false;
        s02Ready = false;
        s03Ready = false;
        s05Ready = false;
        s06Ready = false;
        s07Ready = false;
        s08Ready = false;
        s09Ready = false;
        s10Ready = false;
        s11Ready = false;
        s12Ready = false;
        s13Ready = false;
        s14Ready = false;
        s15Ready = false;
        s16Ready = false;
        s17Ready = false;
        s18Ready = false;
        s19Ready = false;
        s20Ready = false;
        s21Ready = false;
        s22Ready = false;
        s23Ready = false;
        s24Ready = false;
        s25Ready = false;
        r01StorySceneIndex = 0;
        r02StorySceneIndex = 0;
        r03StorySceneIndex = 0;
        r05StorySceneIndex = 0;
        r06StorySceneIndex = 0;
        r07StorySceneIndex = 0;
        r08StorySceneIndex = 0;
        r09StorySceneIndex = 0;
        r10StorySceneIndex = 0;
        r11StorySceneIndex = 0;
        r12StorySceneIndex = 0;
        r13StorySceneIndex = 0;
        r14StorySceneIndex = 0;
        r15StorySceneIndex = 0;
        r16StorySceneIndex = 0;
        r17StorySceneIndex = 0;
        r18StorySceneIndex = 0;
        r19StorySceneIndex = 0;
        r20StorySceneIndex = 0;
        r21StorySceneIndex = 0;
        r22StorySceneIndex = 0;
        r23StorySceneIndex = 0;
        r24StorySceneIndex = 0;
        r25StorySceneIndex = 0;
        activeChoiceAction = null;
        storyTitle = "";
        storyLocation = "";
        pendingScenarioJump = -1;

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
        highlightedCharacterId = -1;
        highlightUntil = 0L;
        highlightAreaX1 = -1;
        highlightAreaY1 = -1;
        highlightAreaX2 = -1;
        highlightAreaY2 = -1;
        highlightAreaUntil = 0L;

        battlePhase = currentBattleIndex == 12
                && s12AnnihilationRoute ? 2 : 1;
        s10DefenseRoute = currentBattleIndex == 10
                && scenarioVariables.getOrDefault(71, 0) != 0;
        scriptedBattleFailure = false;
        if (currentBattleIndex == 10 && s10DefenseRoute) {
            battlePhase = 2;
            if (phase2ObjectiveText != null
                    && !phase2ObjectiveText.isEmpty()) {
                objectiveText = phase2ObjectiveText;
            }
            if (phase2PopupText != null
                    && !phase2PopupText.isEmpty()) {
                objectivePopupText = phase2PopupText;
            }
            turnLimit = phase2TurnLimit;
        }
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
        if (now < highlightAreaUntil
                && highlightAreaX1 >= 0
                && highlightAreaY1 >= 0
                && highlightAreaX2 >= highlightAreaX1
                && highlightAreaY2 >= highlightAreaY1) {
            canvas.drawRect(
                    highlightAreaX1 * TILE + 2,
                    highlightAreaY1 * TILE + 2,
                    (highlightAreaX2 + 1) * TILE - 2,
                    (highlightAreaY2 + 1) * TILE - 2,
                    selectedTilePaint);
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
        if (r25StoryActive) {
            header = "Native v4.60 | R_25 Scene "
                    + Math.min(
                    r25StorySceneIndex + 1,
                    r25StoryScenes == null
                            ? 1
                            : r25StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r24StoryActive) {
            header = "Native v4.60 | R_24 Scene "
                    + Math.min(
                    r24StorySceneIndex + 1,
                    r24StoryScenes == null
                            ? 1
                            : r24StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r23StoryActive) {
            header = "Native v4.60 | R_23 Scene "
                    + Math.min(
                    r23StorySceneIndex + 1,
                    r23StoryScenes == null
                            ? 1
                            : r23StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r22StoryActive) {
            header = "Native v4.60 | R_22 Scene "
                    + Math.min(
                    r22StorySceneIndex + 1,
                    r22StoryScenes == null
                            ? 1
                            : r22StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r21StoryActive) {
            header = "Native v4.60 | R_21 Scene "
                    + Math.min(
                    r21StorySceneIndex + 1,
                    r21StoryScenes == null
                            ? 1
                            : r21StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r20StoryActive) {
            header = "Native v4.60 | R_20 Scene "
                    + Math.min(
                    r20StorySceneIndex + 1,
                    r20StoryScenes == null
                            ? 1
                            : r20StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r19StoryActive) {
            header = "Native v4.60 | R_19 Scene "
                    + Math.min(
                    r19StorySceneIndex + 1,
                    r19StoryScenes == null
                            ? 1
                            : r19StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r18StoryActive) {
            header = "Native v4.60 | R_18 Scene "
                    + Math.min(
                    r18StorySceneIndex + 1,
                    r18StoryScenes == null
                            ? 1
                            : r18StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r17StoryActive) {
            header = "Native v4.60 | R_17 Scene "
                    + Math.min(
                    r17StorySceneIndex + 1,
                    r17StoryScenes == null
                            ? 1
                            : r17StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r16StoryActive) {
            header = "Native v4.60 | R_16 Scene "
                    + Math.min(
                    r16StorySceneIndex + 1,
                    r16StoryScenes == null
                            ? 1
                            : r16StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r15StoryActive) {
            header = "Native v4.60 | R_15 Scene "
                    + Math.min(
                    r15StorySceneIndex + 1,
                    r15StoryScenes == null
                            ? 1
                            : r15StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r14StoryActive) {
            header = "Native v4.60 | R_14 Scene "
                    + Math.min(
                    r14StorySceneIndex + 1,
                    r14StoryScenes == null
                            ? 1
                            : r14StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r13StoryActive) {
            header = "Native v4.60 | R_13 Scene "
                    + Math.min(
                    r13StorySceneIndex + 1,
                    r13StoryScenes == null
                            ? 1
                            : r13StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r01StoryActive) {
            header = "Native v4.60 | R_01 Scene "
                    + Math.min(
                    r01StorySceneIndex + 1,
                    r01StoryScenes == null
                            ? 1
                            : r01StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r02StoryActive) {
            header = "Native v4.60 | R_02 Scene "
                    + Math.min(
                    r02StorySceneIndex + 1,
                    r02StoryScenes == null
                            ? 1
                            : r02StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r03StoryActive) {
            header = "Native v4.60 | R_03 Scene "
                    + Math.min(
                    r03StorySceneIndex + 1,
                    r03StoryScenes == null
                            ? 1
                            : r03StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r12StoryActive) {
            header = "Native v4.60 | R_12 Scene "
                    + Math.min(
                    r12StorySceneIndex + 1,
                    r12StoryScenes == null
                            ? 1
                            : r12StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r11StoryActive) {
            header = "Native v4.60 | R_11 Scene "
                    + Math.min(
                    r11StorySceneIndex + 1,
                    r11StoryScenes == null
                            ? 1
                            : r11StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r10StoryActive) {
            header = "Native v4.60 | R_10 Scene "
                    + Math.min(
                    r10StorySceneIndex + 1,
                    r10StoryScenes == null
                            ? 1
                            : r10StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r09StoryActive) {
            header = "Native v4.60 | R_09 Scene "
                    + Math.min(
                    r09StorySceneIndex + 1,
                    r09StoryScenes == null
                            ? 1
                            : r09StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r08StoryActive) {
            header = "Native v4.60 | R_08 Scene "
                    + Math.min(
                    r08StorySceneIndex + 1,
                    r08StoryScenes == null
                            ? 1
                            : r08StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r07StoryActive) {
            header = "Native v4.60 | R_07 Scene "
                    + Math.min(
                    r07StorySceneIndex + 1,
                    r07StoryScenes == null
                            ? 1
                            : r07StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r06StoryActive) {
            header = "Native v4.60 | R_06 Scene "
                    + Math.min(
                    r06StorySceneIndex + 1,
                    r06StoryScenes == null
                            ? 1
                            : r06StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else if (r05StoryActive) {
            header = "Native v4.60 | R_05 Scene "
                    + Math.min(
                    r05StorySceneIndex + 1,
                    r05StoryScenes == null
                            ? 1
                            : r05StoryScenes.length())
                    + (storyTitle.isEmpty()
                    ? ""
                    : " · " + storyTitle);
        } else {
            header = "Native v4.60 | " + round + "/" + turnLimit + "턴 "
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
        } else if (r01StoryActive
                || r02StoryActive
                || r03StoryActive
                || r05StoryActive
                || r06StoryActive
                || r07StoryActive
                || r08StoryActive
                || r09StoryActive
                || r10StoryActive
                || r11StoryActive
                || r12StoryActive
                || r13StoryActive
                || r14StoryActive
                || r15StoryActive
                || r16StoryActive
                || r17StoryActive
                || r18StoryActive
                || r19StoryActive
                || r20StoryActive
                || r21StoryActive) {
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
                && !r03StoryActive
                && !r05StoryActive
                && !r06StoryActive
                && !r07StoryActive
                && !r08StoryActive
                && !r09StoryActive
                && !r10StoryActive
                && !r11StoryActive
                && !r12StoryActive
                && !r13StoryActive
                && !r14StoryActive
                && !r15StoryActive
                && !r16StoryActive
                && !r17StoryActive
                && !r18StoryActive
                && !r19StoryActive
                && !r20StoryActive
                && !r21StoryActive
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
                    ? String.valueOf(selectedUnit.activeAttackRangeId)
                    : selectedUnit.activeAttackRangeId + "(미지원)";

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
                || r03StoryActive
                || r05StoryActive
                || r06StoryActive
                || r07StoryActive
                || r08StoryActive
                || r09StoryActive
                || r10StoryActive
                || r11StoryActive
                || r12StoryActive
                || r13StoryActive
                || r14StoryActive
                || r15StoryActive
                || r16StoryActive
                || r17StoryActive
                || r18StoryActive
                || r19StoryActive
                || r20StoryActive
                || r21StoryActive
                || activeChoiceAction != null
                || !playerTurn
                || hasActiveAttackAnimation(now)
                || now < scriptedEffectUntil
                || now < highlightUntil
                || now < highlightAreaUntil
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
            if (cost < 0 || cost > selectedUnit.activeMovePoints) {
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
            source = attackSprites.get(unit.activeSpriteId);
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
            source = idleSprites.get(unit.activeSpriteId);
            frameIndex = source == null || source.length == 0
                    ? 0
                    : Math.floorMod(
                            unit.actionFrame,
                            source.length);
        } else if (unit.isMoving()) {
            source = moveSprites.get(unit.activeSpriteId);
            frameIndex = source == null || source.length == 0
                    ? 0
                    : Math.max(
                            0,
                            Math.min(
                                    unit.moveFrame,
                                    source.length - 1));
        } else {
            source = idleSprites.get(unit.activeSpriteId);
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

        if (unit == selectedUnit
                || (unit.characterId == highlightedCharacterId
                && now < highlightUntil)) {
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

            Bitmap[] frames = moveSprites.get(unit.activeSpriteId);
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

                    case "conditionalGlobalCompare": {
                        int globalId = action.optInt("globalId", -1);
                        int actual = globalValues.getOrDefault(
                                globalId,
                                0);
                        int expected = action.optInt("value", 0);
                        int compare = action.optInt("compare", 0);
                        boolean taken = compareScenarioInt(
                                actual,
                                expected,
                                compare);
                        lastBattleConditionalTaken = taken;
                        if (taken && enterNestedBattleActions(
                                action.optJSONArray("actions"))) {
                            break;
                        }
                        activeBattleActionIndex++;
                        break;
                    }

                    case "conditionalIntegerCompare": {
                        int variableId = action.optInt("variableId", -1);
                        int actual = integerVariables.getOrDefault(
                                variableId,
                                0);
                        int expected = action.optInt("value", 0);
                        int compare = action.optInt("compare", 0);
                        boolean taken;
                        if (compare == 0) {
                            taken = actual == expected;
                        } else if (compare == 1) {
                            taken = actual >= expected;
                        } else {
                            taken = actual < expected;
                        }
                        lastBattleConditionalTaken = taken;
                        if (taken && enterNestedBattleActions(
                                action.optJSONArray("actions"))) {
                            break;
                        }
                        activeBattleActionIndex++;
                        break;
                    }

                    case "conditionalProbability": {
                        int percent = Math.max(
                                0,
                                Math.min(100, action.optInt("percent", 0)));
                        boolean taken = scenarioRandom.nextInt(100) < percent;
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

                    case "storyChapter":
                        storyTitle = action.optString("text", storyTitle);
                        lastCombatMessage = currentStoryLabel()
                                + (storyTitle.isEmpty()
                                ? " · 장 전환"
                                : " · " + storyTitle);
                        combatMessageUntil = now + 1600L;
                        activeBattleActionIndex++;
                        break;

                    case "joinCharacter": {
                        int joinedId = action.optInt("characterId", -1);
                        if (joinedId >= 0) {
                            joinedCharacterIds.add(joinedId);
                            lastCombatMessage = "인물 합류 · " + joinedId;
                            combatMessageUntil = now + 1200L;
                        }
                        activeBattleActionIndex++;
                        break;
                    }

                    case "equipmentSet":
                        applyEquipmentSetAction(action);
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

                    case "deploymentTest": {
                        String nextBattle = "S_01";
                        if (r23StoryActive) {
                            nextBattle = "S_23";
                        } else if (r22StoryActive) {
                            nextBattle = "S_22";
                        } else if (r21StoryActive) {
                            nextBattle = "S_21";
                        } else if (r20StoryActive) {
                            nextBattle = "S_20";
                        } else if (r19StoryActive) {
                            nextBattle = "S_19";
                        } else if (r18StoryActive) {
                            nextBattle = "S_18";
                        } else if (r17StoryActive) {
                            nextBattle = "S_17";
                        } else if (r16StoryActive) {
                            nextBattle = "S_16";
                        } else if (r15StoryActive) {
                            nextBattle = "S_15";
                        } else if (r14StoryActive) {
                            nextBattle = "S_14";
                        } else if (r13StoryActive) {
                            nextBattle = "S_13";
                        } else if (r12StoryActive) {
                            nextBattle = "S_12";
                        } else if (r11StoryActive) {
                            nextBattle = "S_11";
                        } else if (r10StoryActive) {
                            nextBattle = "S_10";
                        } else if (r09StoryActive) {
                            nextBattle = "S_09";
                        } else if (r08StoryActive) {
                            nextBattle = "S_08";
                        } else if (r07StoryActive) {
                            nextBattle = "S_07";
                        } else if (r06StoryActive) {
                            nextBattle = "S_06";
                        } else if (r05StoryActive) {
                            nextBattle = "S_05";
                        } else if (r03StoryActive) {
                            nextBattle = "S_03";
                        } else if (r02StoryActive) {
                            nextBattle = "S_02";
                        }
                        lastCombatMessage = "원본 출전 확인 · "
                                + nextBattle + " 준비";
                        combatMessageUntil = now + 1000L;
                        activeBattleActionIndex++;
                        break;
                    }

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

                    case "revealBattleNumber": {
                        BattleUnit unit = findUnitByBattleNumber(
                                action.optInt("battleNumber", -1));
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

                    case "retreatArea":
                        applyRetreatAreaAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 140L;
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

                    case "discardItem": {
                        int itemId = action.optInt("itemId", -1);
                        int count = Math.max(1, action.optInt("count", 1));
                        int current = itemInventory.getOrDefault(itemId, 0);
                        int remaining = Math.max(0, current - count);
                        if (remaining > 0) {
                            itemInventory.put(itemId, remaining);
                        } else {
                            itemInventory.remove(itemId);
                        }
                        lastCombatMessage = "원본 아이템 제거 · "
                                + itemId
                                + " ×" + count;
                        combatMessageUntil = now + 1100L;
                        activeBattleActionIndex++;
                        break;
                    }

                    case "battlefieldObject":
                        applyBattlefieldObjectAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 160L;
                        return true;

                    case "battlefieldObjectAdd":
                        applyBattlefieldObjectAddAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 160L;
                        return true;

                    case "unitAttributeTransfer":
                        applyUnitAttributeTransferAction(action);
                        activeBattleActionIndex++;
                        break;

                    case "unitPanelChange":
                        applyUnitPanelChangeAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 120L;
                        return true;

                    case "aiAreaLimit":
                        applyAiAreaLimitAction(action);
                        activeBattleActionIndex++;
                        break;

                    case "intVariableOp":
                        applyIntegerVariableAction(action);
                        activeBattleActionIndex++;
                        break;

                    case "unitMaxHpChange":
                        applyUnitMaxHpChangeAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 120L;
                        return true;

                    case "unitHpChange":
                        applyUnitHpChangeAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 120L;
                        return true;

                    case "highlightArea":
                        applyHighlightAreaAction(action, now);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 520L;
                        return true;

                    case "highlightUnit":
                        applyHighlightUnitAction(action, now);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 520L;
                        return true;

                    case "jobChange":
                        applyJobChangeAction(action);
                        activeBattleActionIndex++;
                        break;

                    case "specialSprite":
                        applySpecialSpriteAction(action);
                        activeBattleActionIndex++;
                        battleEventWaitUntil = now + 120L;
                        return true;

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
                        scriptedBattleFailure = true;
                        lastCombatMessage = "원본 패배 처리";
                        combatMessageUntil = now + 900L;
                        activeBattleActionIndex++;
                        break;

                    case "scenarioJump":
                        pendingScenarioJump = action.optInt("target", -1);
                        lastCombatMessage = "원본 시나리오 점프 · "
                                + pendingScenarioJump;
                        combatMessageUntil = now + 900L;
                        activeBattleActionIndex++;
                        break;

                    case "endSection":
                        battleActionStack.clear();
                        battleActionIndexStack.clear();
                        battleConditionalStack.clear();
                        activeBattleActionIndex = activeBattleActions.length();
                        lastCombatMessage = "원본 Section 종료";
                        combatMessageUntil = now + 500L;
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
        scriptedBattleFailure = false;
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

    private void applyRetreatAreaAction(JSONObject action) {
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
        boolean kill = action.optBoolean("kill", false);

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

            if (kill) {
                unit.hp = 0;
            }
            unit.visible = false;
            unit.clearMovePath();
            unit.targetX = unit.x;
            unit.targetY = unit.y;
        }
    }

    private void applyBattlefieldObjectAddAction(JSONObject action) {
        // 0x21 is an S-scene visual object action. The original editor
        // stores a packed coordinate/action/type plus viewpoint/sound flags.
        // Keep the event observable without inventing an unverified terrain
        // mutation; 0x58 remains the command that changes terrain state.
        lastCombatMessage = "전장 물체 연출 · 좌표 "
                + action.optInt("coordinate", -1)
                + " · 동작 " + action.optInt("action", -1)
                + " · 유형 " + action.optInt("objectType", -1);
        combatMessageUntil = SystemClock.uptimeMillis() + 900L;
        if (action.optBoolean("sound", false)) {
            lastSound = action.optInt("objectType", lastSound);
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


    private void applyUnitPanelChangeAction(JSONObject action) {
        BattleUnit unit = findUnitByCharacterId(
                action.optInt("characterId", -1));
        if (unit == null) {
            return;
        }

        int panel = action.optInt("panel", -1);
        int operation = action.optInt("operation", 0);
        int value = action.optInt("value", 0);

        // S08 currently uses panel 2 (Spirit) + 10.
        if (panel == 2) {
            int current = unit.spiritBonus;
            if (operation == 0) {
                unit.spiritBonus = value;
            } else if (operation == 1) {
                unit.spiritBonus = current + value;
            } else if (operation == 2) {
                unit.spiritBonus = current - value;
            }
            lastCombatMessage = unit.name
                    + " 정신력 보정 "
                    + (unit.spiritBonus >= 0 ? "+" : "")
                    + unit.spiritBonus;
            combatMessageUntil = SystemClock.uptimeMillis() + 1000L;
        }
    }

    private void applyAiAreaLimitAction(JSONObject action) {
        JSONArray ids = action.optJSONArray("characterIds");
        if (ids == null) {
            return;
        }

        boolean enabled = action.optBoolean("enabled", true);
        int left = Math.min(
                action.optInt("x1", 0),
                action.optInt("x2", 255));
        int right = Math.max(
                action.optInt("x1", 0),
                action.optInt("x2", 255));
        int top = Math.min(
                action.optInt("y1", 0),
                action.optInt("y2", 255));
        int bottom = Math.max(
                action.optInt("y1", 0),
                action.optInt("y2", 255));

        for (int i = 0; i < ids.length(); i++) {
            BattleUnit unit = findUnitByCharacterId(ids.optInt(i, -1));
            if (unit == null) {
                continue;
            }
            unit.aiAreaEnabled = enabled;
            unit.aiAreaLeft = left;
            unit.aiAreaTop = top;
            unit.aiAreaRight = right;
            unit.aiAreaBottom = bottom;
        }
    }

    private boolean insideAiArea(BattleUnit unit, int x, int y) {
        return unit == null
                || !unit.aiAreaEnabled
                || (x >= unit.aiAreaLeft
                && x <= unit.aiAreaRight
                && y >= unit.aiAreaTop
                && y <= unit.aiAreaBottom);
    }

    private void applyUnitAttributeTransferAction(JSONObject action) {
        int variableId = action.optInt("variableId", -1);
        int direction = action.optInt("direction", 0);
        int characterId = action.optInt("characterId", -1);
        int attribute = action.optInt("attribute", -1);
        if (variableId < 0 || characterId < 0) {
            return;
        }

        // AllCondition[0] = R image. R-story actors do not have to be
        // present as battlefield units, so preserve this state separately.
        if (attribute == 0) {
            if (direction == 0) {
                integerVariables.put(
                        variableId,
                        rImageOverrides.getOrDefault(characterId, 0));
            } else if (direction == 1) {
                int value = integerVariables.getOrDefault(variableId, 0);
                rImageOverrides.put(characterId, value);
                lastCombatMessage = "R형상 변경 · 인물 "
                        + characterId + " → " + value;
                combatMessageUntil = SystemClock.uptimeMillis() + 900L;
            }
            return;
        }

        // AllCondition[1] = portrait. R08 changes Cao Cao's portrait and
        // R image together from the same integer variable.
        if (attribute == 1) {
            if (direction == 0) {
                integerVariables.put(
                        variableId,
                        portraitOverrides.getOrDefault(characterId, 0));
            } else if (direction == 1) {
                int value = integerVariables.getOrDefault(variableId, 0);
                portraitOverrides.put(characterId, value);
                lastCombatMessage = "초상 변경 · 인물 "
                        + characterId + " → " + value;
                combatMessageUntil = SystemClock.uptimeMillis() + 900L;
            }
            return;
        }

        if (attribute == 20 || attribute == 21 || attribute == 22) {
            int[] state = equipmentState.get(characterId);
            if (state == null) {
                state = new int[] {0, 0, 0, 0, 0};
                equipmentState.put(characterId, state);
            }
            if (direction == 0) {
                int value = attribute == 20
                        ? state[0]
                        : attribute == 21
                        ? state[1]
                        : weaponExperienceState.getOrDefault(
                        characterId,
                        0);
                integerVariables.put(variableId, value);
            } else if (direction == 1) {
                int value = integerVariables.getOrDefault(variableId, 0);
                if (attribute == 20) {
                    state[0] = value;
                } else if (attribute == 21) {
                    state[1] = value;
                } else {
                    weaponExperienceState.put(characterId, value);
                }
            }
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
            } else if (attribute == 8) {
                value = unit.maxMp;
            } else if (attribute == 32) {
                value = unit.direction;
            } else if (attribute == 33) {
                value = unit.hp;
            } else if (attribute == 34) {
                value = unit.mp;
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
                return;
            }
            if (attribute == 34) {
                unit.mp = Math.max(0, Math.min(unit.maxMp, value));
            }
        }
    }

    private void applyJobChangeAction(JSONObject action) {
        BattleUnit unit = findUnitByCharacterId(
                action.optInt("characterId", -1));
        if (unit == null) {
            return;
        }
        unit.activeJobId = action.optInt("jobId", unit.activeJobId);
        unit.activeJobFamily = action.optInt(
                "jobFamily",
                unit.activeJobFamily);
        unit.activeMovePoints = Math.max(
                0,
                action.optInt("movePoints", unit.activeMovePoints));
        unit.activeAttackRangeId = action.optInt(
                "attackRangeId",
                unit.activeAttackRangeId);
        if (unit == selectedUnit && unit.isPlayer()) {
            refreshReachable();
        }
        lastCombatMessage = unit.name
                + " 병종 변경 · " + unit.activeJobId;
        combatMessageUntil = SystemClock.uptimeMillis() + 900L;
    }

    private void applySpecialSpriteAction(JSONObject action) {
        BattleUnit unit = findUnitByCharacterId(
                action.optInt("characterId", -1));
        if (unit == null) {
            return;
        }
        int spriteId = action.optInt("spriteId", unit.activeSpriteId);
        try {
            ensureSprite(getContext(), spriteId);
            unit.activeSpriteId = spriteId;
            lastCombatMessage = unit.name
                    + " S형상 변경 · " + spriteId;
        } catch (IOException e) {
            lastCombatMessage = "S형상 로드 실패 · " + spriteId;
        }
        combatMessageUntil = SystemClock.uptimeMillis() + 900L;
    }


    private void applyEquipmentSetAction(JSONObject action) {
        int characterId = action.optInt("characterId", -1);
        if (characterId < 0) {
            return;
        }

        int weaponCode = action.optInt("weaponCode", 0);
        int weaponLevel = action.optInt("weaponLevel", 0);
        int armorCode = action.optInt("armorCode", 0);
        int armorLevel = action.optInt("armorLevel", 0);
        int auxiliaryCode = action.optInt("auxiliaryCode", 0);

        equipmentState.put(
                characterId,
                new int[] {
                        weaponCode,
                        weaponLevel,
                        armorCode,
                        armorLevel,
                        auxiliaryCode
                });

        lastCombatMessage = "장비 설정 · 인물 "
                + characterId
                + " · 무기 " + weaponCode
                + " / 방어구 " + armorCode
                + " / 보조 " + auxiliaryCode;
        combatMessageUntil = SystemClock.uptimeMillis() + 1000L;
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


    private void applyUnitMaxHpChangeAction(JSONObject action) {
        BattleUnit unit = findUnitByCharacterId(
                action.optInt("characterId", -1));
        if (unit == null) {
            return;
        }

        int value = action.optInt("value", unit.maxHp);
        int operation = action.optInt("operation", 0);
        int next = unit.maxHp;
        if (operation == 0) {
            next = value;
        } else if (operation == 1) {
            next = unit.maxHp + value;
        } else if (operation == 2) {
            next = unit.maxHp - value;
        }

        unit.maxHp = Math.max(1, next);
        unit.hp = Math.min(unit.hp, unit.maxHp);
        lastCombatMessage = unit.name
                + " 최대 HP " + unit.maxHp;
        combatMessageUntil = SystemClock.uptimeMillis() + 1000L;
    }

    private void applyUnitHpChangeAction(JSONObject action) {
        BattleUnit unit = findUnitByCharacterId(
                action.optInt("characterId", -1));
        if (unit == null) {
            return;
        }

        int value = action.optInt("value", unit.hp);
        int operation = action.optInt("operation", 0);
        int next = unit.hp;
        if (operation == 0) {
            next = value;
        } else if (operation == 1) {
            next = unit.hp + value;
        } else if (operation == 2) {
            next = unit.hp - value;
        }

        unit.hp = Math.max(0, Math.min(unit.maxHp, next));
        lastCombatMessage = unit.name
                + " HP " + unit.hp + "/" + unit.maxHp;
        combatMessageUntil = SystemClock.uptimeMillis() + 1000L;
    }

    private void applyHighlightAreaAction(
            JSONObject action,
            long now) {
        highlightAreaX1 = Math.min(
                action.optInt("x1", -1),
                action.optInt("x2", -1));
        highlightAreaY1 = Math.min(
                action.optInt("y1", -1),
                action.optInt("y2", -1));
        highlightAreaX2 = Math.max(
                action.optInt("x1", -1),
                action.optInt("x2", -1));
        highlightAreaY2 = Math.max(
                action.optInt("y1", -1),
                action.optInt("y2", -1));
        highlightAreaUntil = now + 1100L;
        lastCombatMessage = action.optInt("mode", 1) == 0
                ? "승리 조건 지역 강조"
                : "전장 지역 강조";
        combatMessageUntil = now + 1100L;
    }

    private void applyHighlightUnitAction(
            JSONObject action,
            long now) {
        int characterId = action.optInt("characterId", -1);
        BattleUnit unit = findUnitByCharacterId(characterId);
        if (unit == null) {
            return;
        }
        highlightedCharacterId = characterId;
        highlightUntil = now + 900L;
        selectedX = unit.x;
        selectedY = unit.y;
        lastCombatMessage = unit.name + " 강조";
        combatMessageUntil = now + 900L;
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
        if (r23StoryActive) {
            return "R_23";
        }
        if (r22StoryActive) {
            return "R_22";
        }
        if (r21StoryActive) {
            return "R_21";
        }
        if (r20StoryActive) {
            return "R_20";
        }
        if (r19StoryActive) {
            return "R_19";
        }
        if (r18StoryActive) {
            return "R_18";
        }
        if (r17StoryActive) {
            return "R_17";
        }
        if (r16StoryActive) {
            return "R_16";
        }
        if (r15StoryActive) {
            return "R_15";
        }
        if (r14StoryActive) {
            return "R_14";
        }
        if (r13StoryActive) {
            return "R_13";
        }
        if (r12StoryActive) {
            return "R_12";
        }
        if (r11StoryActive) {
            return "R_11";
        }
        if (r10StoryActive) {
            return "R_10";
        }
        if (r09StoryActive) {
            return "R_09";
        }
        if (r08StoryActive) {
            return "R_08";
        }
        if (r07StoryActive) {
            return "R_07";
        }
        if (r06StoryActive) {
            return "R_06";
        }
        if (r05StoryActive) {
            return "R_05";
        }
        if (r03StoryActive) {
            return "R_03";
        }
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


    private void startS02VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            startR03Story();
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s02Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_02 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS02DefeatOutcome(
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
        outcomeStage = "s02Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_02 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS02PostBattleCleanup() {
        outcomeStage = "s02PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS02Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_02 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS02Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_02 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r03StoryScenes != null && r03StoryScenes.length() > 0) {
            startR03Story();
        } else {
            endBattle(true, "S_02 원본 승리 흐름 완료");
        }
    }

    private void startR03Story() {
        outcomeFlowActive = false;
        r03StoryActive = true;
        r03StorySceneIndex = 0;
        s03Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR03StoryScene();
    }

    private void startR03StoryScene() {
        if (!r03StoryActive || r03StoryScenes == null) {
            return;
        }
        if (r03StorySceneIndex >= r03StoryScenes.length()) {
            r03StoryActive = false;
            s03Ready = true;
            enterS03Battle();
            return;
        }

        JSONObject scene = r03StoryScenes.optJSONObject(r03StorySceneIndex);
        if (scene == null) {
            r03StorySceneIndex++;
            startR03StoryScene();
            return;
        }

        JSONArray actions = scene.optJSONArray("actions");
        prepareScriptActionSequence(actions);
        int sceneNumber = scene.optInt(
                "scene",
                r03StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_03 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR03StoryScene() {
        r03StorySceneIndex++;
        startR03StoryScene();
    }


    private void startS03VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_03 손견 구원 완료");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s03Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_03 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS03DefeatOutcome(
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
        outcomeStage = "s03Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_03 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS03PostBattleCleanup() {
        outcomeStage = "s03PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS03Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_03 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS03Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_03 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (pendingScenarioJump == 8) {
            pendingScenarioJump = -1;
            enterS04Battle();
            return;
        }

        endBattle(
                true,
                pendingScenarioJump >= 0
                        ? "S_03 원본 승리 흐름 완료 · 다음 점프 "
                        + pendingScenarioJump
                        : "S_03 원본 승리 흐름 완료");
    }

    private void startS04VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_04 화웅 처치 완료");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s04Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_04 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS04DefeatOutcome(
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
        outcomeStage = "s04Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_04 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS04PostBattleCleanup() {
        outcomeStage = "s04PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS04Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_04 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS04Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_04 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r05StoryScenes != null && r05StoryScenes.length() > 0) {
            startR05Story();
        } else {
            endBattle(true, "S_04 원본 승리 흐름 완료");
        }
    }

    private void startR05Story() {
        outcomeFlowActive = false;
        r05StoryActive = true;
        r05StorySceneIndex = 0;
        s05Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR05StoryScene();
    }

    private void startR05StoryScene() {
        if (!r05StoryActive || r05StoryScenes == null) {
            return;
        }
        if (r05StorySceneIndex >= r05StoryScenes.length()) {
            r05StoryActive = false;
            s05Ready = true;
            enterS05Battle();
            return;
        }

        JSONObject scene = r05StoryScenes.optJSONObject(r05StorySceneIndex);
        if (scene == null) {
            r05StorySceneIndex++;
            startR05StoryScene();
            return;
        }

        JSONArray actions = scene.optJSONArray("actions");
        prepareScriptActionSequence(actions);
        int sceneNumber = scene.optInt(
                "scene",
                r05StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_05 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR05StoryScene() {
        r05StorySceneIndex++;
        startR05StoryScene();
    }

    private void startS05VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_05 여포 격퇴 완료");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s05Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_05 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS05DefeatOutcome(
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
        outcomeStage = "s05Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_05 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS05PostBattleCleanup() {
        outcomeStage = "s05PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS05Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_05 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS05Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_05 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r06StoryScenes != null && r06StoryScenes.length() > 0) {
            startR06Story();
        } else {
            endBattle(true, "S_05 원본 승리 흐름 완료");
        }
    }

    private void startR06Story() {
        outcomeFlowActive = false;
        r06StoryActive = true;
        r06StorySceneIndex = 0;
        s06Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR06StoryScene();
    }

    private void startR06StoryScene() {
        if (!r06StoryActive || r06StoryScenes == null) {
            return;
        }
        if (r06StorySceneIndex >= r06StoryScenes.length()) {
            r06StoryActive = false;
            s06Ready = true;
            enterS06Battle();
            return;
        }

        JSONObject scene = r06StoryScenes.optJSONObject(
                r06StorySceneIndex);
        if (scene == null) {
            r06StorySceneIndex++;
            startR06StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r06StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_06 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR06StoryScene() {
        r06StorySceneIndex++;
        startR06StoryScene();
    }

    private void enterS06Battle() {
        try {
            loadS06Battle(getContext());
            lastCombatMessage = "R_06 완료 · S_06 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_06 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS06Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle6.json",
                6,
                "m006.jpg",
                "terrain6.bin");
    }

    private void startS06VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_06 문추 격퇴 완료");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s06Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_06 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS06DefeatOutcome(
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
        outcomeStage = "s06Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_06 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS06PostBattleCleanup() {
        outcomeStage = "s06PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS06Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_06 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }


    private void finishS06Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_06 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r07StoryScenes != null && r07StoryScenes.length() > 0) {
            startR07Story();
        } else {
            endBattle(true, "S_06 원본 승리 흐름 완료");
        }
    }

    private void startR07Story() {
        outcomeFlowActive = false;
        r07StoryActive = true;
        r07StorySceneIndex = 0;
        s07Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR07StoryScene();
    }

    private void startR07StoryScene() {
        if (!r07StoryActive || r07StoryScenes == null) {
            return;
        }
        if (r07StorySceneIndex >= r07StoryScenes.length()) {
            r07StoryActive = false;
            s07Ready = true;
            enterS07Battle();
            return;
        }

        JSONObject scene = r07StoryScenes.optJSONObject(
                r07StorySceneIndex);
        if (scene == null) {
            r07StorySceneIndex++;
            startR07StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r07StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_07 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR07StoryScene() {
        r07StorySceneIndex++;
        startR07StoryScene();
    }

    private void enterS07Battle() {
        try {
            loadS07Battle(getContext());
            lastCombatMessage = "R_07 완료 · S_07 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_07 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS07Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle7.json",
                7,
                "m007.jpg",
                "terrain7.bin");
    }

    private void startS07VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_07 원소 격퇴 완료");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s07Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_07 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS07DefeatOutcome(
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
        outcomeStage = "s07Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_07 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS07PostBattleCleanup() {
        outcomeStage = "s07PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS07Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_07 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS07Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_07 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r08StoryScenes != null && r08StoryScenes.length() > 0) {
            startR08Story();
        } else {
            endBattle(true, "S_07 원본 승리 흐름 완료");
        }
    }

    private void startR08Story() {
        outcomeFlowActive = false;
        r08StoryActive = true;
        r08StorySceneIndex = 0;
        s08Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR08StoryScene();
    }

    private void startR08StoryScene() {
        if (!r08StoryActive || r08StoryScenes == null) {
            return;
        }
        if (r08StorySceneIndex >= r08StoryScenes.length()) {
            r08StoryActive = false;
            s08Ready = true;
            enterS08Battle();
            return;
        }

        JSONObject scene = r08StoryScenes.optJSONObject(
                r08StorySceneIndex);
        if (scene == null) {
            r08StorySceneIndex++;
            startR08StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r08StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_08 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR08StoryScene() {
        r08StorySceneIndex++;
        startR08StoryScene();
    }

    private void enterS08Battle() {
        try {
            loadS08Battle(getContext());
            lastCombatMessage = "R_08 완료 · S_08 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_08 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS08Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle8.json",
                8,
                "m008.jpg",
                "terrain8.bin");
    }

    private void startS08VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_08 적군 섬멸 완료");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s08Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_08 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS08DefeatOutcome(
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
        outcomeStage = "s08Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_08 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS08PostBattleCleanup() {
        outcomeStage = "s08PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS08Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_08 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS08Outcome() {
        outcomeFlowActive = false;
        if (battleVictory
                && r09StoryScenes != null
                && r09StoryScenes.length() > 0) {
            startR09Story();
            return;
        }
        endBattle(
                battleVictory,
                battleVictory
                        ? "S_08 원본 승리 흐름 완료"
                        : (battleResultText == null
                        || battleResultText.isEmpty()
                        ? "S_08 원본 패배 흐름 완료"
                        : battleResultText));
    }

    private void startR09Story() {
        outcomeFlowActive = false;
        r09StoryActive = true;
        r09StorySceneIndex = 0;
        s09Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR09StoryScene();
    }

    private void startR09StoryScene() {
        if (!r09StoryActive || r09StoryScenes == null) {
            return;
        }
        if (r09StorySceneIndex >= r09StoryScenes.length()) {
            r09StoryActive = false;
            s09Ready = true;
            enterS09Battle();
            return;
        }

        JSONObject scene = r09StoryScenes.optJSONObject(
                r09StorySceneIndex);
        if (scene == null) {
            r09StorySceneIndex++;
            startR09StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r09StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_09 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR09StoryScene() {
        r09StorySceneIndex++;
        startR09StoryScene();
    }

    private void enterS09Battle() {
        try {
            loadS09Battle(getContext());
            lastCombatMessage = "R_09 완료 · S_09 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_09 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS09Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle9.json",
                9,
                "m009.jpg",
                "terrain9.bin");
    }

    private void startS09VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_09 원본 승리 조건 달성");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s09Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_09 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS09DefeatOutcome(
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
        outcomeStage = "s09Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_09 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS09DirectVictoryCleanup() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s09DirectVictory";
        battleVictory = true;
        stopBattleForOutcome();
        lastCombatMessage = "서주성 진입·도겸 대화 승리 완료";
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        startS09PostBattleCleanup();
        invalidate();
    }

    private void startS09PostBattleCleanup() {
        outcomeStage = "s09PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS09Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_09 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS09Outcome() {
        outcomeFlowActive = false;
        if (battleVictory
                && r10StoryScenes != null
                && r10StoryScenes.length() > 0) {
            startR10Story();
            return;
        }
        endBattle(
                battleVictory,
                battleVictory
                        ? "S_09 원본 승리 흐름 완료"
                        : (battleResultText == null
                        || battleResultText.isEmpty()
                        ? "S_09 원본 패배 흐름 완료"
                        : battleResultText));
    }

    private void startR10Story() {
        outcomeFlowActive = false;
        r10StoryActive = true;
        r10StorySceneIndex = 0;
        s10Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR10StoryScene();
    }

    private void startR10StoryScene() {
        if (!r10StoryActive || r10StoryScenes == null) {
            return;
        }
        if (r10StorySceneIndex >= r10StoryScenes.length()) {
            r10StoryActive = false;
            s10Ready = true;
            enterS10Battle();
            return;
        }

        JSONObject scene = r10StoryScenes.optJSONObject(
                r10StorySceneIndex);
        if (scene == null) {
            r10StorySceneIndex++;
            startR10StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r10StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_10 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR10StoryScene() {
        r10StorySceneIndex++;
        startR10StoryScene();
    }


    private boolean tryStartS10AttackVictory(int characterId) {
        if (s10AttackVictoryEvents == null) {
            return false;
        }
        JSONObject event = s10AttackVictoryEvents.optJSONObject(
                String.valueOf(characterId));
        if (event == null
                || !event.optBoolean("coreSupported", false)
                || !battleEventConditionsSatisfied(event)) {
            return false;
        }

        JSONArray actions = event.optJSONArray("actions");
        if (actions == null || actions.length() == 0) {
            return false;
        }

        outcomeFlowActive = true;
        outcomeStage = "s10Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        BattleUnit target = findUnitByCharacterId(characterId);
        lastCombatMessage = "원본 S_10 승리 · "
                + (target == null
                ? "주장 격퇴"
                : target.name + " 격퇴");
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
        return true;
    }

    private void startS10VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            battleVictory = true;
            finishS10Outcome();
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s10Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_10 15턴 방어 성공";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS10DefeatOutcome(
            int characterId,
            String fallbackReason) {
        if (battleEnded || outcomeFlowActive) {
            return;
        }

        JSONArray actions = null;
        if (s01DefeatOutcomeEvents != null && characterId >= 0) {
            JSONObject entry = s01DefeatOutcomeEvents.optJSONObject(
                    String.valueOf(characterId));
            if (entry != null
                    && (entry.optBoolean("supported", false)
                    || entry.optBoolean("coreSupported", false))) {
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
        outcomeStage = "s10Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_10 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS10PostBattleCleanup() {
        outcomeStage = "s10PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS10Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_10 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS10Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_10 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r11StoryScenes != null && r11StoryScenes.length() > 0) {
            startR11Story();
        } else {
            endBattle(true, "S_10 원본 승리 흐름 완료");
        }
    }

    private void startS11VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_11 원본 승리 조건 달성");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s11Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_11 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS11DefeatOutcome(
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
        outcomeStage = "s11Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_11 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS11PostBattleCleanup() {
        outcomeStage = "s11PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS11Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_11 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS11Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_11 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r12StoryScenes != null && r12StoryScenes.length() > 0) {
            startR12Story();
        } else {
            endBattle(true, "S_11 원본 승리 흐름 완료");
        }
    }

    private void startR11Story() {
        outcomeFlowActive = false;
        r11StoryActive = true;
        r11StorySceneIndex = 0;
        s11Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR11StoryScene();
    }

    private void startR11StoryScene() {
        if (!r11StoryActive || r11StoryScenes == null) {
            return;
        }
        if (r11StorySceneIndex >= r11StoryScenes.length()) {
            r11StoryActive = false;
            s11Ready = true;
            enterS11Battle();
            return;
        }

        JSONObject scene = r11StoryScenes.optJSONObject(
                r11StorySceneIndex);
        if (scene == null) {
            r11StorySceneIndex++;
            startR11StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r11StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_11 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR11StoryScene() {
        r11StorySceneIndex++;
        startR11StoryScene();
    }

    private void startR12Story() {
        outcomeFlowActive = false;
        r12StoryActive = true;
        r12StorySceneIndex = 0;
        s12Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR12StoryScene();
    }

    private void startR12StoryScene() {
        if (!r12StoryActive || r12StoryScenes == null) {
            return;
        }
        if (r12StorySceneIndex >= r12StoryScenes.length()) {
            r12StoryActive = false;
            s12Ready = true;
            enterS12Battle();
            return;
        }

        JSONObject scene = r12StoryScenes.optJSONObject(
                r12StorySceneIndex);
        if (scene == null) {
            r12StorySceneIndex++;
            startR12StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r12StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_12 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR12StoryScene() {
        r12StorySceneIndex++;
        startR12StoryScene();
    }


    private void startR13Story() {
        outcomeFlowActive = false;
        r13StoryActive = true;
        r13StorySceneIndex = 0;
        s13Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR13StoryScene();
    }

    private void startR13StoryScene() {
        if (!r13StoryActive || r13StoryScenes == null) {
            return;
        }
        if (r13StorySceneIndex >= r13StoryScenes.length()) {
            r13StoryActive = false;
            s13Ready = true;
            enterS13Battle();
            return;
        }

        JSONObject scene = r13StoryScenes.optJSONObject(
                r13StorySceneIndex);
        if (scene == null) {
            r13StorySceneIndex++;
            startR13StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r13StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_13 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR13StoryScene() {
        r13StorySceneIndex++;
        startR13StoryScene();
    }

    private void startR14Story() {
        outcomeFlowActive = false;
        r14StoryActive = true;
        r14StorySceneIndex = 0;
        s14Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR14StoryScene();
    }

    private void startR14StoryScene() {
        if (!r14StoryActive || r14StoryScenes == null) {
            return;
        }
        if (r14StorySceneIndex >= r14StoryScenes.length()) {
            r14StoryActive = false;
            s14Ready = true;
            enterS14Battle();
            return;
        }

        JSONObject scene = r14StoryScenes.optJSONObject(
                r14StorySceneIndex);
        if (scene == null) {
            r14StorySceneIndex++;
            startR14StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r14StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_14 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR14StoryScene() {
        r14StorySceneIndex++;
        startR14StoryScene();
    }

    private void startR15Story() {
        outcomeFlowActive = false;
        r15StoryActive = true;
        r15StorySceneIndex = 0;
        s15Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR15StoryScene();
    }

    private void startR15StoryScene() {
        if (!r15StoryActive || r15StoryScenes == null) {
            return;
        }
        if (r15StorySceneIndex >= r15StoryScenes.length()) {
            r15StoryActive = false;
            s15Ready = true;
            enterS15Battle();
            return;
        }

        JSONObject scene = r15StoryScenes.optJSONObject(
                r15StorySceneIndex);
        if (scene == null) {
            r15StorySceneIndex++;
            startR15StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r15StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_15 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR15StoryScene() {
        r15StorySceneIndex++;
        startR15StoryScene();
    }

    private void startR16Story() {
        outcomeFlowActive = false;
        r16StoryActive = true;
        r16StorySceneIndex = 0;
        s16Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR16StoryScene();
    }

    private void startR16StoryScene() {
        if (!r16StoryActive || r16StoryScenes == null) {
            return;
        }
        if (r16StorySceneIndex >= r16StoryScenes.length()) {
            r16StoryActive = false;
            s16Ready = true;
            enterS16Battle();
            return;
        }

        JSONObject scene = r16StoryScenes.optJSONObject(
                r16StorySceneIndex);
        if (scene == null) {
            r16StorySceneIndex++;
            startR16StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r16StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_16 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR16StoryScene() {
        r16StorySceneIndex++;
        startR16StoryScene();
    }

    private void startR17Story() {
        outcomeFlowActive = false;
        r17StoryActive = true;
        r17StorySceneIndex = 0;
        s17Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR17StoryScene();
    }

    private void startR17StoryScene() {
        if (!r17StoryActive || r17StoryScenes == null) {
            return;
        }
        if (r17StorySceneIndex >= r17StoryScenes.length()) {
            r17StoryActive = false;
            s17Ready = true;
            enterS17Battle();
            return;
        }

        JSONObject scene = r17StoryScenes.optJSONObject(
                r17StorySceneIndex);
        if (scene == null) {
            r17StorySceneIndex++;
            startR17StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r17StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_17 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR17StoryScene() {
        r17StorySceneIndex++;
        startR17StoryScene();
    }

    private void startR18Story() {
        outcomeFlowActive = false;
        r18StoryActive = true;
        r18StorySceneIndex = 0;
        s18Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR18StoryScene();
    }

    private void startR18StoryScene() {
        if (!r18StoryActive || r18StoryScenes == null) {
            return;
        }
        if (r18StorySceneIndex >= r18StoryScenes.length()) {
            r18StoryActive = false;
            s18Ready = true;
            enterS18Battle();
            return;
        }

        JSONObject scene = r18StoryScenes.optJSONObject(
                r18StorySceneIndex);
        if (scene == null) {
            r18StorySceneIndex++;
            startR18StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r18StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_18 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR18StoryScene() {
        r18StorySceneIndex++;
        startR18StoryScene();
    }

    private void enterS18Battle() {
        try {
            loadS18Battle(getContext());
            lastCombatMessage = "R_18 완료 · S_18 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_18 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS18Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle18.json",
                18,
                "m018.jpg",
                "terrain18.bin");
    }

    private void enterS17Battle() {
        try {
            loadS17Battle(getContext());
            lastCombatMessage = "R_17 완료 · S_17 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_17 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS17Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle17.json",
                17,
                "m017.jpg",
                "terrain17.bin");
    }

    private void enterS16Battle() {
        try {
            loadS16Battle(getContext());
            lastCombatMessage = "R_16 완료 · S_16 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_16 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS16Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle16.json",
                16,
                "m016.jpg",
                "terrain16.bin");
    }

    private void enterS15Battle() {
        try {
            loadS15Battle(getContext());
            lastCombatMessage = "R_15 완료 · S_15 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_15 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS15Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle15.json",
                15,
                "m015.jpg",
                "terrain15.bin");
    }

    private void enterS13Battle() {
        try {
            loadS13Battle(getContext());
            lastCombatMessage = "R_13 완료 · S_13 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_13 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS13Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle13.json",
                13,
                "m013.jpg",
                "terrain13.bin");
    }

    private void enterS14Battle() {
        try {
            loadS14Battle(getContext());
            lastCombatMessage = "R_14 완료 · S_14 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_14 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS14Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle14.json",
                14,
                "m014.jpg",
                "terrain14.bin");
    }

    private void enterS10Battle() {
        try {
            loadS10Battle(getContext());
            lastCombatMessage = "R_10 완료 · S_10 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_10 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS10Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle10.json",
                10,
                "m010.jpg",
                "terrain10.bin");
    }

    private void enterS11Battle() {
        try {
            loadS11Battle(getContext());
            lastCombatMessage = "R_11 완료 · S_11 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_11 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS11Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle11.json",
                11,
                "m011.jpg",
                "terrain11.bin");
    }


    private void enterS12Battle() {
        try {
            loadS12Battle(getContext());
            lastCombatMessage = "R_12 완료 · S_12 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_12 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS12Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle12.json",
                12,
                "m012.jpg",
                "terrain12.bin");
    }

    private boolean s12RetreatGoalReached() {
        BattleUnit liuBei = findUnitByCharacterId(0);
        if (liuBei == null
                || !liuBei.visible
                || !liuBei.isAlive()
                || s12RetreatGoals == null) {
            return false;
        }

        for (int i = 0; i < s12RetreatGoals.length(); i++) {
            JSONObject goal = s12RetreatGoals.optJSONObject(i);
            if (goal == null) {
                continue;
            }
            String type = goal.optString("type", "");
            if ("position".equals(type)) {
                if (liuBei.x == goal.optInt("x", -1)
                        && liuBei.y == goal.optInt("y", -1)) {
                    return true;
                }
            } else if ("area".equals(type)) {
                int left = Math.min(
                        goal.optInt("x1", -1),
                        goal.optInt("x2", -1));
                int right = Math.max(
                        goal.optInt("x1", -1),
                        goal.optInt("x2", -1));
                int top = Math.min(
                        goal.optInt("y1", -1),
                        goal.optInt("y2", -1));
                int bottom = Math.max(
                        goal.optInt("y1", -1),
                        goal.optInt("y2", -1));
                if (liuBei.x >= left
                        && liuBei.x <= right
                        && liuBei.y >= top
                        && liuBei.y <= bottom) {
                    return true;
                }
            }
        }
        return false;
    }

    private void startS12VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_12 원본 승리 조건 달성");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s12Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_12 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS12DefeatOutcome(
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
        outcomeStage = "s12Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_12 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS12PostBattleCleanup() {
        outcomeStage = "s12PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS12Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_12 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS12Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_12 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r13StoryScenes != null && r13StoryScenes.length() > 0) {
            startR13Story();
        } else {
            endBattle(true, "S_12 원본 승리 흐름 완료");
        }
    }

    private void startS13VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            if (r14StoryScenes != null && r14StoryScenes.length() > 0) {
                startR14Story();
            } else {
                endBattle(true, "S_13 원본 승리 조건 달성");
            }
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s13Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_13 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS13DefeatOutcome(
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
        outcomeStage = "s13Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_13 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS13PostBattleCleanup() {
        outcomeStage = "s13PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS13Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_13 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS13Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_13 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }

        if (r14StoryScenes != null && r14StoryScenes.length() > 0) {
            startR14Story();
        } else {
            endBattle(true, "S_13 원본 승리 흐름 완료");
        }
    }

    private void startS14VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            outcomeFlowActive = true;
            battleVictory = true;
            startS14PostBattleCleanup();
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s14Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_14 적 전멸 승리 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS14EscapeOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }

        // Section 11 already performed the escape dialogue,
        // battle-end marker, variables 0/614 and scene end.
        outcomeFlowActive = true;
        outcomeStage = "s14Escape";
        battleVictory = true;
        stopBattleForOutcome();
        startS14PostBattleCleanup();
        invalidate();
    }

    private void startS14DefeatOutcome(
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
        outcomeStage = "s14Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_14 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS14PostBattleCleanup() {
        outcomeFlowActive = true;
        outcomeStage = "s14PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS14Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_14 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS14Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_14 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r15StoryScenes != null && r15StoryScenes.length() > 0) {
            startR15Story();
        } else {
            endBattle(true, "S_14 원본 승리 흐름 완료");
        }
    }

    private void startS15VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            startS15PostBattleCleanup();
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s15Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_15 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS15DefeatOutcome(
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
        outcomeStage = "s15Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_15 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS15PostBattleCleanup() {
        outcomeFlowActive = true;
        outcomeStage = "s15PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS15Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_15 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS15Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_15 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r16StoryScenes != null && r16StoryScenes.length() > 0) {
            startR16Story();
        } else {
            endBattle(true, "S_15 원본 승리 흐름 완료");
        }
    }

    private void startS16VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            startS16PostBattleCleanup();
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s16Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_16 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS16DefeatOutcome(
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
        outcomeStage = "s16Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_16 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS16PostBattleCleanup() {
        outcomeFlowActive = true;
        outcomeStage = "s16PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS16Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_16 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS16Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_16 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r17StoryScenes != null && r17StoryScenes.length() > 0) {
            startR17Story();
        } else {
            endBattle(true, "S_16 원본 승리 흐름 완료");
        }
    }

    private void startS17VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            startS17PostBattleCleanup();
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s17Victory";
        battleVictory = true;
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_17 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS17DefeatOutcome(
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
        outcomeStage = "s17Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_17 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void completeS17OuterCityVictory() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        battleVictory = true;
        battleResultText = "외성의 적군 섬멸 · 원본 Section 16 완료";
        stopBattleForOutcome();
        startS17PostBattleCleanup();
    }

    private void startS17PostBattleCleanup() {
        outcomeFlowActive = true;
        outcomeStage = "s17PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS17Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_17 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS17Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_17 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r18StoryScenes != null && r18StoryScenes.length() > 0) {
            startR18Story();
        } else {
            endBattle(true, "S_17 원본 승리 흐름 완료");
        }
    }

    private void startS18VictoryOutcome(
            int defeatedCharacterId,
            String fallbackReason) {
        if (battleEnded || outcomeFlowActive) {
            return;
        }

        JSONArray actions = null;
        if (s18VictoryOutcomeEvents != null
                && defeatedCharacterId >= 0) {
            JSONObject entry = s18VictoryOutcomeEvents.optJSONObject(
                    String.valueOf(defeatedCharacterId));
            if (entry != null && entry.optBoolean("supported", false)) {
                actions = entry.optJSONArray("actions");
            }
        }
        if (actions == null) {
            actions = victoryOutcomeActions;
        }
        if (actions == null || actions.length() == 0) {
            endBattle(true, fallbackReason);
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s18Victory";
        battleVictory = true;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_18 승리 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS18DefeatOutcome(
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
        if (actions == null) {
            actions = s01GenericDefeatActions;
        }
        if (actions == null || actions.length() == 0) {
            endBattle(false, fallbackReason);
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s18Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_18 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS18PostBattleCleanup() {
        outcomeStage = "s18PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS18Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_18 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS18Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_18 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r19StoryScenes != null && r19StoryScenes.length() > 0) {
            startR19Story();
        } else {
            endBattle(true, "S_18 원본 승리 흐름 완료");
        }
    }

    private void startR19Story() {
        outcomeFlowActive = false;
        r19StoryActive = true;
        r19StorySceneIndex = 0;
        s19Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR19StoryScene();
    }

    private void startR19StoryScene() {
        if (!r19StoryActive || r19StoryScenes == null) {
            return;
        }
        if (r19StorySceneIndex >= r19StoryScenes.length()) {
            r19StoryActive = false;
            s19Ready = true;
            enterS19Battle();
            return;
        }

        JSONObject scene = r19StoryScenes.optJSONObject(
                r19StorySceneIndex);
        if (scene == null) {
            r19StorySceneIndex++;
            startR19StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r19StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_19 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR19StoryScene() {
        r19StorySceneIndex++;
        startR19StoryScene();
    }

    private void enterS19Battle() {
        try {
            loadS19Battle(getContext());
            lastCombatMessage = "R_19 완료 · S_19 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_19 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS19Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle19.json",
                19,
                "m019.jpg",
                "terrain19.bin");
    }

    private void startS19VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_19 원본 승리 조건 달성");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s19Victory";
        battleVictory = true;
        battleResultText = "적군 전멸 · 원본 승리 조건";
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_19 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS19DefeatOutcome(
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
        outcomeStage = "s19Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_19 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS19PostBattleCleanup() {
        outcomeStage = "s19PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS19Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_19 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS19Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_19 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r20StoryScenes != null && r20StoryScenes.length() > 0) {
            startR20Story();
        } else {
            endBattle(true, "S_19 원본 승리 흐름 완료");
        }
    }

    private void startR20Story() {
        outcomeFlowActive = false;
        r20StoryActive = true;
        r20StorySceneIndex = 0;
        s20Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR20StoryScene();
    }

    private void startR20StoryScene() {
        if (!r20StoryActive || r20StoryScenes == null) {
            return;
        }
        if (r20StorySceneIndex >= r20StoryScenes.length()) {
            r20StoryActive = false;
            s20Ready = true;
            enterS20Battle();
            return;
        }

        JSONObject scene = r20StoryScenes.optJSONObject(
                r20StorySceneIndex);
        if (scene == null) {
            r20StorySceneIndex++;
            startR20StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt(
                "scene",
                r20StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_20 Scene " + sceneNumber
                + ("departure".equals(kind)
                ? " · 출전"
                : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR20StoryScene() {
        r20StorySceneIndex++;
        startR20StoryScene();
    }

    private void enterS20Battle() {
        try {
            loadS20Battle(getContext());
            lastCombatMessage = "R_20 완료 · S_20 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_20 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS20Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle20.json",
                20,
                "m020.jpg",
                "terrain20.bin");
    }

    private void startS20VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_20 원본 승리 조건 달성");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s20Victory";
        battleVictory = true;
        battleResultText = (killTargetName == null || killTargetName.isEmpty())
                ? "목표 무장 격파 · 원본 승리 조건"
                : killTargetName + " 격파 · 원본 승리 조건";
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_20 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS20DefeatOutcome(
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
        outcomeStage = "s20Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_20 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS20PostBattleCleanup() {
        outcomeStage = "s20PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS20Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_20 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS20Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_20 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r21StoryScenes != null && r21StoryScenes.length() > 0) {
            startR21Story();
        } else {
            endBattle(true, "S_20 원본 승리 흐름 완료");
        }
    }

    private void startR21Story() {
        outcomeFlowActive = false;
        r21StoryActive = true;
        r21StorySceneIndex = 0;
        s21Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR21StoryScene();
    }

    private void startR21StoryScene() {
        if (!r21StoryActive || r21StoryScenes == null) {
            return;
        }
        if (r21StorySceneIndex >= r21StoryScenes.length()) {
            r21StoryActive = false;
            s21Ready = true;
            enterS21Battle();
            return;
        }

        JSONObject scene = r21StoryScenes.optJSONObject(
                r21StorySceneIndex);
        if (scene == null) {
            r21StorySceneIndex++;
            startR21StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt("scene", r21StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_21 Scene " + sceneNumber
                + ("departure".equals(kind) ? " · 출전" : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR21StoryScene() {
        r21StorySceneIndex++;
        startR21StoryScene();
    }

    private void enterS21Battle() {
        try {
            loadS21Battle(getContext());
            lastCombatMessage = "R_21 완료 · S_21 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_21 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS21Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle21.json",
                21,
                "m021.jpg",
                "terrain21.bin");
    }

    private void startS21VictoryOutcome(boolean escapeRoute) {
        if (battleEnded || outcomeFlowActive) {
            return;
        }

        battleVictory = true;
        battleResultText = escapeRoute
                ? "유비 탈출 · 원본 승리 조건"
                : "적군 전멸 · 원본 승리 조건";
        stopBattleForOutcome();

        if (escapeRoute) {
            outcomeFlowActive = true;
            startS21PostBattleCleanup();
            invalidate();
            return;
        }

        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            startS21PostBattleCleanup();
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s21Victory";
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_21 승리 후일담";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS21DefeatOutcome(
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
        outcomeStage = "s21Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_21 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS21PostBattleCleanup() {
        outcomeFlowActive = true;
        outcomeStage = "s21PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS21Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_21 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS21Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_21 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r22StoryScenes != null && r22StoryScenes.length() > 0) {
            startR22Story();
        } else {
            endBattle(true, "S_21 원본 승리 흐름 완료");
        }
    }

    private void startR22Story() {
        outcomeFlowActive = false;
        r22StoryActive = true;
        r22StorySceneIndex = 0;
        s22Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR22StoryScene();
    }

    private void startR22StoryScene() {
        if (!r22StoryActive || r22StoryScenes == null) {
            return;
        }
        if (r22StorySceneIndex >= r22StoryScenes.length()) {
            r22StoryActive = false;
            s22Ready = true;
            enterS22Battle();
            return;
        }

        JSONObject scene = r22StoryScenes.optJSONObject(
                r22StorySceneIndex);
        if (scene == null) {
            r22StorySceneIndex++;
            startR22StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt("scene", r22StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_22 Scene " + sceneNumber
                + ("departure".equals(kind) ? " · 출전" : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR22StoryScene() {
        r22StorySceneIndex++;
        startR22StoryScene();
    }

    private void enterS22Battle() {
        try {
            loadS22Battle(getContext());
            lastCombatMessage = "R_22 완료 · S_22 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            endBattle(
                    false,
                    "S_22 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS22Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle22.json",
                22,
                "m022.jpg",
                "terrain22.bin");
    }

    private void startS22VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "안량 격퇴 · 원본 승리 조건");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s22Victory";
        battleVictory = true;
        battleResultText = (killTargetName == null || killTargetName.isEmpty())
                ? "목표 무장 격파 · 원본 승리 조건"
                : killTargetName + " 격파 · 원본 승리 조건";
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_22 승리 정산";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS22DefeatOutcome(
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
        outcomeStage = "s22Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_22 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS22PostBattleCleanup() {
        outcomeStage = "s22PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS22Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_22 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS22Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_22 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r23StoryScenes != null && r23StoryScenes.length() > 0) {
            startR23Story();
        } else {
            endBattle(true, "S_22 원본 승리 흐름 완료");
        }
    }

    private void startR23Story() {
        outcomeFlowActive = false;
        r23StoryActive = true;
        r23StorySceneIndex = 0;
        s23Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR23StoryScene();
    }

    private void startR23StoryScene() {
        if (!r23StoryActive || r23StoryScenes == null) {
            return;
        }
        if (r23StorySceneIndex >= r23StoryScenes.length()) {
            r23StoryActive = false;
            s23Ready = true;
            enterS23Battle();
            return;
        }

        JSONObject scene = r23StoryScenes.optJSONObject(
                r23StorySceneIndex);
        if (scene == null) {
            r23StorySceneIndex++;
            startR23StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt("scene", r23StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_23 Scene " + sceneNumber
                + ("departure".equals(kind) ? " · 출전" : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR23StoryScene() {
        r23StorySceneIndex++;
        startR23StoryScene();
    }

    private void enterS23Battle() {
        try {
            loadS23Battle(getContext());
            lastCombatMessage = "R_23 완료 · S_23 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            r23StoryActive = false;
            endBattle(
                    false,
                    "S_23 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS23Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle23.json",
                23,
                "m023.jpg",
                "terrain23.bin");
    }

    private void startS23VictoryOutcome(boolean genericVictory) {
        if (battleEnded || outcomeFlowActive) {
            return;
        }

        JSONArray actions = genericVictory
                ? s23GenericVictoryActions
                : victoryOutcomeActions;
        if (actions == null || actions.length() == 0) {
            endBattle(
                    true,
                    genericVictory
                            ? "적군 전멸 · 원본 승리 조건"
                            : "조조 격퇴 · 원본 승리 조건");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s23Victory";
        battleVictory = true;
        battleResultText = genericVictory
                ? "적군 전멸 · 원본 승리 조건"
                : "조조 격퇴 · 원본 승리 조건";
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_23 승리 정산";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS23DefeatOutcome(
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
        outcomeStage = "s23Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_23 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS23PostBattleCleanup() {
        outcomeStage = "s23PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS23Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_23 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS23Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_23 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r24StoryScenes != null && r24StoryScenes.length() > 0) {
            startR24Story();
        } else {
            endBattle(true, "S_23 원본 승리 흐름 완료");
        }
    }


    private void startR24Story() {
        outcomeFlowActive = false;
        r24StoryActive = true;
        r24StorySceneIndex = 0;
        s24Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR24StoryScene();
    }

    private void startR24StoryScene() {
        if (!r24StoryActive || r24StoryScenes == null) {
            return;
        }
        if (r24StorySceneIndex >= r24StoryScenes.length()) {
            r24StoryActive = false;
            s24Ready = true;
            enterS24Battle();
            return;
        }

        JSONObject scene = r24StoryScenes.optJSONObject(
                r24StorySceneIndex);
        if (scene == null) {
            r24StorySceneIndex++;
            startR24StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt("scene", r24StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_24 Scene " + sceneNumber
                + ("departure".equals(kind) ? " · 출전" : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR24StoryScene() {
        r24StorySceneIndex++;
        startR24StoryScene();
    }

    private void enterS24Battle() {
        try {
            loadS24Battle(getContext());
            lastCombatMessage = "R_24 완료 · S_24 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            r24StoryActive = false;
            endBattle(
                    false,
                    "S_24 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS24Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle24.json",
                24,
                "m024.jpg",
                "terrain24.bin");
    }

    private void startS24VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "관우와 마차 하북 도착 · 원본 승리 조건");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s24Victory";
        battleVictory = true;
        battleResultText = "관우와 마차 하북 도착 · 원본 승리 조건";
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_24 승리 정산";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS24DefeatOutcome(
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
        outcomeStage = "s24Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_24 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS24PostBattleCleanup() {
        outcomeStage = "s24PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS24Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_24 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS24Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_24 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        if (r25StoryScenes != null && r25StoryScenes.length() > 0) {
            startR25Story();
        } else {
            endBattle(true, "S_24 원본 승리 흐름 완료");
        }
    }

    private void startR25Story() {
        outcomeFlowActive = false;
        r25StoryActive = true;
        r25StorySceneIndex = 0;
        s25Ready = false;
        battleEnded = false;
        playerTurn = false;
        selectedUnit = null;
        selectedX = -1;
        selectedY = -1;
        storyTitle = "";
        storyLocation = "";
        clearReachable();
        startR25StoryScene();
    }

    private void startR25StoryScene() {
        if (!r25StoryActive || r25StoryScenes == null) {
            return;
        }
        if (r25StorySceneIndex >= r25StoryScenes.length()) {
            r25StoryActive = false;
            s25Ready = true;
            enterS25Battle();
            return;
        }

        JSONObject scene = r25StoryScenes.optJSONObject(
                r25StorySceneIndex);
        if (scene == null) {
            r25StorySceneIndex++;
            startR25StoryScene();
            return;
        }

        prepareScriptActionSequence(scene.optJSONArray("actions"));
        int sceneNumber = scene.optInt("scene", r25StorySceneIndex + 1);
        String kind = scene.optString("kind", "story");
        lastCombatMessage = "R_25 Scene " + sceneNumber
                + ("departure".equals(kind) ? " · 출전" : " · 스토리");
        combatMessageUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    private void finishR25StoryScene() {
        r25StorySceneIndex++;
        startR25StoryScene();
    }

    private void enterS25Battle() {
        try {
            loadS25Battle(getContext());
            lastCombatMessage = "R_25 완료 · S_25 전투 개시";
            combatMessageUntil = SystemClock.uptimeMillis() + 1800L;
            invalidate();
        } catch (Exception e) {
            r25StoryActive = false;
            endBattle(
                    false,
                    "S_25 로드 실패 · "
                            + e.getClass().getSimpleName());
        }
    }

    private void loadS25Battle(Context context) throws Exception {
        loadFollowupBattle(
                context,
                "battle25.json",
                25,
                "m025.jpg",
                "terrain25.bin");
    }

    private void startS25VictoryOutcome() {
        if (battleEnded || outcomeFlowActive) {
            return;
        }
        if (victoryOutcomeActions == null
                || victoryOutcomeActions.length() == 0) {
            endBattle(true, "S_25 적군 전멸 · 원본 승리 조건");
            return;
        }

        outcomeFlowActive = true;
        outcomeStage = "s25Victory";
        battleVictory = true;
        battleResultText = "S_25 적군 전멸 · 원본 승리 조건";
        stopBattleForOutcome();
        prepareScriptActionSequence(victoryOutcomeActions);
        lastCombatMessage = "원본 S_25 승리 정산";
        combatMessageUntil = SystemClock.uptimeMillis() + 1600L;
        invalidate();
    }

    private void startS25DefeatOutcome(
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
        outcomeStage = "s25Defeat";
        battleVictory = false;
        battleResultText = fallbackReason;
        stopBattleForOutcome();
        prepareScriptActionSequence(actions);
        lastCombatMessage = "원본 S_25 패배 연출";
        combatMessageUntil = SystemClock.uptimeMillis() + 1500L;
        invalidate();
    }

    private void startS25PostBattleCleanup() {
        outcomeStage = "s25PostBattle";
        if (postBattleOutcomeActions == null
                || postBattleOutcomeActions.length() == 0) {
            finishS25Outcome();
            return;
        }
        prepareScriptActionSequence(postBattleOutcomeActions);
        lastCombatMessage = "원본 S_25 전투 후 정리";
        combatMessageUntil = SystemClock.uptimeMillis() + 1200L;
    }

    private void finishS25Outcome() {
        outcomeFlowActive = false;
        if (!battleVictory) {
            endBattle(
                    false,
                    battleResultText == null || battleResultText.isEmpty()
                            ? "S_25 원본 패배 흐름 완료"
                            : battleResultText);
            return;
        }
        endBattle(true, "S_25 원본 승리 흐름 완료 · R_26 준비");
    }


    private String currentBattleLabel() {
        if (currentBattleIndex == 25) {
            return "S_25";
        }
        if (currentBattleIndex == 25) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS25DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS25DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS25DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveEnemy()) {
                startS25VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 24) {
        if (currentBattleIndex == 24) {
            return "S_24";
        }


        if (currentBattleIndex == 23) {
            return "S_23";
        }
        if (currentBattleIndex == 22) {
            return "S_22";
        }
        if (currentBattleIndex == 21) {
            return "S_21";
        }
        if (currentBattleIndex == 20) {
            return "S_20";
        }
        if (currentBattleIndex == 19) {
            return "S_19";
        }
        if (currentBattleIndex == 18) {
            return "S_18";
        }
        if (currentBattleIndex == 17) {
            return "S_17";
        }
        if (currentBattleIndex == 16) {
            return "S_16";
        }
        if (currentBattleIndex == 15) {
            return "S_15";
        }
        if (currentBattleIndex == 14) {
            return "S_14";
        }
        if (currentBattleIndex == 13) {
            return "S_13";
        }
        if (currentBattleIndex == 12) {
            return "S_12";
        }

        if (currentBattleIndex == 11) {
            return "S_11";
        }
        if (currentBattleIndex == 10) {
            return "S_10";
        }
        if (currentBattleIndex == 9) {
            return "S_09";
        }
        if (currentBattleIndex == 8) {
            return "S_08";
        }
        if (currentBattleIndex == 7) {
            return "S_07";
        }
        if (currentBattleIndex == 6) {
            return "S_06";
        }
        if (currentBattleIndex == 5) {
            return "S_05";
        }
        if (currentBattleIndex == 4) {
            return "S_04";
        }
        if (currentBattleIndex == 3) {
            return "S_03";
        }
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
            if ("s02Victory".equals(outcomeStage)
                    || "s02Defeat".equals(outcomeStage)) {
                startS02PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s02PostBattle".equals(outcomeStage)) {
                finishS02Outcome();
                return;
            }
            if ("s03Victory".equals(outcomeStage)
                    || "s03Defeat".equals(outcomeStage)) {
                startS03PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s03PostBattle".equals(outcomeStage)) {
                finishS03Outcome();
                return;
            }
            if ("s04Victory".equals(outcomeStage)
                    || "s04Defeat".equals(outcomeStage)) {
                startS04PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s04PostBattle".equals(outcomeStage)) {
                finishS04Outcome();
                return;
            }
            if ("s05Victory".equals(outcomeStage)
                    || "s05Defeat".equals(outcomeStage)) {
                startS05PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s05PostBattle".equals(outcomeStage)) {
                finishS05Outcome();
                return;
            }
            if ("s06Victory".equals(outcomeStage)
                    || "s06Defeat".equals(outcomeStage)) {
                startS06PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s06PostBattle".equals(outcomeStage)) {
                finishS06Outcome();
                return;
            }
            if ("s07Victory".equals(outcomeStage)
                    || "s07Defeat".equals(outcomeStage)) {
                startS07PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s07PostBattle".equals(outcomeStage)) {
                finishS07Outcome();
                return;
            }
            if ("s08Victory".equals(outcomeStage)
                    || "s08Defeat".equals(outcomeStage)) {
                startS08PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s08PostBattle".equals(outcomeStage)) {
                finishS08Outcome();
                return;
            }
            if ("s09Victory".equals(outcomeStage)
                    || "s09Defeat".equals(outcomeStage)) {
                startS09PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s09PostBattle".equals(outcomeStage)) {
                finishS09Outcome();
                return;
            }
            if ("s10Victory".equals(outcomeStage)
                    || "s10Defeat".equals(outcomeStage)) {
                startS10PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s10PostBattle".equals(outcomeStage)) {
                finishS10Outcome();
                return;
            }
            if ("s11Victory".equals(outcomeStage)
                    || "s11Defeat".equals(outcomeStage)) {
                startS11PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s11PostBattle".equals(outcomeStage)) {
                finishS11Outcome();
                return;
            }
            if ("s12Victory".equals(outcomeStage)
                    || "s12Defeat".equals(outcomeStage)) {
                startS12PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s12PostBattle".equals(outcomeStage)) {
                finishS12Outcome();
                return;
            }
            if ("s13Victory".equals(outcomeStage)
                    || "s13Defeat".equals(outcomeStage)) {
                startS13PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s13PostBattle".equals(outcomeStage)) {
                finishS13Outcome();
                return;
            }
            if ("s14Victory".equals(outcomeStage)
                    || "s14Defeat".equals(outcomeStage)) {
                startS14PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s14PostBattle".equals(outcomeStage)) {
                finishS14Outcome();
                return;
            }
            if ("s15Victory".equals(outcomeStage)
                    || "s15Defeat".equals(outcomeStage)) {
                startS15PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s15PostBattle".equals(outcomeStage)) {
                finishS15Outcome();
                return;
            }
            if ("s16Victory".equals(outcomeStage)
                    || "s16Defeat".equals(outcomeStage)) {
                startS16PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s16PostBattle".equals(outcomeStage)) {
                finishS16Outcome();
                return;
            }
            if ("s17Victory".equals(outcomeStage)
                    || "s17Defeat".equals(outcomeStage)) {
                startS17PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s17PostBattle".equals(outcomeStage)) {
                finishS17Outcome();
                return;
            }
            if ("s18Victory".equals(outcomeStage)
                    || "s18Defeat".equals(outcomeStage)) {
                startS18PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s18PostBattle".equals(outcomeStage)) {
                finishS18Outcome();
                return;
            }
            if ("s19Victory".equals(outcomeStage)
                    || "s19Defeat".equals(outcomeStage)) {
                startS19PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s19PostBattle".equals(outcomeStage)) {
                finishS19Outcome();
                return;
            }
            if ("s20Victory".equals(outcomeStage)
                    || "s20Defeat".equals(outcomeStage)) {
                startS20PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s20PostBattle".equals(outcomeStage)) {
                finishS20Outcome();
                return;
            }
            if ("s21Victory".equals(outcomeStage)
                    || "s21Defeat".equals(outcomeStage)) {
                startS21PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s21PostBattle".equals(outcomeStage)) {
                finishS21Outcome();
                return;
            }
            if ("s22Victory".equals(outcomeStage)
                    || "s22Defeat".equals(outcomeStage)) {
                startS22PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s22PostBattle".equals(outcomeStage)) {
                finishS22Outcome();
                return;
            }
            if ("s23Victory".equals(outcomeStage)
                    || "s23Defeat".equals(outcomeStage)) {
                startS23PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s23PostBattle".equals(outcomeStage)) {
                finishS23Outcome();
                return;
            }
            if ("s24Victory".equals(outcomeStage)
                    || "s24Defeat".equals(outcomeStage)) {
                startS24PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s24PostBattle".equals(outcomeStage)) {
                finishS24Outcome();
                return;
            }
            if ("s25Victory".equals(outcomeStage)
                    || "s25Defeat".equals(outcomeStage)) {
                startS25PostBattleCleanup();
                invalidate();
                return;
            }
            if ("s25PostBattle".equals(outcomeStage)) {
                finishS25Outcome();
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
        if (r03StoryActive) {
            finishR03StoryScene();
            return;
        }
        if (r05StoryActive) {
            finishR05StoryScene();
            return;
        }
        if (r06StoryActive) {
            finishR06StoryScene();
            return;
        }
        if (r07StoryActive) {
            finishR07StoryScene();
            return;
        }
        if (r08StoryActive) {
            finishR08StoryScene();
            return;
        }
        if (r09StoryActive) {
            finishR09StoryScene();
            return;
        }
        if (r10StoryActive) {
            finishR10StoryScene();
            return;
        }
        if (r11StoryActive) {
            finishR11StoryScene();
            return;
        }
        if (r12StoryActive) {
            finishR12StoryScene();
            return;
        }
        if (r13StoryActive) {
            finishR13StoryScene();
            return;
        }
        if (r14StoryActive) {
            finishR14StoryScene();
            return;
        }
        if (r15StoryActive) {
            finishR15StoryScene();
            return;
        }
        if (r16StoryActive) {
            finishR16StoryScene();
            return;
        }
        if (r17StoryActive) {
            finishR17StoryScene();
            return;
        }
        if (r18StoryActive) {
            finishR18StoryScene();
            return;
        }
        if (r19StoryActive) {
            finishR19StoryScene();
            return;
        }
        if (r20StoryActive) {
            finishR20StoryScene();
            return;
        }
        if (r21StoryActive) {
            finishR21StoryScene();
            return;
        }
        if (r22StoryActive) {
            finishR22StoryScene();
            return;
        }
        if (r23StoryActive) {
            finishR23StoryScene();
            return;
        }
        if (r24StoryActive) {
            finishR24StoryScene();
            return;
        }
        if (r25StoryActive) {
            finishR25StoryScene();
            return;
        }

        if (currentBattleIndex == 25
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS25DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }

        if (currentBattleIndex == 10
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS10DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 11
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS11DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 12
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS12DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 13
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS13DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 14
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS14DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 15
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS15DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 16
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS16DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 17
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS17DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 18
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS18DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 19
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS19DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
            return;
        }
        if (currentBattleIndex == 20
                && scriptedBattleFailure
                && !outcomeFlowActive) {
            scriptedBattleFailure = false;
            startS20DefeatOutcome(
                    -1,
                    "원본 전장 이벤트 패배 조건");
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

            case "unitHpCompare": {
                BattleUnit unit = findUnitByCharacterId(
                        trigger.optInt("characterId", -1));
                if (unit == null) {
                    return false;
                }
                return compareScenarioInt(
                        unit.hp,
                        trigger.optInt("value", 0),
                        trigger.optInt("compare", 2));
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

    private boolean unitAliveInArea(
            int characterId,
            int x1,
            int y1,
            int x2,
            int y2) {
        BattleUnit unit = findUnitByCharacterId(characterId);
        if (unit == null || !unit.visible || !unit.isAlive()) {
            return false;
        }
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        return unit.x >= left
                && unit.x <= right
                && unit.y >= top
                && unit.y <= bottom;
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
                || r03StoryActive
                || r05StoryActive
                || r06StoryActive
                || r07StoryActive
                || r08StoryActive
                || r09StoryActive
                || r10StoryActive
                || r11StoryActive
                || r12StoryActive
                || r13StoryActive
                || r14StoryActive
                || r15StoryActive
                || r16StoryActive
                || r17StoryActive
                || r18StoryActive
                || r19StoryActive
                || r20StoryActive
                || r21StoryActive
                || r22StoryActive
                || r23StoryActive
                || r24StoryActive
                || r25StoryActive
                || phaseTransitionActive
                || scriptEventActive) {
            return;
        }


        if (currentBattleIndex == 24) {
            if (firedBattleSections.contains(3) && battlePhase != 2) {
                battlePhase = 2;
                turnLimit = phase2TurnLimit;
                if (phase2ObjectiveText != null
                        && !phase2ObjectiveText.isEmpty()) {
                    objectiveText = phase2ObjectiveText;
                }
                if (phase2PopupText != null
                        && !phase2PopupText.isEmpty()) {
                    objectivePopupText = phase2PopupText;
                }
            }

            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS24DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }

            if (round > turnLimit) {
                startS24DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS24DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            if (battlePhase == 2
                    && unitAliveInArea(1, 0, 0, 22, 2)
                    && unitAliveInArea(327, 0, 0, 22, 2)) {
                startS24VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 23) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS23DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS23DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS23DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            BattleUnit caoCao = findUnitByCharacterId(36);
            if (caoCao != null && !caoCao.isAlive()) {
                startS23VictoryOutcome(false);
                return;
            }
            if (!hasAnyAliveEnemy()) {
                startS23VictoryOutcome(true);
            }
            return;
        }

        if (currentBattleIndex == 22) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS22DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS22DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS22DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("s22-target-defeat-event-driven".equals(battleMode)
                    && killTargetCharacterId >= 0) {
                BattleUnit target = findUnitByCharacterId(
                        killTargetCharacterId);
                if (target != null && !target.isAlive()) {
                    if (killTargetSignalSection < 0
                            || firedBattleSections.contains(
                            killTargetSignalSection)) {
                        startS22VictoryOutcome();
                    }
                }
            }
            return;
        }

        if (currentBattleIndex == 21) {
            boolean phase2 = firedBattleSections.contains(2)
                    || scenarioVariables.getOrDefault(3, 0) != 0;
            if (phase2 && battlePhase != 2) {
                battlePhase = 2;
                protectedCharacterIds.clear();
                protectedCharacterIds.add(0);
                turnLimit = phase2TurnLimit;
                if (phase2ObjectiveText != null
                        && !phase2ObjectiveText.isEmpty()) {
                    objectiveText = phase2ObjectiveText;
                }
                if (phase2PopupText != null
                        && !phase2PopupText.isEmpty()) {
                    objectivePopupText = phase2PopupText;
                }
            }

            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS21DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }

            if (round > turnLimit) {
                startS21DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS21DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            // Section 4 is the original Liu Bei escape-completion path.
            // It sets the scenario completion variables and ends the scene.
            if (firedBattleSections.contains(4)
                    && scenarioVariables.getOrDefault(0, 0) != 0) {
                startS21VictoryOutcome(true);
                return;
            }

            if (!hasAnyAliveEnemy()) {
                startS21VictoryOutcome(false);
            }
            return;
        }

        if (currentBattleIndex == 20) {
            BattleUnit zhangFei = findUnitByCharacterId(2);
            if (zhangFei != null && !zhangFei.isAlive()) {
                startS20DefeatOutcome(
                        2,
                        "장비 사망 · 원본 패배 조건");
                return;
            }
            if (round > turnLimit) {
                startS20DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS20DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("kill-target".equals(battleMode)
                    && killTargetCharacterId >= 0) {
                BattleUnit target = findUnitByCharacterId(
                        killTargetCharacterId);
                if (target != null && !target.isAlive()) {
                    startS20VictoryOutcome();
                }
            }
            return;
        }

        if (currentBattleIndex == 19) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS19DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS19DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS19DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("enemy-annihilation".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS19VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 18) {
            BattleUnit liuBei = findUnitByCharacterId(0);
            if (liuBei != null && !liuBei.isAlive()) {
                startS18DefeatOutcome(
                        0,
                        "유비 사망 · 원본 패배 조건");
                return;
            }
            if (round > turnLimit) {
                startS18DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS18DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            BattleUnit luBu = findUnitByCharacterId(119);
            if (luBu != null && !luBu.isAlive()) {
                startS18VictoryOutcome(
                        119,
                        "여포 격퇴 · 원본 승리 조건");
                return;
            }

            BattleUnit diaoChan = findUnitByCharacterId(158);
            if (diaoChan != null && !diaoChan.isAlive()) {
                startS18VictoryOutcome(
                        158,
                        "초선 격퇴 · 원본 승리 분기");
                return;
            }

            if (!hasAnyAliveEnemy()) {
                startS18VictoryOutcome(
                        -1,
                        "적군 전멸 · 원본 승리 처리");
            }
            return;
        }

        if (currentBattleIndex == 17) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS17DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS17DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS17DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            // Original route:
            // Section 5 confirms all three outer-city enemy regions are empty
            // and sets variable 7. Section 16 consumes variable 7, plays the
            // Cao Cao/Liu Bei transition and executes 0x49 + var0/617 + 0x0D.
            // Therefore Section 16 completion, not total-map annihilation, is
            // the exact native victory signal.
            if ("s17-outer-city-event-driven".equals(battleMode)
                    && firedBattleSections.contains(16)) {
                completeS17OuterCityVictory();
            }
            return;
        }

        if (currentBattleIndex == 16) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS16DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (!hasAnyAliveAlly()) {
                startS16DefeatOutcome(
                        -1,
                        "우군 전멸 · 원본 패배 조건");
                return;
            }
            if (round > turnLimit) {
                startS16DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS16DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("s16-annihilation-with-ally-survival".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS16VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 15) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS15DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (!hasAnyAliveAlly()) {
                startS15DefeatOutcome(
                        -1,
                        "우군 전멸 · 원본 패배 조건");
                return;
            }
            if (round > turnLimit) {
                startS15DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS15DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("s15-annihilation-with-ally-survival".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS15VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 14) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS14DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS14DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS14DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            // S14 Section 11 is the original Liu Bei escape route.
            // The section itself already runs its original ending actions.
            if (firedBattleSections.contains(11)) {
                startS14EscapeOutcome();
                return;
            }

            if ("s14-escape-or-annihilation".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS14VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 13) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS13DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS13DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS13DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("enemy-annihilation".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS13VictoryOutcome();
            }
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
                startS01DefeatOutcome(-1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS01DefeatOutcome(-1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("enemy-annihilation".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS01VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 2) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS02DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS02DefeatOutcome(-1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS02DefeatOutcome(-1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("enemy-annihilation".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS02VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 3) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS03DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS03DefeatOutcome(-1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS03DefeatOutcome(-1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("rescue-character".equals(battleMode)
                    && rescueCharacterId >= 0
                    && rescueGoalX >= 0
                    && rescueGoalY >= 0) {
                BattleUnit rescue = findUnitByCharacterId(
                        rescueCharacterId);
                if (rescue != null
                        && rescue.visible
                        && rescue.isAlive()
                        && rescue.x == rescueGoalX
                        && rescue.y == rescueGoalY) {
                    startS03VictoryOutcome();
                }
            }
            return;
        }

        if (currentBattleIndex >= 4 && currentBattleIndex <= 7) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    if (currentBattleIndex == 4) {
                        startS04DefeatOutcome(characterId,
                                unit.name + " 사망 · 원본 패배 조건");
                    } else if (currentBattleIndex == 5) {
                        startS05DefeatOutcome(characterId,
                                unit.name + " 사망 · 원본 패배 조건");
                    } else if (currentBattleIndex == 6) {
                        startS06DefeatOutcome(characterId,
                                unit.name + " 사망 · 원본 패배 조건");
                    } else {
                        startS07DefeatOutcome(characterId,
                                unit.name + " 사망 · 원본 패배 조건");
                    }
                    return;
                }
            }
            if (round > turnLimit || !hasAnyAliveFriendly()) {
                String reason = round > turnLimit
                        ? turnLimit + "턴 초과 · 원본 패배 조건"
                        : "아군 전멸 · 원본 패배 조건";
                if (currentBattleIndex == 4) {
                    startS04DefeatOutcome(-1, reason);
                } else if (currentBattleIndex == 5) {
                    startS05DefeatOutcome(-1, reason);
                } else if (currentBattleIndex == 6) {
                    startS06DefeatOutcome(-1, reason);
                } else {
                    startS07DefeatOutcome(-1, reason);
                }
                return;
            }
            if ("kill-character".equals(battleMode)
                    && killTargetCharacterId >= 0) {
                BattleUnit target = findUnitByCharacterId(
                        killTargetCharacterId);
                if (target == null || !target.isAlive()) {
                    if (currentBattleIndex == 4) {
                        startS04VictoryOutcome();
                    } else if (currentBattleIndex == 5) {
                        startS05VictoryOutcome();
                    } else if (currentBattleIndex == 6) {
                        startS06VictoryOutcome();
                    } else {
                        startS07VictoryOutcome();
                    }
                }
            }
            return;
        }

        if (currentBattleIndex == 8) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS08DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS08DefeatOutcome(-1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS08DefeatOutcome(-1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            if ("enemy-annihilation".equals(battleMode)
                    && !hasAnyAliveEnemy()) {
                startS08VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 12) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS12DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS12DefeatOutcome(
                        -1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS12DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            if (s12AnnihilationRoute) {
                if (!hasAnyAliveEnemy()) {
                    startS12VictoryOutcome();
                }
            } else if (s12RetreatGoalReached()
                    || !hasAnyAliveEnemy()) {
                startS12VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 11) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS11DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (!hasAnyAliveFriendly()) {
                startS11DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }
            // Original S11 victory route A: Section 9 fires on
            // round 25 / player side, finishes the dawn retreat sequence,
            // then sets scenario variable 9.
            boolean dawnVictory =
                    scenarioVariables.getOrDefault(9, 0) != 0;

            // Original S11 victory route B: Section 10 fires when a
            // player/ally occupies Guangling at (11,0), then sets variable
            // 16 after the capture/reward/retreat sequence.
            boolean guanglingVictory =
                    scenarioVariables.getOrDefault(16, 0) != 0;

            if (dawnVictory || guanglingVictory) {
                startS11VictoryOutcome();
            }
            return;
        }

        if (currentBattleIndex == 10) {
            BattleUnit liuBei = findUnitByCharacterId(0);
            if (liuBei != null && !liuBei.isAlive()) {
                startS10DefeatOutcome(
                        0,
                        liuBei.name + " 사망 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS10DefeatOutcome(
                        -1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            if (s10DefenseRoute) {
                // var71 route: survive through turn 15.
                if (round > turnLimit) {
                    startS10VictoryOutcome();
                }
                return;
            }

            // var70 route: the original Section 24/26 gates decide which
            // commander defeat is a terminal victory.
            if (tryStartS10AttackVictory(137)
                    || tryStartS10AttackVictory(215)) {
                return;
            }
            return;
        }

        if (currentBattleIndex == 9) {
            for (int characterId : protectedCharacterIds) {
                BattleUnit unit = findUnitByCharacterId(characterId);
                if (unit != null && !unit.isAlive()) {
                    startS09DefeatOutcome(
                            characterId,
                            unit.name + " 사망 · 원본 패배 조건");
                    return;
                }
            }
            if (round > turnLimit) {
                startS09DefeatOutcome(-1,
                        turnLimit + "턴 초과 · 원본 패배 조건");
                return;
            }
            if (!hasAnyAliveFriendly()) {
                startS09DefeatOutcome(-1,
                        "아군 전멸 · 원본 패배 조건");
                return;
            }

            boolean directVictoryComplete =
                    s09DirectVictoryVariable >= 0
                    && s09DirectVictoryCompletionVariable >= 0
                    && scenarioVariables.getOrDefault(
                    s09DirectVictoryVariable,
                    0) != 0
                    && scenarioVariables.getOrDefault(
                    s09DirectVictoryCompletionVariable,
                    0) != 0;

            if ("s09-xuzhou-rescue".equals(battleMode)
                    && directVictoryComplete) {
                startS09DirectVictoryCleanup();
                return;
            }

            boolean caoCaoDefeated =
                    s09CaoCaoDefeatVariable >= 0
                    && scenarioVariables.getOrDefault(
                    s09CaoCaoDefeatVariable,
                    0) != 0;
            if ("s09-xuzhou-rescue".equals(battleMode)
                    && caoCaoDefeated) {
                startS09VictoryOutcome();
            }
            return;
        }

        for (int characterId : protectedCharacterIds) {
            BattleUnit unit = findUnitByCharacterId(characterId);
            if (unit != null && !unit.isAlive()) {
                endBattle(false,
                        unit.name + " 사망 · 원본 패배 조건");
                return;
            }
        }
        if (round > turnLimit) {
            endBattle(false,
                    turnLimit + "턴 초과 · 원본 패배 조건");
            return;
        }
        if ("enemy-annihilation".equals(battleMode)) {
            if (!hasAnyAliveEnemy()) {
                endBattle(true,
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
                    endBattle(false,
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

    private boolean hasAnyAliveAlly() {
        for (BattleUnit unit : units) {
            if (unit.isAlive() && "ally".equals(unit.faction)) {
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
                || selectedUnit.activeMovePoints <= 0) {
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

                if (!insideAiArea(unit, nx, ny)) {
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
                if (nextCost > unit.activeMovePoints) {
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
                || reachableBest[target] > selectedUnit.activeMovePoints
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
        return unit.activeAttackRangeId == 0
                || unit.activeAttackRangeId == 1;
    }

    private boolean isInAttackRange(
            BattleUnit attacker,
            BattleUnit target) {
        int dx = Math.abs(target.x - attacker.x);
        int dy = Math.abs(target.y - attacker.y);

        if (attacker.activeAttackRangeId == 0) {
            return dx + dy == 1;
        }
        if (attacker.activeAttackRangeId == 1) {
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
                || unit.activeMovePoints <= 0
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
                    || cost > unit.activeMovePoints) {
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
                || unit.activeJobFamily < 0
                || unit.activeJobFamily
                >= movementCostFamilyCount) {
            return IMPASSABLE;
        }

        int index = unit.activeJobFamily
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

    private BattleUnit findUnitByBattleNumber(int battleNumber) {
        for (BattleUnit unit : units) {
            if (unit.battleNumber == battleNumber) {
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
