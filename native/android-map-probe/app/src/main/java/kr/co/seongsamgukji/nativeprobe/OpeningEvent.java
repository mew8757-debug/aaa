package kr.co.seongsamgukji.nativeprobe;

import org.json.JSONObject;

public final class OpeningEvent {
    public final String type;
    public final int characterId;
    public final int targetId;
    public final int x;
    public final int y;
    public final int direction;
    public final int variableId;
    public final int value;
    public final String speaker;
    public final String text;

    private OpeningEvent(
            String type,
            int characterId,
            int targetId,
            int x,
            int y,
            int direction,
            int variableId,
            int value,
            String speaker,
            String text) {
        this.type = type;
        this.characterId = characterId;
        this.targetId = targetId;
        this.x = x;
        this.y = y;
        this.direction = direction;
        this.variableId = variableId;
        this.value = value;
        this.speaker = speaker;
        this.text = text;
    }

    public static OpeningEvent fromJson(JSONObject obj) {
        return new OpeningEvent(
                obj.optString("type", ""),
                obj.optInt("characterId", -1),
                obj.optInt("targetId", -1),
                obj.has("x") ? obj.optInt("x") : Integer.MIN_VALUE,
                obj.has("y") ? obj.optInt("y") : Integer.MIN_VALUE,
                obj.optInt("direction", -1),
                obj.optInt("variableId", -1),
                obj.optInt("value", 0),
                obj.optString("speaker", ""),
                obj.optString("text", ""));
    }
}
