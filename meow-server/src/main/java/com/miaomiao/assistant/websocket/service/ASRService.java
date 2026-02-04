package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.service.ConversationConfigService;
import com.miaomiao.assistant.service.asr.ASRManager;
import com.miaomiao.assistant.service.asr.BaseASRProvider;
import com.miaomiao.assistant.websocket.message.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * ASR（语音识别）处理服务
 * 负责将音频数据转换为文本
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ASRService {

    private final ASRManager asrManager;
    private final ConversationService conversationService;
    private final WebSocketMessageSender messageSender;
    private final ConversationConfigService configService;

    /**
     * 处理音频数据，执行 ASR -> LLM -> TTS 流程
     *
     * @param state     会话状态
     * @param audioData 音频数据
     */
    public void processAudio(SessionState state, byte[] audioData) {
        if (audioData.length == 0) {
            log.debug("音频数据为空，跳过处理");
            return;
        }

        // 异步处理ASR
        CompletableFuture.runAsync(() -> {
            try {
                ConversationConfig config = configService.getConfigBySessionId(state.getSessionId());
                BaseASRProvider.ASROptions asrOptions = BaseASRProvider.ASROptions.of(config.getAsrModel(), "opus");
                BaseASRProvider.ASRResult result = asrManager.speechToText(config.getAsrProvider(), audioData, asrOptions);

                // 发送STT结果到客户端
                messageSender.sendSTTResult(state, result.getText(), true);

                // 继续处理LLM和TTS
                conversationService.processConversation(state, result.getText());

            } catch (Exception e) {
                log.error("ASR处理失败", e);
                try {
                    messageSender.sendError(state, "语音识别失败: " + e.getMessage());
                } catch (Exception ex) {
                    log.error("发送错误消息失败", ex);
                }
            }
        });
    }

    /**
     * 仅执行ASR，不继续后续流程
     *
     * @param audioData 音频数据
     * @return ASR结果
     */
    public BaseASRProvider.ASRResult transcribe(byte[] audioData) {
        ConversationConfig config = configService.getDefaultConfig();
        BaseASRProvider.ASROptions asrOptions = BaseASRProvider.ASROptions.of(config.getAsrModel(), "opus");
        return asrManager.speechToText(config.getAsrProvider(), audioData, asrOptions);
    }
}
