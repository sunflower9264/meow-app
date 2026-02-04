package com.miaomiao.assistant.websocket.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Audio message from client
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AudioMessage extends WSMessage {
    private String format;       // Audio format: opus, wav, mp3
    private byte[] data;         // Base64 encoded audio data
    private boolean isLast;      // Is this the last audio chunk
}
