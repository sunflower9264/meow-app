package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.codec.AudioConverter;
import com.miaomiao.assistant.service.ConversationConfigService;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.service.llm.BaseLLMProvider;
import com.miaomiao.assistant.service.llm.LLMManager;
import com.miaomiao.assistant.service.tts.BaseTTSProvider;
import com.miaomiao.assistant.service.tts.TTSManager;
import com.miaomiao.assistant.websocket.message.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话处理服务
 * 负责 LLM -> TTS -> Opus 编码的完整流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    // 首句标点符号（更激进，更低延迟）
    private static final String FIRST_SENTENCE_PUNCTUATIONS = "，,、。.？?！!；;：:~";
    // 后续句子标点符号
    private static final String SENTENCE_PUNCTUATIONS = "。.？?！!；;：:";

    private final LLMManager llmManager;
    private final TTSManager ttsManager;
    private final AudioConverter audioConverter;
    private final WebSocketMessageSender messageSender;
    private final ConversationConfigService configService;

    /**
     * 处理用户文本输入，执行 LLM -> TTS 流程
     *
     * @param state 会话状态
     * @param text  用户输入文本
     */
    public void processConversation(SessionState state, String text) {
        // 重置中止状态
        state.resetAborted();

        // 获取对话配置
        ConversationConfig config = configService.getConfigBySessionId(state.getSessionId());

        // 构建LLM选项
        BaseLLMProvider.LLMOptions llmOptions = BaseLLMProvider.LLMOptions.of(config.getLlmModel());

        // 构建对话历史
        List<BaseLLMProvider.AppChatMessage> messages = new ArrayList<>(state.getConversationHistory());
        messages.add(new BaseLLMProvider.AppChatMessage("user", text));

        // 设置文本分段sink用于TTS
        Sinks.Many<String> textSink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder fullResponse = new StringBuilder();
        AtomicInteger sentenceIndex = new AtomicInteger(0);
        StringBuilder currentBuffer = new StringBuilder();

        // 标记首句是否已发送（用于激进的首句切分）
        final boolean[] firstSentenceSent = {false};

        // 开始LLM流式响应
        Flux<BaseLLMProvider.AppLLMResponse> llmStream = llmManager.chatStream(config.getLlmProvider(), messages, llmOptions);

        // 订阅LLM流 - 检测句子边界时立即发送给TTS
        Disposable llmDisposable = llmStream
                .takeWhile(response -> !state.isAborted())  // 检查中止状态
                .subscribe(
                        llmResponse -> handleLLMResponse(
                                state, llmResponse, text, fullResponse, currentBuffer,
                                sentenceIndex, firstSentenceSent, textSink
                        ),
                        error -> {
                            log.error("LLM流错误", error);
                            textSink.tryEmitComplete();
                        },
                        () -> {
                            log.debug("LLM流完成");
                            // 如果是被中止的，确保 sink 完成
                            if (state.isAborted()) {
                                textSink.tryEmitComplete();
                            }
                        }
                );

        // 保存订阅以便取消
        state.setActiveDisposable(llmDisposable);

        // 处理TTS流 - 将文本转为Opus音频
        processTTSStream(state, textSink.asFlux(), config);
    }

    /**
     * 处理LLM响应
     */
    private void handleLLMResponse(SessionState state,
                                   BaseLLMProvider.AppLLMResponse appLlmResponse,
                                   String userText,
                                   StringBuilder fullResponse,
                                   StringBuilder currentBuffer,
                                   AtomicInteger sentenceIndex,
                                   boolean[] firstSentenceSent,
                                   Sinks.Many<String> textSink) {
        String content = appLlmResponse.text();
        if (content != null && !content.isEmpty()) {
            fullResponse.append(content);
            currentBuffer.append(content);

            // 检测句子边界
            String bufferText = currentBuffer.toString();
            String punctuations = firstSentenceSent[0] ? SENTENCE_PUNCTUATIONS : FIRST_SENTENCE_PUNCTUATIONS;

            int lastPunctPos = findLastPunctuation(bufferText, punctuations);
            if (lastPunctPos >= 0) {
                // 提取到标点前的完整句子
                String sentenceText = bufferText.substring(0, lastPunctPos + 1);
                String remaining = bufferText.substring(lastPunctPos + 1);

                // 发送句子标记
                int idx = sentenceIndex.getAndIncrement();
                try {
                    messageSender.sendSentence(state, sentenceText, idx);
                } catch (IOException e) {
                    log.error("发送句子消息失败", e);
                }

                // 立即发送给TTS流
                textSink.tryEmitNext(sentenceText);
                firstSentenceSent[0] = true;

                // 保留剩余文本在缓冲区
                currentBuffer.setLength(0);
                currentBuffer.append(remaining);
            }
        }

        // 流结束时处理剩余文本
        if (appLlmResponse.finished()) {
            if (!currentBuffer.isEmpty()) {
                int idx = sentenceIndex.getAndIncrement();
                try {
                    messageSender.sendSentence(state, currentBuffer.toString(), idx);
                } catch (IOException e) {
                    log.error("发送句子消息失败", e);
                }
                textSink.tryEmitNext(currentBuffer.toString());
            }
            textSink.tryEmitComplete();

            // 保存到对话历史
            state.addMessage("user", userText);
            state.addMessage("assistant", fullResponse.toString());
        }
    }

    /**
     * 处理TTS流 - 将文本转为Opus音频并发送
     */
    private void processTTSStream(SessionState state, Flux<String> textStream, ConversationConfig config) {
        // TTS选项
        BaseTTSProvider.TTSOptions ttsOptions = BaseTTSProvider.TTSOptions.of(
                config.getTtsModel(), config.getTtsVoice(), config.getTtsFormat()
        );

        // 调用TTS获取流式音频
        textStream
                .takeWhile(sentence -> !state.isAborted())  // 检查中止状态
                .flatMap(sentence -> ttsManager.textToSpeechStream(config.getTtsProvider(), sentence, ttsOptions))
                .takeWhile(audio -> !state.isAborted())  // TTS输出也检查中止状态
                .subscribe(
                        ttsAudio -> {
                            try {
                                // 将PCM转换为Opus裸帧（前端解码播放）
                                byte[] opusData = audioConverter.convertPcmToOpus(ttsAudio.getAudioData());

                                // 发送Opus音频到客户端
                                messageSender.sendTTSAudio(state, opusData, ttsAudio.isFinished());
                            } catch (Exception e) {
                                log.error("处理TTS音频失败", e);
                            }
                        },
                        error -> {
                            log.error("TTS流错误: {}", error.getMessage(), error);
                            try {
                                messageSender.sendError(state, "TTS服务错误: " + error.getMessage());
                            } catch (IOException e) {
                                log.error("发送TTS错误到客户端失败", e);
                            }
                        },
                        () -> log.debug("TTS流完成")
                );
    }

    /**
     * 查找文本中最后一个标点符号的位置
     */
    private int findLastPunctuation(String text, String punctuations) {
        int lastPos = -1;
        for (int i = 0; i < punctuations.length(); i++) {
            char punct = punctuations.charAt(i);
            int pos = text.lastIndexOf(punct);
            if (pos > lastPos) {
                lastPos = pos;
            }
        }
        return lastPos;
    }
}
