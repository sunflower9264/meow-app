package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.codec.OpusEncoder;
import com.miaomiao.assistant.model.tts.TTSManager;
import com.miaomiao.assistant.service.ConversationConfigService;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.websocket.message.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.service.pipeline.ConcurrentTTSFrameProcessor;
import com.miaomiao.assistant.websocket.service.pipeline.FrameProcessor;
import com.miaomiao.assistant.websocket.service.pipeline.Frames;
import com.miaomiao.assistant.websocket.service.pipeline.TextAggregator;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * TTS（语音合成）处理服务
 * <p>
 * 基于 Pipecat 架构，使用并发模式处理 TTS。
 * <p>
 * 核心特性：
 * 1. 文本聚合策略（HYBRID：首句激进、后续完整句子）
 * 2. 音频时机控制（模拟真实音频设备播放速率）
 * 3. 支持中断
 * 4. 并发模式下的有序播放保证
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TTSService {

    private final TTSManager ttsManager;
    private final OpusEncoder opusEncoder;
    private final WebSocketMessageSender messageSender;
    private final ConversationConfigService configService;

    /**
     * TTS 并发数
     * <p>
     * 建议值：2-4，过高可能导致 TTS 服务限流
     */
    @Value("${tts.concurrent.max-concurrency:3}")
    private int maxConcurrency;

    /**
     * 处理TTS流 - 使用 Pipecat 管道架构
     * <p>
     * 采用 HYBRID 聚合策略：
     * - 首句使用宽松标点（逗号、顿号等）→ 降低首句延迟
     * - 后续使用完整句子标点 → 保证 TTS 合成质量
     * <p>
     * 使用并发模式：多线程并发调用 TTS，有序队列保证播放顺序
     *
     * @param state      会话状态
     * @param textStream 句子文本流（来自 LLM）
     * @param config     对话配置
     */
    public void processTTSStream(SessionState state, Flux<String> textStream, ConversationConfig config) {
        ConcurrentTTSFrameProcessor processor = new ConcurrentTTSFrameProcessor(
                ttsManager,
                opusEncoder,
                messageSender,
                configService,
                state,
                TextAggregator.AggregationStrategy.HYBRID,
                maxConcurrency
        );

        FrameProcessor.ProcessingContext context = new FrameProcessor.ProcessingContext(state.getSessionId());

        textStream
                .takeWhile(text -> !state.isAborted())
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(text -> {
                    if (state.isAborted() || context.isInterrupted()) {
                        return;
                    }
                    try {
                        processor.processFrame(new Frames.TextFrame(text), context);
                    } catch (FrameProcessor.FrameProcessingException e) {
                        log.error("TTS 处理文本失败: {}", text, e);
                    }
                })
                .doOnError(error -> {
                    log.error("TTS 流错误", error);
                    processor.close();
                })
                .doOnComplete(() -> {
                    try {
                        processor.processFrame(new Frames.EndFrame(), context);
                        log.debug("会话 {} TTS 流处理完成", state.getSessionId());
                    } catch (FrameProcessor.FrameProcessingException e) {
                        log.error("处理结束帧失败", e);
                    }
                })
                .subscribe();
    }
}
