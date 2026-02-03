package com.miaomiao.assistant.session.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaomiao.assistant.session.provider.asr.ASRProvider;
import com.miaomiao.assistant.session.provider.asr.ASRProviderFactory;
import com.miaomiao.assistant.session.provider.llm.LLMProvider;
import com.miaomiao.assistant.session.provider.llm.LLMProvider.ChatMessage;
import com.miaomiao.assistant.session.provider.llm.LLMProvider.LLMResponse;
import com.miaomiao.assistant.session.provider.llm.LLMProviderFactory;
import com.miaomiao.assistant.session.provider.tts.TTSProvider;
import com.miaomiao.assistant.session.provider.tts.TTSProvider.TTSAudio;
import com.miaomiao.assistant.session.provider.tts.TTSProvider.TextSegment;
import com.miaomiao.assistant.session.provider.tts.TTSProviderFactory;
import com.miaomiao.assistant.session.websocket.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket conversation handler
 * Implements ASR -> LLM -> TTS pipeline with dual streaming
 */
@Slf4j
@Component
public class ConversationWebSocketHandler extends TextWebSocketHandler {

    private final ASRProviderFactory asrFactory;
    private final LLMProviderFactory llmFactory;
    private final TTSProviderFactory ttsFactory;
    private final ObjectMapper objectMapper;

    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    public ConversationWebSocketHandler(ASRProviderFactory asrFactory,
                                        LLMProviderFactory llmFactory,
                                        TTSProviderFactory ttsFactory) {
        this.asrFactory = asrFactory;
        this.llmFactory = llmFactory;
        this.ttsFactory = ttsFactory;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connection established: {}", session.getId());
        SessionState state = new SessionState(session);
        sessionStates.put(session.getId(), state);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            String payload = message.getPayload();
            WSMessage wsMessage = objectMapper.readValue(payload, WSMessage.class);

            SessionState state = sessionStates.get(session.getId());
            if (state == null) {
                log.warn("Session state not found: {}", session.getId());
                return;
            }

            handleIncomingMessage(state, wsMessage);

        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
            sendError(session, e.getMessage());
        }
    }

    private void handleIncomingMessage(SessionState state, WSMessage message) throws IOException {
        String type = message.getType();
        if (type == null) {
            log.warn("Message type is null, cannot process message");
            return;
        }
        
        switch (type) {
            case "audio":
                handleAudioMessage(state, (AudioMessage) message);
                break;
            case "text":
                handleTextInputMessage(state, (com.miaomiao.assistant.session.websocket.dto.TextMessage) message);
                break;
            case "control":
                handleControlMessage(state, (ControlMessage) message);
                break;
            default:
                log.warn("Unknown message type: {}", type);
        }
    }

    private void handleAudioMessage(SessionState state, AudioMessage message) throws IOException {
        // Accumulate audio data
        state.accumulateAudio(message.getData());

        if (message.isLast()) {
            // Process the accumulated audio with ASR
            processASR(state);
        }
    }

    private void handleTextInputMessage(SessionState state, com.miaomiao.assistant.session.websocket.dto.TextMessage message) throws IOException {
        // Direct text input, skip ASR
        processLLMAndTTS(state, message.getText());
    }

    private void handleControlMessage(SessionState state, ControlMessage message) throws IOException {
        switch (message.getAction()) {
            case "abort":
                // Abort current processing
                state.abort();
                break;
            case "config":
                // Update configuration
                state.updateConfig(message.getConfig());
                break;
            default:
                log.warn("Unknown control action: {}", message.getAction());
        }
    }

    private void processASR(SessionState state) throws IOException {
        byte[] audioData = state.getAndClearAudioBuffer();
        if (audioData == null || audioData.length == 0) {
            return;
        }

        // Run ASR in background thread
        CompletableFuture.runAsync(() -> {
            try {
                ASRProvider asr = asrFactory.getDefaultProvider();
                String text = asr.speechToText(audioData, "opus");

                // Send STT result to client
                STTMessage sttMessage = new STTMessage();
                sttMessage.setType("stt");
                sttMessage.setText(text);
                sttMessage.setFinal(true);
                sttMessage.setTimestamp(System.currentTimeMillis());
                sendMessage(state.getSession(), sttMessage);

                // Continue to LLM and TTS
                processLLMAndTTS(state, text);

            } catch (Exception e) {
                log.error("ASR processing failed", e);
            }
        });
    }

    // Punctuations for first sentence (more aggressive, lower latency)
    private static final String FIRST_SENTENCE_PUNCTUATIONS = "，,、。.？?！!；;：:~";
    // Punctuations for subsequent sentences
    private static final String SENTENCE_PUNCTUATIONS = "。.？?！!；;：:";

    private void processLLMAndTTS(SessionState state, String text) throws IOException {
        LLMProvider llm = llmFactory.getDefaultProvider();
        TTSProvider tts = ttsFactory.getDefaultProvider();

        // Build conversation history
        List<ChatMessage> messages = new ArrayList<>(state.getConversationHistory());
        messages.add(new ChatMessage("user", text));

        // Set up text segment sink for TTS - using multicast for real streaming
        Sinks.Many<String> textSink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder fullResponse = new StringBuilder();
        AtomicInteger sentenceIndex = new AtomicInteger(0);
        StringBuilder currentBuffer = new StringBuilder();
        
        // Track if first sentence has been sent (for aggressive first-sentence splitting)
        final boolean[] firstSentenceSent = {false};

        // Start LLM streaming with configured temperature and maxTokens
        Flux<LLMResponse> llmStream = llm.responseStream(messages, 
                llm.getDefaultTemperature(), llm.getDefaultMaxTokens());

        // Subscribe to LLM stream - emit to TTS immediately when sentence boundary detected
        llmStream.subscribe(
                llmResponse -> {
                    String content = llmResponse.getText();
                    if (content != null && !content.isEmpty()) {
                        fullResponse.append(content);
                        currentBuffer.append(content);

                        // Check for sentence boundary in the buffer
                        String bufferText = currentBuffer.toString();
                        String punctuations = firstSentenceSent[0] ? SENTENCE_PUNCTUATIONS : FIRST_SENTENCE_PUNCTUATIONS;
                        
                        int lastPunctPos = findLastPunctuation(bufferText, punctuations);
                        if (lastPunctPos >= 0) {
                            // Extract complete sentence(s) up to the punctuation
                            String sentenceText = bufferText.substring(0, lastPunctPos + 1);
                            String remaining = bufferText.substring(lastPunctPos + 1);
                            
                            // Send sentence marker
                            int idx = sentenceIndex.getAndIncrement();
                            try {
                                sendSentenceMessage(state, "sentence_end", sentenceText, idx);
                            } catch (IOException e) {
                                log.error("Failed to send sentence message", e);
                            }

                            // Immediately emit to TTS stream
                            textSink.tryEmitNext(sentenceText);
                            firstSentenceSent[0] = true;

                            // Keep remaining text in buffer
                            currentBuffer.setLength(0);
                            currentBuffer.append(remaining);
                        }
                    }
                    
                    // If stream finished, process remaining text
                    if (llmResponse.isFinished()) {
                        if (currentBuffer.length() > 0) {
                            int idx = sentenceIndex.getAndIncrement();
                            try {
                                sendSentenceMessage(state, "sentence_end", currentBuffer.toString(), idx);
                            } catch (IOException e) {
                                log.error("Failed to send sentence message", e);
                            }
                            textSink.tryEmitNext(currentBuffer.toString());
                        }
                        textSink.tryEmitComplete();

                        // Save to conversation history
                        state.addMessage("user", text);
                        state.addMessage("assistant", fullResponse.toString());
                    }
                },
                error -> {
                    log.error("LLM stream error", error);
                    textSink.tryEmitComplete();
                },
                () -> log.debug("LLM stream completed")
        );

        // Process TTS stream - concatMap ensures sequential processing
        Flux<TextSegment> textSegmentStream = textSink.asFlux()
                .map(txt -> new TextSegment(txt, true, false));

        Flux<TTSAudio> ttsStream = tts.textStreamToSpeechStream(textSegmentStream);

        // Send TTS audio to client immediately as chunks arrive
        ttsStream.subscribe(
                ttsAudio -> {
                    try {
                        sendTTSMessage(state, ttsAudio);
                    } catch (IOException e) {
                        log.error("Failed to send TTS message", e);
                    }
                },
                error -> {
                    log.error("TTS stream error: {}", error.getMessage(), error);
                    try {
                        sendError(state.getSession(), "TTS服务错误: " + error.getMessage());
                    } catch (IOException e) {
                        log.error("Failed to send TTS error to client", e);
                    }
                },
                () -> log.debug("TTS stream completed")
        );
    }

    /**
     * Find the last punctuation position in text
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

    private boolean isSentenceEnd(String text) {
        return text.endsWith(".") || text.endsWith("。") ||
               text.endsWith("!") || text.endsWith("！") ||
               text.endsWith("?") || text.endsWith("？") ||
               text.endsWith("\n");
    }

    private void sendSentenceMessage(SessionState state, String eventType, String text, int index) throws IOException {
        SentenceMessage message = new SentenceMessage();
        message.setType("sentence");
        message.setEventType(eventType);
        message.setText(text);
        message.setIndex(index);
        message.setTimestamp(System.currentTimeMillis());
        sendMessage(state.getSession(), message);
    }

    private void sendTTSMessage(SessionState state, TTSAudio ttsAudio) throws IOException {
        TTSMessage message = new TTSMessage();
        message.setType("tts");
        message.setFormat(ttsAudio.getFormat());
        message.setData(Base64.getEncoder().encodeToString(ttsAudio.getAudioData()));
        message.setFinished(ttsAudio.isFinished());
        message.setTimestamp(System.currentTimeMillis());
        sendMessage(state.getSession(), message);
    }

    private void sendMessage(WebSocketSession session, WSMessage message) throws IOException {
        String json = objectMapper.writeValueAsString(message);
        session.sendMessage(new TextMessage(json));
    }

    private void sendError(WebSocketSession session, String error) throws IOException {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("type", "error");
        errorData.put("message", error);
        errorData.put("timestamp", System.currentTimeMillis());

        String json = objectMapper.writeValueAsString(errorData);
        session.sendMessage(new TextMessage(json));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {}", session.getId());
        SessionState state = sessionStates.remove(session.getId());
        if (state != null) {
            state.cleanup();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error", exception);
        SessionState state = sessionStates.remove(session.getId());
        if (state != null) {
            state.cleanup();
        }
    }

    /**
     * Session state management
     */
    private static class SessionState {
        private final WebSocketSession session;
        private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        private final List<ChatMessage> conversationHistory = new CopyOnWriteArrayList<>();
        private volatile boolean aborted = false;

        public SessionState(WebSocketSession session) {
            this.session = session;
        }

        public synchronized void accumulateAudio(byte[] data) {
            try {
                audioBuffer.write(data);
            } catch (IOException e) {
                throw new RuntimeException("Failed to accumulate audio", e);
            }
        }

        public synchronized byte[] getAndClearAudioBuffer() {
            byte[] data = audioBuffer.toByteArray();
            audioBuffer.reset();
            return data;
        }

        public List<ChatMessage> getConversationHistory() {
            return new ArrayList<>(conversationHistory);
        }

        public void addMessage(String role, String content) {
            conversationHistory.add(new ChatMessage(role, content));
            // Keep history within reasonable size
            if (conversationHistory.size() > 50) {
                conversationHistory.subList(0, 10).clear();
            }
        }

        public void abort() {
            this.aborted = true;
        }

        public boolean isAborted() {
            return aborted;
        }

        public void updateConfig(String config) {
            // Parse and update configuration
            // TODO: Implement configuration update
        }

        public void cleanup() {
            aborted = true;
            try {
                audioBuffer.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        public WebSocketSession getSession() {
            return session;
        }
    }
}
