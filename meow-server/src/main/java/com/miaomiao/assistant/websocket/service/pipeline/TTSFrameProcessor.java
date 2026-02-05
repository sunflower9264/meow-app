package com.miaomiao.assistant.websocket.service.pipeline;

import com.miaomiao.assistant.codec.AudioConverter;
import com.miaomiao.assistant.model.tts.TTSManager;
import com.miaomiao.assistant.model.tts.TTSOptions;
import com.miaomiao.assistant.model.tts.TTSAudio;
import com.miaomiao.assistant.service.ConversationConfigService;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.websocket.message.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * TTS Frame 处理器
 * <p>
 * 移植自 Pipecat 的 TTSService，将文本流转换为音频流，并应用精确的时机控制。
 * <p>
 * 核心优化：
 * 1. 可配置的文本聚合策略（推荐 HYBRID：首句激进、后续完整句子）
 * 2. 精确的音频发送时机控制（模拟真实音频设备播放速率）
 * 3. 支持中断
 * <p>
 * 参考: pipecat-main/src/pipecat/services/tts_service.py
 *
 * @author Pipecat移植
 */
@Slf4j
public class TTSFrameProcessor implements FrameProcessor {

    private final TTSManager ttsManager;
    private final AudioConverter audioConverter;
    private final WebSocketMessageSender messageSender;
    private final ConversationConfigService configService;
    private final SessionState sessionState;

    private final TextAggregator textAggregator;
    private final AudioTimingManager timingController;

    // 音频参数
    private static final int DEFAULT_SAMPLE_RATE = 24000;
    private static final int DEFAULT_CHANNELS = 1;
    private static final int OPUS_FRAME_DURATION_MS = 20; // OPUS 帧时长（毫秒）

    /**
     * 构造函数
     *
     * @param ttsManager          TTS 管理器
     * @param audioConverter      音频转换器（PCM -> OPUS）
     * @param messageSender       WebSocket 消息发送器
     * @param configService       配置服务
     * @param sessionState        会话状态
     * @param aggregationStrategy 聚合策略（推荐 HYBRID）
     */
    public TTSFrameProcessor(
            TTSManager ttsManager,
            AudioConverter audioConverter,
            WebSocketMessageSender messageSender,
            ConversationConfigService configService,
            SessionState sessionState,
            TextAggregator.AggregationStrategy aggregationStrategy) {
        this.ttsManager = ttsManager;
        this.audioConverter = audioConverter;
        this.messageSender = messageSender;
        this.configService = configService;
        this.sessionState = sessionState;

        // 创建文本聚合器
        this.textAggregator = new TextAggregator(
                TextAggregator.AggregationConfig.create()
                        .strategy(aggregationStrategy)
        );

        // 创建音频时机控制器（OPUS 每帧 20ms）
        this.timingController = new AudioTimingManager(OPUS_FRAME_DURATION_MS);

        log.debug("TTS Frame 处理器初始化: strategy={}, sampleRate={}, opusFrameDuration={}ms",
                aggregationStrategy, DEFAULT_SAMPLE_RATE, OPUS_FRAME_DURATION_MS);
    }

    /**
     * 构造函数（使用推荐的 HYBRID 聚合策略）
     */
    public TTSFrameProcessor(
            TTSManager ttsManager,
            AudioConverter audioConverter,
            WebSocketMessageSender messageSender,
            ConversationConfigService configService,
            SessionState sessionState) {
        this(ttsManager, audioConverter, messageSender, configService, sessionState,
                TextAggregator.AggregationStrategy.HYBRID);
    }

    @Override
    public void processFrame(Frame frame, ProcessingContext context) throws FrameProcessingException {
        if (context.isInterrupted()) {
            return;
        }

        switch (frame.getType()) {
            case TEXT:
            case LLM_TEXT:
            case AGGREGATED_TEXT:
                processTextFrame(frame, context);
                break;

            case INTERRUPTION:
                processInterruptionFrame(frame);
                break;

            case END:
                processEndFrame(frame);
                break;

            default:
                // 其他类型的 Frame 直接透传
                break;
        }
    }

    /**
     * 处理文本 Frame
     */
    private void processTextFrame(Frame frame, ProcessingContext context) throws FrameProcessingException {
        String text;

        if (frame instanceof Frames.TextFrame) {
            text = ((Frames.TextFrame) frame).getText();
        } else if (frame instanceof Frames.LLMTextFrame) {
            text = ((Frames.LLMTextFrame) frame).getText();
        } else if (frame instanceof Frames.AggregatedTextFrame) {
            Frames.AggregatedTextFrame aggFrame = (Frames.AggregatedTextFrame) frame;
            text = aggFrame.getText();

            // 跳过 TTS
            if (aggFrame.isSkipTts()) {
                log.debug("跳过 TTS: {}", text);
                return;
            }
        } else {
            return;
        }

        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // 使用文本聚合器处理文本
        var results = textAggregator.append(text);

        for (var result : results) {
            if (context.isInterrupted()) {
                break;
            }

            // 将文本转换为音频
            convertToAudio(result.getText(), result.getType(), context);
        }
    }

    /**
     * 处理中断 Frame
     */
    private void processInterruptionFrame(Frame frame) {
        log.debug("收到中断 Frame，重置状态");

        // 重置文本聚合器
        textAggregator.reset();

        // 重置时机控制器
        timingController.reset();

        // 清空缓冲区
        sessionState.abort();
    }

    /**
     * 处理结束 Frame
     */
    private void processEndFrame(Frame frame) throws FrameProcessingException {
        // 处理剩余文本
        var remaining = textAggregator.complete();
        if (remaining != null) {
            convertToAudio(remaining.getText(), remaining.getType(), null);
        }
    }

    /**
     * 将文本转换为音频并发送（同步阻塞，确保时机控制正确）
     */
    private void convertToAudio(String text, TextAggregator.AggregationType type, ProcessingContext context)
            throws FrameProcessingException {

        if (context != null && context.isInterrupted()) {
            return;
        }

        try {
            ConversationConfig config = configService.getConfigBySessionId(sessionState.getSessionId());

            // 构建 TTS 选项
            TTSOptions ttsOptions = TTSOptions.builder()
                    .model(config.getTtsModel())
                    .voice(config.getTtsVoice())
                    .speed(config.getTtsSpeed())
                    .volume(config.getTtsVolume())
                    .format(config.getTtsFormat())
                    .build();

            log.debug("开始 TTS 转换: type={}, text={}", type, text);

            // 调用 TTS 获取音频流，使用同步阻塞方式收集所有 PCM 数据
            ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();

            List<TTSAudio> audioChunks = ttsManager.textToSpeechStream(
                    config.getTTSModelKey(),
                    text,
                    ttsOptions
            ).collectList().block();

            if (audioChunks == null || audioChunks.isEmpty()) {
                log.warn("TTS 返回空音频: text={}", text);
                return;
            }

            // 收集所有 PCM 数据
            for (TTSAudio audio : audioChunks) {
                if (context != null && context.isInterrupted()) {
                    log.debug("TTS 转换被中断");
                    return;
                }
                if (audio.getAudioData() != null) {
                    pcmBuffer.write(audio.getAudioData());
                }
            }

            byte[] pcmData = pcmBuffer.toByteArray();
            if (pcmData.length == 0) {
                return;
            }

            // 转换为 OPUS 并流式发送
            byte[] opusData = audioConverter.convertPcmToOpus(pcmData);
            sendAudioWithTiming(opusData);

            log.debug("TTS 转换完成: text={}, pcmSize={}, opusSize={}",
                    text, pcmData.length, opusData.length);

        } catch (Exception e) {
            log.error("TTS 转换异常: text={}", text, e);
            throw new FrameProcessingException("TTS 转换失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送 OPUS 音频数据，保持帧完整性
     * <p>
     * OPUS 数据格式：每帧前有2字节长度头（小端序）
     * 必须按完整帧发送，不能随意切割
     */
    private void sendAudioWithTiming(byte[] opusData) {
        if (opusData == null || opusData.length == 0) {
            return;
        }

        // 按 OPUS 帧发送，每帧格式：[2字节长度头][帧数据]
        int offset = 0;
        int frameCount = 0;

        while (offset + 2 <= opusData.length) {
            if (sessionState.isAborted()) {
                log.debug("会话已中止，停止发送音频");
                break;
            }

            // 读取帧长度（2字节小端序）
            int frameLen = (opusData[offset] & 0xFF) | ((opusData[offset + 1] & 0xFF) << 8);
            int totalFrameSize = 2 + frameLen; // 包含长度头

            if (offset + totalFrameSize > opusData.length) {
                log.warn("OPUS 帧数据不完整: offset={}, frameLen={}, dataLen={}",
                        offset, frameLen, opusData.length);
                break;
            }

            // 提取完整帧（包含长度头）
            byte[] frame = new byte[totalFrameSize];
            System.arraycopy(opusData, offset, frame, 0, totalFrameSize);

            try {
                // 发送 OPUS 帧到前端
                boolean isLastFrame = (offset + totalFrameSize >= opusData.length);
                messageSender.sendTTSAudio(sessionState, frame, isLastFrame);
                frameCount++;
            } catch (Exception e) {
                log.error("发送音频帧失败", e);
                break;
            }

            // 每帧应用时机控制（20ms/帧）
            timingController.waitForNextChunk();

            offset += totalFrameSize;
        }

        log.debug("OPUS 音频发送完成: 共 {} 帧, {} 字节", frameCount, opusData.length);
    }

    /**
     * 获取文本聚合器
     */
    public TextAggregator getTextAggregator() {
        return textAggregator;
    }

    /**
     * 获取时机控制器
     */
    public AudioTimingManager getTimingController() {
        return timingController;
    }
}
