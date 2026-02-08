package com.miaomiao.assistant.websocket.message;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Audio message from client
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AudioMessage extends WSMessage {
    private String format;       // Audio format: webm, opus, wav, mp3
    private byte[] data;         // Raw audio bytes from binary frame payload
    private boolean last;        // Is this the last audio chunk
}
