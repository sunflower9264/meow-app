package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.codec.AudioConverter;
import com.miaomiao.assistant.model.tts.TTSManager;
import com.miaomiao.assistant.model.tts.TTSOptions;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.websocket.message.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * TTS（语音合成）处理服务 负责将文本转换为Opus音频并发送
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TTSService {

    private final TTSManager ttsManager;
    private final AudioConverter audioConverter;
    private final WebSocketMessageSender messageSender;

    /**
     * 处理TTS流 - 将文本流转为Opus音频并发送
     *
     * @param state 会话状态
     * @param textStream 句子文本流
     * @param config 对话配置
     */
    public void processTTSStream(SessionState state, Flux<String> textStream, ConversationConfig config) {
        // TTS选项
        TTSOptions ttsOptions = TTSOptions.builder()
                        .model(config.getTtsModel())
                        .voice(config.getTtsVoice())
                        .speed(config.getTtsSpeed())
                        .volume(config.getTtsVolume())
                        .format(config.getTtsFormat())
                        .build();
        // 调用TTS获取流式音频
        textStream
                .takeWhile(sentence -> !state.isAborted()) // 检查中止状态
                .concatMap(sentence -> ttsManager.textToSpeechStream(config.getTTSModelKey(), sentence, ttsOptions))
                .takeWhile(audio -> !state.isAborted()) // TTS输出也检查中止状态
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
}
