package com.miaomiao.assistant.model.asr;

import lombok.Data;

/**
 * ASR请求选项
 */
@Data
public class ASROptions {
    /**
     * 模型名称
     */
    private String model;

    /**
     * 音频格式 (wav, mp3, pcm等)
     */
    private String format = "wav";

    /**
     * 采样率
     */
    private Integer sampleRate;

    /**
     * 语言
     */
    private String language;

    public static ASROptions of(String model) {
        ASROptions options = new ASROptions();
        options.setModel(model);
        return options;
    }

    public static ASROptions of(String model, String format) {
        ASROptions options = of(model);
        options.setFormat(format);
        return options;
    }
}