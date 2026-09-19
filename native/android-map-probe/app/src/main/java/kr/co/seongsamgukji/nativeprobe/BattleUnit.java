package kr.co.seongsamgukji.nativeprobe;

import java.util.ArrayList;
import java.util.List;

public class BattleUnit {
    public final int characterId;
    public final String name;
    public final int spriteId;
    public final int jobId;
    public final int jobFamily;
    public final int movePoints;
    public final int attackRangeId;
    public final String faction;
    public final boolean scripted;

    public boolean visible;
    public int x;
    public int y;
    public int direction;
    public int targetX;
    public int targetY;
    public int moveFrame;
    public int actionFrame;
    public long actionUntil;
    public long lastMoveStepAt;

    public final List<Integer> movePath = new ArrayList<>();
    public int movePathIndex;

    public BattleUnit(
            int characterId,
            String name,
            int spriteId,
            int jobId,
            int jobFamily,
            int movePoints,
            int attackRangeId,
            String faction,
            boolean scripted,
            boolean visible,
            int x,
            int y,
            int direction) {
        this.characterId = characterId;
        this.name = name;
        this.spriteId = spriteId;
        this.jobId = jobId;
        this.jobFamily = jobFamily;
        this.movePoints = movePoints;
        this.attackRangeId = attackRangeId;
        this.faction = faction;
        this.scripted = scripted;
        this.visible = visible;
        this.x = x;
        this.y = y;
        this.direction = direction;
        this.targetX = x;
        this.targetY = y;
        this.moveFrame = 0;
        this.actionFrame = 0;
        this.actionUntil = 0L;
        this.lastMoveStepAt = 0L;
        this.movePathIndex = 0;
    }

    public boolean hasPlannedPath() {
        return movePathIndex < movePath.size();
    }

    public void clearMovePath() {
        movePath.clear();
        movePathIndex = 0;
    }

    public boolean isMoving() {
        return hasPlannedPath() || x != targetX || y != targetY;
    }

    public boolean isPlayer() {
        return "player".equals(faction);
    }
}
