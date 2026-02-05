package com.miaomiao.assistant.model.tts;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TTSOptions {
    /**
     * 模型名称
     */
    private String model;

    /**
     * 音色
     */
    private String voice;

    /**
     * 语速 (0.5-2.0)
     */
    private Float speed;

    /**
     * 音量 (0.0-1.0)
     */
    private Float volume;

    /**
     * 输出格式 (pcm, mp3, wav)
     */
    private String format;
}