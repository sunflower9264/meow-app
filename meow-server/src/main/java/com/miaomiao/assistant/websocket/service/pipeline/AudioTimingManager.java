package com.miaomiao.assistant.websocket.service.pipeline;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 音频时机控制器
 * <p>
 * 移植自 Pipecat 的音频时机控制机制，确保音频数据以恒定的速率发送到前端，
 * 模拟真实音频设备的播放时钟，避免前端缓冲问题。
 * <p>
 * 参考: pipecat-main/src/pipecat/transports/websocket/server.py:406-415
 *
 * @author Pipecat移植
 * @see <a href="https://github.com/pipecat-ai/pipecat">Pipecat Framework</a>
 */
@Slf4j
public class AudioTimingManager {

    /**
     * 音频块大小（字节）
     */
    private final int audioChunkSize;

    /**
     * 采样率（Hz）
     */
    private final int sampleRate;

    /**
     * 每个音频块的播放时长（毫秒）
     */
    private final long chunkDurationMs;

    /**
     * 下一次发送的时间戳（纳秒）
     */
    private final AtomicLong nextSendTime;

    /**
     * 是否启用时机控制
     */
    private volatile boolean timingEnabled = true;

    /**
     * 构造函数
     *
     * @param audioChunkSize 音频块大小（字节）
     * @param sampleRate     采样率（Hz）
     */
    public AudioTimingManager(int audioChunkSize, int sampleRate) {
        this.audioChunkSize = audioChunkSize;
        this.sampleRate = sampleRate;
        // 计算每个音频块的播放时长：chunkSize / (sampleRate * 2 bytes/sample) / 2
        // 除以2是为了更激进的发送策略，减少延迟
        this.chunkDurationMs = (long) ((audioChunkSize / (double) (sampleRate * 2)) * 1000 / 2);
        this.nextSendTime = new AtomicLong(0);
        log.debug("音频时机控制器初始化: chunkSize={} bytes, sampleRate={} Hz, chunkDuration={} ms",
                audioChunkSize, sampleRate, chunkDurationMs);
    }

    /**
     * 固定帧时长构造函数（用于 OPUS 等已编码格式）
     *
     * @param frameDurationMs 每帧的播放时长（毫秒）
     */
    public AudioTimingManager(long frameDurationMs) {
        this.audioChunkSize = 0;
        this.sampleRate = 0;
        this.chunkDurationMs = frameDurationMs;
        this.nextSendTime = new AtomicLong(0);
        log.debug("音频时机控制器初始化（固定帧时长）: frameDuration={} ms", frameDurationMs);
    }

    /**
     * 等待到下一个音频块应该发送的时间
     * <p>
     * 这个方法模拟真实音频设备的播放时钟，确保音频数据以恒定的速率发送。
     * 如果发送太快，会导致前端缓冲区溢出；如果发送太慢，会导致音频卡顿。
     */
    public void waitForNextChunk() {
        if (!timingEnabled) {
            return;
        }

        long now = System.nanoTime() / 1_000_000; // 转换为毫秒
        long nextTime = nextSendTime.get();

        if (nextTime == 0) {
            // 第一次发送，立即执行
            nextSendTime.set(now + chunkDurationMs);
            return;
        }

        long delay = nextTime - now;
        if (delay > 0) {
            try {
                // 精确休眠到指定时间
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("音频时机等待被中断", e);
            }
        }

        // 更新下一次发送时间
        // 如果延迟为0（发送太慢），则从当前时间开始计算
        // 如果延迟大于0（正常情况），则在之前时间基础上累加
        long newNextTime = (delay <= 0) ? (now + chunkDurationMs) : (nextTime + chunkDurationMs);
        nextSendTime.set(newNextTime);

        log.trace("音频块发送: now={}, delay={}, next={}", now, delay, newNextTime);
    }

    /**
     * 重置时机控制器
     * <p>
     * 在中断或重新开始播放时调用
     */
    public void reset() {
        nextSendTime.set(0);
        log.debug("音频时机控制器已重置");
    }

    /**
     * 设置是否启用时机控制
     * <p>
     * 在某些场景下（如测试），可能需要禁用时机控制
     *
     * @param enabled true 启用，false 禁用
     */
    public void setTimingEnabled(boolean enabled) {
        this.timingEnabled = enabled;
        log.debug("音频时机控制: {}", enabled ? "启用" : "禁用");
    }

    /**
     * 获取音频块大小
     */
    public int getAudioChunkSize() {
        return audioChunkSize;
    }

    /**
     * 获取采样率
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * 获取音频块播放时长
     */
    public long getChunkDurationMs() {
        return chunkDurationMs;
    }
}
