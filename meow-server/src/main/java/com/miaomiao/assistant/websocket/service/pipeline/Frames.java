package com.miaomiao.assistant.websocket.service.pipeline;

/**
 * 具体 Frame 类型定义
 * <p>
 * 移植自 Pipecat 的各种 Frame 类型
 *
 * @author Pipecat移植
 */
public class Frames {

    /**
     * 文本帧
     */
    public static class TextFrame extends Frame {
        private final String text;
        private final boolean includesInterFrameSpaces;

        public TextFrame(String text) {
            this(text, false);
        }

        public TextFrame(String text, boolean includesInterFrameSpaces) {
            super(FrameType.TEXT);
            this.text = text;
            this.includesInterFrameSpaces = includesInterFrameSpaces;
        }

        public String getText() {
            return text;
        }

        public boolean isIncludesInterFrameSpaces() {
            return includesInterFrameSpaces;
        }

        @Override
        public String toString() {
            return "TextFrame{" +
                    "text='" + text + '\'' +
                    ", includesInterFrameSpaces=" + includesInterFrameSpaces +
                    '}';
        }
    }

    /**
     * LLM 文本帧
     */
    public static class LLMTextFrame extends Frame {
        private final String text;
        private boolean skipTts = false;

        public LLMTextFrame(String text) {
            super(FrameType.LLM_TEXT);
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public boolean isSkipTts() {
            return skipTts;
        }

        public void setSkipTts(boolean skipTts) {
            this.skipTts = skipTts;
        }
    }

    /**
     * 聚合文本帧
     */
    public static class AggregatedTextFrame extends Frame {
        private final String text;
        private final TextAggregator.AggregationType aggregatedBy;
        private boolean appendToContext = true;
        private boolean skipTts = false;

        public AggregatedTextFrame(String text, TextAggregator.AggregationType aggregatedBy) {
            super(FrameType.AGGREGATED_TEXT);
            this.text = text;
            this.aggregatedBy = aggregatedBy;
        }

        public String getText() {
            return text;
        }

        public TextAggregator.AggregationType getAggregatedBy() {
            return aggregatedBy;
        }

        public boolean isAppendToContext() {
            return appendToContext;
        }

        public void setAppendToContext(boolean appendToContext) {
            this.appendToContext = appendToContext;
        }

        public boolean isSkipTts() {
            return skipTts;
        }

        public void setSkipTts(boolean skipTts) {
            this.skipTts = skipTts;
        }
    }

    /**
     * 音频帧（原始 PCM 数据）
     */
    public static class AudioRawFrame extends Frame {
        private final byte[] audio;
        private final int sampleRate;
        private final int numChannels;

        public AudioRawFrame(byte[] audio, int sampleRate, int numChannels) {
            super(FrameType.AUDIO_RAW);
            this.audio = audio;
            this.sampleRate = sampleRate;
            this.numChannels = numChannels;
        }

        public byte[] getAudio() {
            return audio;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public int getNumChannels() {
            return numChannels;
        }

        public int getSize() {
            return audio.length;
        }

        @Override
        public String toString() {
            return "AudioRawFrame{" +
                    "size=" + audio.length +
                    ", sampleRate=" + sampleRate +
                    ", numChannels=" + numChannels +
                    '}';
        }
    }

    /**
     * TTS 音频帧
     */
    public static class TTSAudioRawFrame extends AudioRawFrame {
        private String transportDestination;

        public TTSAudioRawFrame(byte[] audio, int sampleRate, int numChannels) {
            super(audio, sampleRate, numChannels);
            // 覆盖类型为 TTS_AUDIO_RAW
            // 注意：这里需要特殊处理，因为 Java 的 final 字段限制
        }

        public String getTransportDestination() {
            return transportDestination;
        }

        public void setTransportDestination(String transportDestination) {
            this.transportDestination = transportDestination;
        }
    }

    /**
     * 开始帧
     */
    public static class StartFrame extends Frame {
        private final int audioOutSampleRate;

        public StartFrame(int audioOutSampleRate) {
            super(FrameType.START);
            this.audioOutSampleRate = audioOutSampleRate;
        }

        public int getAudioOutSampleRate() {
            return audioOutSampleRate;
        }
    }

    /**
     * 结束帧
     */
    public static class EndFrame extends Frame {
        public EndFrame() {
            super(FrameType.END);
        }
    }

    /**
     * 中断帧
     */
    public static class InterruptionFrame extends Frame {
        public InterruptionFrame() {
            super(FrameType.INTERRUPTION);
        }
    }

    /**
     * 消息帧（通用消息传递）
     */
    public static class MessageFrame extends Frame {
        private final String messageType;
        private final Object payload;

        public MessageFrame(String messageType, Object payload) {
            super(FrameType.MESSAGE);
            this.messageType = messageType;
            this.payload = payload;
        }

        public String getMessageType() {
            return messageType;
        }

        public Object getPayload() {
            return payload;
        }

        @Override
        public String toString() {
            return "MessageFrame{" +
                    "messageType='" + messageType + '\'' +
                    ", payload=" + payload +
                    '}';
        }
    }

    /**
     * LLM 完整响应开始帧
     */
    public static class LLMFullResponseStartFrame extends Frame {
        public LLMFullResponseStartFrame() {
            super(FrameType.START); // 复用 START 类型
        }
    }

    /**
     * LLM 完整响应结束帧
     */
    public static class LLMFullResponseEndFrame extends Frame {
        private boolean skipTts = false;

        public LLMFullResponseEndFrame() {
            super(FrameType.END); // 复用 END 类型
        }

        public boolean isSkipTts() {
            return skipTts;
        }

        public void setSkipTts(boolean skipTts) {
            this.skipTts = skipTts;
        }
    }
}
