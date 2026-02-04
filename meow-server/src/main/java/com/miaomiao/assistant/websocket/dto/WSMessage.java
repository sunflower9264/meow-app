package com.miaomiao.assistant.websocket.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;

/**
 * WebSocket message base class
 */
@Data
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AudioMessage.class, name = "audio"),
        @JsonSubTypes.Type(value = StringMessage.class, name = "text"),
        @JsonSubTypes.Type(value = ControlMessage.class, name = "control"),
        @JsonSubTypes.Type(value = STTMessage.class, name = "stt"),
        @JsonSubTypes.Type(value = TTSMessage.class, name = "tts"),
        @JsonSubTypes.Type(value = SentenceMessage.class, name = "sentence"),
})

public abstract class WSMessage {
    private String type;
    private long timestamp;
}
