package com.miaomiao.assistant.session.websocket.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Text message from client
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TextMessage extends WSMessage {
    private String text;
    private String sessionId;
}
