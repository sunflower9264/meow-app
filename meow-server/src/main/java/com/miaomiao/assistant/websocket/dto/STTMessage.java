package com.miaomiao.assistant.websocket.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * STT (Speech-to-Text) result message to client
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class STTMessage extends WSMessage {
    private String text;
    private boolean isFinal;
    private String sessionId;
}
