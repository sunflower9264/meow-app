package com.miaomiao.assistant.websocket.message;

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
        @JsonSubTypes.Type(value = StringMessage.class, name = "text"),
        @JsonSubTypes.Type(value = TerminateMessage.class, name = "terminate")
})

public abstract class WSMessage {
    private String type;
    private long timestamp;
}
