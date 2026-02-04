package com.miaomiao.assistant.model.tts;

import com.miaomiao.assistant.model.BaseModelProvider;
import reactor.core.publisher.Flux;

/**
 * TTS提供商抽象基类
 */
public abstract class BaseTTSProvider extends BaseModelProvider {

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
