package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.model.asr.ASRManager;
import com.miaomiao.assistant.model.asr.ASROptions;
import com.miaomiao.assistant.model.asr.ASRResult;
import com.miaomiao.assistant.websocket.ConversationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * ASR（语音识别）处理服务 负责将音频数据转换为文本
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ASRService {

    private final ASRManager asrManager;

    /**
     * 将音频数据转换为文本
     *
     * @param audioData 音频数据
     * @param audioFormat 客户端上报的音频格式
     * @param config 对话配置
     * @return ASR识别结果
     */
    public ASRResult speechToText(byte[] audioData, String audioFormat, ConversationConfig config) {
        String format = normalizeAudioFormat(audioFormat);
        ASROptions asrOptions = ASROptions.of(config.getAsrModel(), format);
        log.debug("ASR 开始识别: format={}, bytes={}", format, audioData.length);
        return asrManager.speechToText(config.getASRModelKey(), audioData, asrOptions);
    }

    private String normalizeAudioFormat(String audioFormat) {
        if (audioFormat == null || audioFormat.isBlank()) {
            return "opus";
        }

        String normalized = audioFormat.trim().toLowerCase(Locale.ROOT);
        int semicolonIndex = normalized.indexOf(';');
        if (semicolonIndex >= 0) {
            normalized = normalized.substring(0, semicolonIndex);
        }
        if (normalized.startsWith("audio/")) {
            normalized = normalized.substring("audio/".length());
        }

        return switch (normalized) {
            case "x-wav" -> "wav";
            case "mpeg" -> "mp3";
            default -> normalized;
        };
    }
}
