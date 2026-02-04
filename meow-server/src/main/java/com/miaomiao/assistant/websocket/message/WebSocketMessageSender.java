package com.miaomiao.assistant.websocket.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaomiao.assistant.websocket.dto.SentenceMessage;
import com.miaomiao.assistant.websocket.dto.STTMessage;
import com.miaomiao.assistant.websocket.dto.TTSMessage;
import com.miaomiao.assistant.websocket.dto.WSMessage;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket消息发送器
 * 负责将各类消息序列化并发送到客户端
 */
@Slf4j
@Component
public class WebSocketMessageSender {

    private final ObjectMapper objectMapper;

    public WebSocketMessageSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 发送通用消息
     */
    public void sendMessage(WebSocketSession session, WSMessage message) throws IOException {
        if (session == null || !session.isOpen()) {
            log.warn("WebSocket会话已关闭，无法发送消息");
            return;
        }
        String json = objectMapper.writeValueAsString(message);
        session.sendMessage(new TextMessage(json));
    }

    /**
     * 发送通用消息（使用SessionState）
     */
    public void sendMessage(SessionState state, WSMessage message) throws IOException {
        sendMessage(state.getSession(), message);
    }

    /**
     * 发送STT结果消息
     */
    public void sendSTTResult(SessionState state, String text, boolean isFinal) throws IOException {
        STTMessage sttMessage = new STTMessage();
        sttMessage.setType("stt");
        sttMessage.setText(text);
        sttMessage.setFinal(isFinal);
        sttMessage.setTimestamp(System.currentTimeMillis());
        sendMessage(state, sttMessage);
    }

    /**
     * 发送句子分段消息
     */
    public void sendSentence(SessionState state, String text, int index) throws IOException {
        SentenceMessage message = new SentenceMessage();
        message.setType("sentence");
        message.setEventType("sentence_end");
        message.setText(text);
        message.setIndex(index);
        message.setTimestamp(System.currentTimeMillis());
        sendMessage(state, message);
    }

    /**
     * 发送TTS音频消息
     */
    public void sendTTSAudio(SessionState state, byte[] opusData, boolean finished) throws IOException {
        TTSMessage message = new TTSMessage();
        message.setType("tts");
        message.setFormat("opus");
        message.setData(Base64.getEncoder().encodeToString(opusData));
        message.setFinished(finished);
        message.setTimestamp(System.currentTimeMillis());
        sendMessage(state, message);
    }

    /**
     * 发送错误消息
     */
    public void sendError(WebSocketSession session, String error) throws IOException {
        if (session == null || !session.isOpen()) {
            log.warn("WebSocket会话已关闭，无法发送错误消息");
            return;
        }
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("type", "error");
        errorData.put("message", error);
        errorData.put("timestamp", System.currentTimeMillis());

        String json = objectMapper.writeValueAsString(errorData);
        session.sendMessage(new TextMessage(json));
    }

    /**
     * 发送错误消息（使用SessionState）
     */
    public void sendError(SessionState state, String error) throws IOException {
        sendError(state.getSession(), error);
    }
}
