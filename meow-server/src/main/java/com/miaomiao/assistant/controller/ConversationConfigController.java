package com.miaomiao.assistant.controller;

import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.service.ConversationConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 对话配置 Controller
 * 提供对话配置的 RESTful 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/conversation/config")
@RequiredArgsConstructor
public class ConversationConfigController {

    private final ConversationConfigService configService;

    /**
     * 获取默认对话配置
     */
    @GetMapping("/default")
    public ResponseEntity<ConversationConfig> getDefaultConfig() {
        ConversationConfig config = configService.getDefaultConfig();
        return ResponseEntity.ok(config);
    }

    /**
     * 根据用户ID获取对话配置
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<ConversationConfig> getConfigByUserId(@PathVariable String userId) {
        ConversationConfig config = configService.getConfigByUserId(userId);
        return ResponseEntity.ok(config);
    }

    /**
     * 根据会话ID获取对话配置
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<ConversationConfig> getConfigBySessionId(@PathVariable String sessionId) {
        ConversationConfig config = configService.getConfigBySessionId(sessionId);
        return ResponseEntity.ok(config);
    }
}
