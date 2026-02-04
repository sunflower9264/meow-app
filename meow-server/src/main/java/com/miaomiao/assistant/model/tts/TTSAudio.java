package com.miaomiao.assistant.model.tts;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TTSAudio {
    /**
     * 音频数据
     */
    private byte[] audioData;

    /**
     * 音频格式 (pcm, mp3, wav等)
     */
    private String format;

    /**
     * 是否是最后一帧
     */
    private boolean finished;
}