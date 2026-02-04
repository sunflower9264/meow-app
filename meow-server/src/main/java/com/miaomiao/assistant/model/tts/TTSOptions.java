package com.miaomiao.assistant.model.tts;

import lombok.Data;

@Data
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
    private Float speed = 1.0f;

    /**
     * 音量 (0.0-1.0)
     */
    private Float volume = 1.0f;

    /**
     * 输出格式 (pcm, mp3, wav)
     */
    private String format = "pcm";

    public static TTSOptions of(String model, String voice) {
        TTSOptions options = new TTSOptions();
        options.setModel(model);
        options.setVoice(voice);
        return options;
    }

    public static TTSOptions of(String model, String voice, String format) {
        TTSOptions options = of(model, voice);
        options.setFormat(format);
        return options;
    }
}