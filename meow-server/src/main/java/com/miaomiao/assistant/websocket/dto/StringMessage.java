package com.miaomiao.assistant.websocket.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Text message from client
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StringMessage extends WSMessage {
    private String text;
    private String sessionId;
}
