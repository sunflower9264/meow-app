package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.model.asr.ASRResult;
import com.miaomiao.assistant.service.ConversationConfigService;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.websocket.session.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;

/**
 * 对话处理服务（入口） 负责编排 ASR -> LLM -> TTS 的完整流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ASRService asrService;
    private final LLMService llmService;
    private final TTSService ttsService;
    private final WebSocketMessageSender messageSender;
    private final ConversationConfigService configService;

    /**
     * 处理音频输入，执行完整的 ASR -> LLM -> TTS 流程
     *
     * @param state 会话状态
     * @param audioData 音频数据
     * @param audioFormat 音频格式（来自客户端）
     */
    public void processAudioInput(SessionState state, byte[] audioData, String audioFormat) {
        if (audioData.length == 0) {
            log.debug("音频数据为空，跳过处理");
            return;
        }
        if (!state.getSession().isOpen()) {
            log.debug("会话 {} 已断开，跳过音频处理", state.getSessionId());
            return;
        }

        // 异步处理完整流程
        CompletableFuture.runAsync(() -> {
            try {
                if (!state.getSession().isOpen()) {
                    log.debug("会话 {} 已断开，取消 ASR->LLM->TTS 流程", state.getSessionId());
                    return;
                }
                ConversationConfig config = configService.getConfigBySessionId(state.getSessionId());

                // 1. ASR: 语音转文本
                ASRResult asrResult = asrService.speechToText(audioData, audioFormat, config);
                if (!state.getSession().isOpen()) {
                    log.debug("会话 {} 在 ASR 后已断开，终止后续流程", state.getSessionId());
                    return;
                }

                // 发送STT结果到客户端
                messageSender.sendSTTResult(state, asrResult.getText(), true);

                // 2. LLM + TTS: 对话生成和语音合成
                processTextInput(state, asrResult.getText(), config);

            } catch (Exception e) {
                log.error("对话处理失败", e);
                if (!state.getSession().isOpen()) {
                    return;
                }
                try {
                    messageSender.sendError(state, "处理失败: " + e.getMessage());
                } catch (Exception ex) {
                    log.error("发送错误消息失败", ex);
                }
            }
        });
    }

    /**
     * 处理文本输入，执行 LLM -> TTS 流程
     *
     * @param state 会话状态
     * @param text 用户输入文本
     */
    public void processTextInput(SessionState state, String text) {
        ConversationConfig config = configService.getConfigBySessionId(state.getSessionId());
        processTextInput(state, text, config);
    }

    /**
     * 处理文本输入，执行 LLM -> TTS 流程
     *
     * @param state 会话状态
     * @param text 用户输入文本
     * @param config 对话配置
     */
    private void processTextInput(SessionState state, String text, ConversationConfig config) {
        if (!state.getSession().isOpen()) {
            log.debug("会话 {} 已断开，忽略文本输入处理", state.getSessionId());
            return;
        }
        if (text == null || text.isBlank()) {
            log.debug("文本输入为空，跳过处理");
            return;
        }

        // 重置中止状态
        state.resetAborted();

        // 记录用户输入开始时间（性能指标）
        state.getPerformanceMetrics().recordUserInputStart();

        // 2. LLM: 流式对话，返回句子流
        Flux<String> sentenceStream = llmService.processLLMStream(state, text, config);

        // 3. TTS: 将句子流转为音频并发送
        ttsService.processTTSStream(state, sentenceStream);
    }
}
