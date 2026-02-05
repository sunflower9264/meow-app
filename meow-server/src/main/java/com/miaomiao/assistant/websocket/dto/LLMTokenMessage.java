package com.miaomiao.assistant.websocket.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * LLM 流式 Token 消息（用于前端打字效果）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LLMTokenMessage extends WSMessage {
    /**
     * 当前 token 内容
     */
    private String token;

    /**
     * 累积文本（从开始到当前的完整文本）
     */
    private String accumulated;

    /**
     * 是否完成
     */
    private boolean finished;
}
