package com.miaomiao.assistant.model.asr;

import com.miaomiao.assistant.model.BaseModelProvider;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;

/**
 * ASR提供商抽象基类
 */
public abstract class BaseASRModelProvider extends BaseModelProvider {

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
