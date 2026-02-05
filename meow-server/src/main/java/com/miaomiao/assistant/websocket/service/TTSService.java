package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.codec.AudioConverter;
import com.miaomiao.assistant.model.tts.TTSManager;
import com.miaomiao.assistant.service.ConversationConfigService;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.websocket.message.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.service.pipeline.FrameProcessor;
import com.miaomiao.assistant.websocket.service.pipeline.Frames;
import com.miaomiao.assistant.websocket.service.pipeline.TTSFrameProcessor;
import com.miaomiao.assistant.websocket.service.pipeline.TextAggregator;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * TTS（语音合成）处理服务
 * <p>
 * 基于 Pipecat 架构重构，使用 TTSFrameProcessor 实现流式 TTS 处理：
 * 1. 文本聚合策略（HYBRID：首句激进、后续完整句子）
 * 2. 音频时机控制（模拟真实音频设备播放速率）
 * 3. 支持中断
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TTSService {

    private final TTSManager ttsManager;
    private final AudioConverter audioConverter;
    private final WebSocketMessageSender messageSender;
    private final ConversationConfigService configService;

    /**
     * 处理TTS流 - 使用 Pipecat 管道架构
     * <p>
     * 采用 HYBRID 聚合策略：
     * - 首句使用宽松标点（逗号、顿号等）→ 降低首句延迟
     * - 后续使用完整句子标点 → 保证 TTS 合成质量
     *
     * @param state      会话状态
     * @param textStream 句子文本流（来自 LLM）
     * @param config     对话配置
     */
    public void processTTSStream(SessionState state, Flux<String> textStream, ConversationConfig config) {
        // 创建 TTS Frame 处理器（使用 HYBRID 策略优化首句延迟）
        TTSFrameProcessor processor = new TTSFrameProcessor(
                ttsManager,
                audioConverter,
                messageSender,
                configService,
                state,
                TextAggregator.AggregationStrategy.HYBRID
        );

        FrameProcessor.ProcessingContext context = new FrameProcessor.ProcessingContext(state.getSessionId());

        log.info("会话 {} 启动 TTS 流处理，使用 HYBRID 聚合策略", state.getSessionId());

        // 在独立线程处理 TTS（阻塞操作）
        textStream
                .takeWhile(text -> !state.isAborted())
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(text -> {
                    if (state.isAborted() || context.isInterrupted()) {
                        return;
                    }
                    try {
                        // 推送文本 Frame 到处理器
                        processor.processFrame(new Frames.TextFrame(text), context);
                    } catch (FrameProcessor.FrameProcessingException e) {
                        log.error("TTS 处理文本失败: {}", text, e);
                    }
                })
                .doOnError(error -> log.error("TTS 流错误", error))
                .doOnComplete(() -> {
                    // 完成时处理剩余文本
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
