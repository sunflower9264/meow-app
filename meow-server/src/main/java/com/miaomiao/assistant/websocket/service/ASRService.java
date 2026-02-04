package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.model.asr.ASRManager;
import com.miaomiao.assistant.model.asr.ASROptions;
import com.miaomiao.assistant.model.asr.ASRResult;
import com.miaomiao.assistant.websocket.ConversationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
     * @param audioData 音频数据（Opus格式）
     * @param config 对话配置
     * @return ASR识别结果
     */
    public ASRResult speechToText(byte[] audioData, ConversationConfig config) {
        ASROptions asrOptions = ASROptions.of(config.getAsrModel(), "opus");
        return asrManager.speechToText(config.getASRModelKey(), audioData, asrOptions);
    }
}
