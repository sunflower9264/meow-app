package com.miaomiao.assistant.session.websocket.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Sentence boundary message for dual streaming
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SentenceMessage extends WSMessage {
    private String eventType;    // sentence_start, sentence_end
    private String text;
    private int index;
}
