package com.miaomiao.assistant.websocket.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * TTS (Text-to-Speech) audio message to client
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TTSMessage extends WSMessage {
    private String format;       // Audio format (e.g., "audio/mpeg")
    private String data;         // Base64 encoded audio data
    private boolean isFinished;
}
