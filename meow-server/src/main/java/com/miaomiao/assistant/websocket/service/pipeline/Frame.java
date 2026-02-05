package com.miaomiao.assistant.websocket.service.pipeline;

/**
 * Frame 基类
 * <p>
 * 移植自 Pipecat 的 Frame 概念，所有在管道中流转的数据都是 Frame。
 * Frame 可以是文本、音频、控制信号等。
 * <p>
 * 参考: pipecat-main/src/pipecat/frames/frames.py
 *
 * @author Pipecat移植
 */
public abstract class Frame {

    /**
     * Frame 类型
     */
    public enum FrameType {
        /**
         * 文本帧
         */
        TEXT,

        /**
         * LLM 文本帧
         */
        LLM_TEXT,

        /**
         * 聚合文本帧
         */
        AGGREGATED_TEXT,

        /**
         * 音频帧
         */
        AUDIO_RAW,

        /**
         * TTS 音频帧
         */
        TTS_AUDIO_RAW,

        /**
         * 开始帧
         */
        START,

        /**
         * 结束帧
         */
        END,

        /**
         * 中断帧
         */
        INTERRUPTION,

        /**
         * 消息帧
         */
        MESSAGE
    }

    private final FrameType type;
    private long timestamp;

    protected Frame(FrameType type) {
        this.type = type;
        this.timestamp = System.nanoTime();
    }

    /**
     * 获取 Frame 类型
     */
    public FrameType getType() {
        return type;
    }

    /**
     * 获取时间戳（纳秒）
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 设置时间戳
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 判断是否为文本类型 Frame
     */
    public boolean isText() {
        return type == FrameType.TEXT || type == FrameType.LLM_TEXT || type == FrameType.AGGREGATED_TEXT;
    }

    /**
     * 判断是否为音频类型 Frame
     */
    public boolean isAudio() {
        return type == FrameType.AUDIO_RAW || type == FrameType.TTS_AUDIO_RAW;
    }

    /**
     * 判断是否为控制类型 Frame
     */
    public boolean isControl() {
        return type == FrameType.START || type == FrameType.END || type == FrameType.INTERRUPTION;
    }

    @Override
    public String toString() {
        return "Frame{" +
                "type=" + type +
                ", timestamp=" + timestamp +
                '}';
    }
}
