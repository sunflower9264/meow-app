package com.miaomiao.assistant.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话配置
 * 包含 ASR、LLM、TTS 的模型配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationConfig {

    /**
     * ASR Provider名称
     */
    private String asrProvider;

    /**
     * ASR 模型
     */
    private String asrModel;

    /**
     * LLM Provider名称
     */
    private String llmProvider;

    /**
     * LLM 模型
     */
    private String llmModel;

    /**
     * TTS Provider名称
     */
    private String ttsProvider;

    /**
     * TTS 模型
     */
    private String ttsModel;

    /**
     * TTS 音色
     */
    private String ttsVoice;
    /**
     * TTS 音量
     */
    private Float ttsVolume;


    /**
     * TTS 语速
     */
    private Float ttsSpeed;

    /**
     * TTS 输出格式
     */
    @Builder.Default
    private String ttsFormat = "pcm";

    public String getASRModelKey(){
        return asrProvider + ":" + asrModel;
    }

    public String getLMModelKey(){
        return llmProvider + ":" + llmModel;
    }

    public String getTTSModelKey(){
        return ttsProvider + ":" + ttsModel;
    }
}
