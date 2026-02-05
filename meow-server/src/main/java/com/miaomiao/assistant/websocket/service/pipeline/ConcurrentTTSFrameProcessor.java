package com.miaomiao.assistant.websocket.service.pipeline;

import com.miaomiao.assistant.codec.AudioConverter;
import com.miaomiao.assistant.model.tts.TTSManager;
import com.miaomiao.assistant.model.tts.TTSOptions;
import com.miaomiao.assistant.service.ConversationConfigService;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.websocket.message.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 并发 TTS Frame 处理器
 * <p>
 * 当 LLM 返回较快时，可以并发调用 TTS API 提高效率，同时保证播放顺序。
 * <p>
 * 与同步版本 {@link TTSFrameProcessor} 的区别：
 * 1. 使用 {@link ConcurrentTTSProcessor} 并发执行 TTS 调用
 * 2. 通过有序队列保证音频按句子顺序播放
 * 3. 更低的首句延迟和更高的吞吐量
 * <p>
 * 适用场景：
 * - LLM 响应速度快
 * - 长文本对话
 * - 需要低延迟响应
 *
 * @author Pipecat移植优化
 */
@Slf4j
public class ConcurrentTTSFrameProcessor implements FrameProcessor {

    private final ConversationConfigService configService;
    private final SessionState sessionState;

    /**
     * -- GETTER --
     *  获取文本聚合器
     */
    @Getter
    private final TextAggregator textAggregator;
    private final ConcurrentTTSProcessor concurrentProcessor;

    /**
     * 构造函数
     *
     * @param ttsManager          TTS 管理器
     * @param audioConverter      音频转换器
     * @param messageSender       WebSocket 消息发送器
     * @param configService       配置服务
     * @param sessionState        会话状态
     * @param aggregationStrategy 聚合策略
     * @param maxConcurrency      最大并发数（建议 2-4）
     */
    public ConcurrentTTSFrameProcessor(
            TTSManager ttsManager,
            AudioConverter audioConverter,
            WebSocketMessageSender messageSender,
            ConversationConfigService configService,
            SessionState sessionState,
            TextAggregator.AggregationStrategy aggregationStrategy,
            int maxConcurrency) {
        this.configService = configService;
        this.sessionState = sessionState;

        // 创建文本聚合器
        this.textAggregator = new TextAggregator(
                TextAggregator.AggregationConfig.create()
                        .strategy(aggregationStrategy)
        );

        // 创建并发 TTS 处理器
        this.concurrentProcessor = new ConcurrentTTSProcessor(
                ttsManager,
                audioConverter,
                (opusFrame, isLast) -> {
                    try {
                        // 记录 TTS 首次响应时间（性能指标）
                        sessionState.getPerformanceMetrics().recordTTSFirstResponse();
                        messageSender.sendTTSAudio(sessionState, opusFrame, isLast);
                    } catch (Exception e) {
                        log.error("发送音频帧失败", e);
                    }
                },
                errorMsg -> log.warn("TTS 错误: {}", errorMsg),
                maxConcurrency,
                // 完整音频回调（性能指标 - 保存音频文件）
                (opusData) -> sessionState.getPerformanceMetrics().saveTTSAudio(opusData)
        );
    }

    /**
     * 构造函数（使用默认并发数 3）
     */
    public ConcurrentTTSFrameProcessor(
            TTSManager ttsManager,
            AudioConverter audioConverter,
            WebSocketMessageSender messageSender,
            ConversationConfigService configService,
            SessionState sessionState,
            TextAggregator.AggregationStrategy aggregationStrategy) {
        this(ttsManager, audioConverter, messageSender, configService, sessionState,
                aggregationStrategy, 3);
    }

    @Override
    public void processFrame(Frame frame, ProcessingContext context) throws FrameProcessingException {
        if (context.isInterrupted() || sessionState.isAborted()) {
            return;
        }

        switch (frame.getType()) {
            case TEXT:
            case LLM_TEXT:
            case AGGREGATED_TEXT:
                processTextFrame(frame, context);
                break;

            case INTERRUPTION:
                processInterruptionFrame();
                break;

            case END:
                processEndFrame();
                break;

            default:
                break;
        }
    }

    /**
     * 处理文本 Frame
     */
    private void processTextFrame(Frame frame, ProcessingContext context) throws FrameProcessingException {
        String text = extractText(frame);
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // 检查是否跳过 TTS
        if (frame instanceof Frames.AggregatedTextFrame) {
            Frames.AggregatedTextFrame aggFrame = (Frames.AggregatedTextFrame) frame;
            if (aggFrame.isSkipTts()) {
                log.debug("跳过 TTS: {}", text);
                return;
            }
        }

        // 使用文本聚合器处理
        var results = textAggregator.append(text);

        for (var result : results) {
            if (context.isInterrupted() || sessionState.isAborted()) {
                break;
            }

            // 提交 TTS 任务（非阻塞）
            submitTTSTask(result.getText(), result.getType());
        }
    }

    /**
     * 提取文本内容
     */
    private String extractText(Frame frame) {
        if (frame instanceof Frames.TextFrame) {
            return ((Frames.TextFrame) frame).getText();
        } else if (frame instanceof Frames.LLMTextFrame) {
            return ((Frames.LLMTextFrame) frame).getText();
        } else if (frame instanceof Frames.AggregatedTextFrame) {
            return ((Frames.AggregatedTextFrame) frame).getText();
        }
        return null;
    }

    /**
     * 提交 TTS 任务
     */
    private void submitTTSTask(String text, TextAggregator.AggregationType type) {
        // 通过预处理管道过滤和拆分文本
        java.util.List<String> processedTexts = TextPreProcessorPipeline.getInstance().process(text);
        if (processedTexts.isEmpty()) {
            return;
        }

        try {
            ConversationConfig config = configService.getConfigBySessionId(sessionState.getSessionId());

            TTSOptions options = TTSOptions.builder()
                    .model(config.getTtsModel())
                    .voice(config.getTtsVoice())
                    .speed(config.getTtsSpeed())
                    .volume(config.getTtsVolume())
                    .format(config.getTtsFormat())
                    .build();

            for (String processedText : processedTexts) {
                concurrentProcessor.submitTask(processedText, type, config.getTTSModelKey(), options);
            }
        } catch (Exception e) {
            log.error("提交 TTS 任务失败: text={}", text, e);
        }
    }

    /**
     * 处理中断 Frame
     */
    private void processInterruptionFrame() {
        log.debug("收到中断 Frame，停止处理");
        textAggregator.reset();
        concurrentProcessor.interrupt();
        sessionState.abort();
    }

    /**
     * 处理结束 Frame
     */
    private void processEndFrame() throws FrameProcessingException {
        // 处理剩余文本
        var remaining = textAggregator.complete();
        if (remaining != null) {
            submitTTSTask(remaining.getText(), remaining.getType());
        }

        // 标记任务提交完成
        concurrentProcessor.complete();

        // 等待所有任务处理完成（最多等待 30 秒）
        try {
            boolean completed = concurrentProcessor.awaitCompletion(30, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("等待 TTS 任务完成超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待 TTS 任务完成被中断");
        }

        log.debug("并发 TTS 处理完成");
    }

    /**
     * 获取待处理任务数
     */
    public int getPendingCount() {
        return concurrentProcessor.getPendingCount();
    }

    /**
     * 关闭处理器
     */
    public void close() {
        concurrentProcessor.close();
    }
}
