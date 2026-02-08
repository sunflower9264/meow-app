package com.miaomiao.assistant.model.asr;

import com.miaomiao.assistant.model.BaseModelProvider;
import reactor.core.publisher.Flux;

/**
 * ASR提供商抽象基类
 */
public abstract class BaseASRModelProvider extends BaseModelProvider {

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
