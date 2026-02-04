package com.miaomiao.assistant.service;

import com.miaomiao.assistant.websocket.ConversationConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话配置服务
 * 负责获取对话所需的模型配置
 * TODO: 后续集成数据库后，从数据库读取配置
 */
@Slf4j
@Service
public class ConversationConfigService {

    /**
     * 获取默认对话配置
     * TODO: 后续从数据库读取
     *
     * @return 对话配置
     */
    public ConversationConfig getDefaultConfig() {
        return ConversationConfig.builder()
                .asrProvider("zhipu")
                .asrModel("chirp-beta")
                .llmProvider("zhipu")
                .llmModel("glm-4-flash")
                .ttsProvider("zhipu")
                .ttsModel("glm-tts")
                .ttsVoice("female")
                .ttsFormat("pcm")
                .build();
    }

    /**
     * 根据用户ID获取对话配置
     * TODO: 后续从数据库读取用户个性化配置
     *
     * @param userId 用户ID
     * @return 对话配置
     */
    public ConversationConfig getConfigByUserId(String userId) {
        // 暂时返回默认配置
        log.debug("获取用户[{}]的对话配置，暂时使用默认配置", userId);
        return getDefaultConfig();
    }

    /**
     * 根据会话ID获取对话配置
     * TODO: 后续从数据库读取会话配置
     *
     * @param sessionId 会话ID
     * @return 对话配置
     */
    public ConversationConfig getConfigBySessionId(String sessionId) {
        // 暂时返回默认配置
        log.debug("获取会话[{}]的对话配置，暂时使用默认配置", sessionId);
        return getDefaultConfig();
    }
}
