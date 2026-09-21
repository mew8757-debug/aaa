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
    public final int level;
    public int maxHp;

    public int activeSpriteId;
    public int activeJobId;
    public int activeJobFamily;
    public int activeMovePoints;
    public int activeAttackRangeId;
    public int maxMp;
    public int mp;
    public final int attack;
    public final int defense;
    public final String faction;
    public final boolean scripted;

    public boolean visible;
    public int hp;
    public int x;
    public int y;
    public int direction;
    public int targetX;
    public int targetY;
    public int moveFrame;
    public int actionFrame;
    public long actionUntil;
    public long lastMoveStepAt;
    public long attackStartedAt;
    public long attackUntil;
    public boolean moved;
    public boolean acted;
    public int battleNumber;
    public int aiPolicy;
    public int aiTargetCharacterId;
    public int aiTargetX;
    public int aiTargetY;
    public int debuffMask;
    public int attackCondition;
    public int defenseCondition;
    public int spiritCondition;
    public int burstCondition;
    public int moraleCondition;
    public int spiritBonus;
    public boolean aiAreaEnabled;
    public int aiAreaLeft;
    public int aiAreaTop;
    public int aiAreaRight;
    public int aiAreaBottom;

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
            int level,
            int maxHp,
            int attack,
            int defense,
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
        this.level = level;
        this.maxHp = Math.max(1, maxHp);
        this.hp = this.maxHp;
        this.activeSpriteId = spriteId;
        this.activeJobId = jobId;
        this.activeJobFamily = jobFamily;
        this.activeMovePoints = movePoints;
        this.activeAttackRangeId = attackRangeId;
        this.maxMp = 0;
        this.mp = 0;
        this.attack = Math.max(0, attack);
        this.defense = Math.max(0, defense);
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
        this.attackStartedAt = 0L;
        this.attackUntil = 0L;
        this.moved = false;
        this.acted = false;
        this.battleNumber = -1;
        this.aiPolicy = 1;
        this.aiTargetCharacterId = -1;
        this.aiTargetX = -1;
        this.aiTargetY = -1;
        this.debuffMask = 0;
        this.attackCondition = 1;
        this.defenseCondition = 1;
        this.spiritCondition = 1;
        this.burstCondition = 1;
        this.moraleCondition = 1;
        this.spiritBonus = 0;
        this.aiAreaEnabled = false;
        this.aiAreaLeft = 0;
        this.aiAreaTop = 0;
        this.aiAreaRight = 255;
        this.aiAreaBottom = 255;
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

    public boolean isAlive() {
        return hp > 0;
    }

    public boolean isPlayer() {
        return "player".equals(faction);
    }

    public boolean isEnemy() {
        return "enemy".equals(faction);
    }
}
