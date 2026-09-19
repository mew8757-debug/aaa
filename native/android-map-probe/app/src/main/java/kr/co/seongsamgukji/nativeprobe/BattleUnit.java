package kr.co.seongsamgukji.nativeprobe;

public class BattleUnit {
    public final int characterId;
    public final String name;
    public final int spriteId;
    public final String faction;
    public final boolean scripted;
    public int x;
    public int y;
    public int direction;
    public int targetX;
    public int targetY;
    public int moveFrame;

    public BattleUnit(int characterId, String name, int spriteId, String faction,
                      boolean scripted, int x, int y, int direction) {
        this.characterId = characterId;
        this.name = name;
        this.spriteId = spriteId;
        this.faction = faction;
        this.scripted = scripted;
        this.x = x;
        this.y = y;
        this.direction = direction;
        this.targetX = x;
        this.targetY = y;
        this.moveFrame = 0;
    }

    public boolean isMoving() {
        return x != targetX || y != targetY;
    }

    public boolean isPlayer() {
        return "player".equals(faction);
    }
}
