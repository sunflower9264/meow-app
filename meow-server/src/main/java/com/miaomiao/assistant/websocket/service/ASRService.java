package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.model.asr.ASRManager;
import com.miaomiao.assistant.model.asr.ASROptions;
import com.miaomiao.assistant.model.asr.ASRResult;
import com.miaomiao.assistant.websocket.ConversationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Locale;

/**
 * ASR（语音识别）处理服务 负责将音频数据转换为文本
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ASRService {

    private static final String SUPPORTED_FORMAT = "wav";

    private final ASRManager asrManager;

    /**
     * 将音频数据流式转换为文本
     *
     * @param audioData 音频数据
     * @param audioFormat 客户端上报的音频格式
     * @param config 对话配置
     * @return ASR识别结果流
     */
    public Flux<ASRResult> speechToTextStream(byte[] audioData, String audioFormat, ConversationConfig config) {
        String normalizedFormat = normalizeAudioFormat(audioFormat);
        if (!SUPPORTED_FORMAT.equals(normalizedFormat)) {
            throw new IllegalArgumentException("ASR仅支持wav格式音频，当前格式: " + audioFormat);
        }

        ASROptions asrOptions = ASROptions.of(config.getAsrModel(), SUPPORTED_FORMAT);
        log.debug("ASR 流式识别开始: format={}, bytes={}", SUPPORTED_FORMAT, audioData.length);
        return asrManager.speechToTextStream(config.getASRModelKey(), Flux.just(audioData), asrOptions);
    }

    private String normalizeAudioFormat(String audioFormat) {
        if (audioFormat == null || audioFormat.isBlank()) {
            return SUPPORTED_FORMAT;
        }

        String normalized = audioFormat.trim().toLowerCase(Locale.ROOT);
        int semicolonIndex = normalized.indexOf(';');
        if (semicolonIndex >= 0) {
            normalized = normalized.substring(0, semicolonIndex);
        }
        normalized = normalized.replace("audio/", "");
        return switch (normalized) {
            case "x-wav" -> "wav";
            case "mpeg" -> "mp3";
            default -> normalized;
        };
    }
}
