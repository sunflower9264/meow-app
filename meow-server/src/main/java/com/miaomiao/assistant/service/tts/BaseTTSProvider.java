package com.miaomiao.assistant.service.tts;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Flux;

/**
 * TTS提供商抽象基类
 */
public abstract class BaseTTSProvider {

    protected String providerName;
    protected String apiKey;
    protected String baseUrl;

    /**
     * TTS音频结果
     */
    @Data
    @AllArgsConstructor
    public static class TTSAudio {
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

    /**
     * TTS请求选项
     */
    @Data
    public static class TTSOptions {
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

    /**
     * 获取提供商名称
     */
    public String getProviderName() {
        return providerName;
    }

    /**
     * 检查是否支持指定模型
     */
    public abstract boolean supportsModel(String model);

    /**
     * 获取支持的模型列表
     */
    public abstract String[] getSupportedModels();

    /**
     * 文本转语音 (非流式)
     *
     * @param text    要转换的文本
     * @param options TTS选项
     * @return 音频数据
     */
    public abstract TTSAudio textToSpeech(String text, TTSOptions options);

    /**
     * 文本转语音 (流式)
     *
     * @param text    要转换的文本
     * @param options TTS选项
     * @return 音频数据流
     */
    public abstract Flux<TTSAudio> textToSpeechStream(String text, TTSOptions options);
}
