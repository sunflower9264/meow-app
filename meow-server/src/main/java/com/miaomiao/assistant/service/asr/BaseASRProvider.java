package com.miaomiao.assistant.service.asr;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;

/**
 * ASR提供商抽象基类
 */
public abstract class BaseASRProvider {

    protected String providerName;
    protected String apiKey;
    protected String baseUrl;

    /**
     * ASR识别结果
     */
    @Data
    @AllArgsConstructor
    public static class ASRResult {
        /**
         * 识别的文本
         */
        private String text;

        /**
         * 是否是最终结果（流式识别时使用）
         */
        private boolean isFinal;

        /**
         * 置信度 (0-1)
         */
        private Float confidence;

        public static ASRResult of(String text) {
            return new ASRResult(text, true, null);
        }

        public static ASRResult partial(String text) {
            return new ASRResult(text, false, null);
        }
    }

    /**
     * ASR请求选项
     */
    @Data
    public static class ASROptions {
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
     * 语音转文字（同步）
     *
     * @param audioData 音频数据
     * @param options   ASR选项
     * @return 识别结果
     */
    public abstract ASRResult speechToText(byte[] audioData, ASROptions options);

    /**
     * 语音转文字（异步）
     *
     * @param audioData 音频数据
     * @param options   ASR选项
     * @return 识别结果Future
     */
    public CompletableFuture<ASRResult> speechToTextAsync(byte[] audioData, ASROptions options) {
        return CompletableFuture.supplyAsync(() -> speechToText(audioData, options));
    }

    /**
     * 语音转文字（流式）
     * 用于实时语音识别场景
     *
     * @param audioStream 音频数据流
     * @param options     ASR选项
     * @return 识别结果流
     */
    public abstract Flux<ASRResult> speechToTextStream(Flux<byte[]> audioStream, ASROptions options);
}
