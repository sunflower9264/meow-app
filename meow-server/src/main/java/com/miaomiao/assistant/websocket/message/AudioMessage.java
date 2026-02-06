package com.miaomiao.assistant.websocket.message;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Audio message from client
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AudioMessage extends WSMessage {
    private String format;       // Audio format: webm, opus, wav, mp3
    private byte[] data;         // Base64 encoded audio data
    @JsonProperty("isLast")
    @JsonAlias("last")
    private boolean last;        // Is this the last audio chunk
}
