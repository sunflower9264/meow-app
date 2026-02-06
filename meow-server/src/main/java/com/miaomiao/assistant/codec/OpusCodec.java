package com.miaomiao.assistant.codec;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/**
 * Opus音频编码器
 * 使用opus-jni实现，支持输出 Ogg Opus 格式
 * 参考: https://github.com/LabyMod/opus-jni
 */
@Slf4j
@Component
public class OpusCodec {

    // Opus编码参数
    private static final int SAMPLE_RATE = 24000;     // 智谱TTS返回的采样率
    private static final int CHANNELS = 1;            // 单声道
    private static final int BITRATE = 64000;         // 64kbps
    // frameSize = 采样率 * 帧时长(秒)
    // 对于24kHz，20ms帧: 24000 * 0.02 = 480
    private static final int FRAME_SIZE = 480;

    /**
     * 创建独立的 Opus 编解码器实例。
     * <p>
     * 不能在并发任务中复用同一个 native codec 实例，否则会出现状态互相污染，
     * 导致输出音频杂音、节奏异常等问题。
     */
    private net.labymod.opus.OpusCodec createCodec() {
        return net.labymod.opus.OpusCodec.newBuilder()
                .withSampleRate(SAMPLE_RATE)
                .withChannels(CHANNELS)
                .withBitrate(BITRATE)
                .withFrameSize(FRAME_SIZE)
                .build();
    }

    /**
     * 将PCM数据编码为Opus
     * 会自动进行分帧处理，输入数据长度可以是任意的
     *
     * @param pcmData 16位PCM数据(小端序)
     * @return Opus编码后的数据（多个帧拼接，每帧前有2字节长度头）
     */
    public byte[] encodePcmToOpus(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) {
            return new byte[0];
        }

        net.labymod.opus.OpusCodec codec = createCodec();
        try {
            int frameSizeBytes = FRAME_SIZE * CHANNELS * 2;
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            // 分帧编码
            int offset = 0;
            while (offset + frameSizeBytes <= pcmData.length) {
                // 提取一帧数据
                byte[] frameData = new byte[frameSizeBytes];
                System.arraycopy(pcmData, offset, frameData, 0, frameSizeBytes);

                // 编码这一帧
                byte[] encoded = codec.encodeFrame(frameData);

                // 写入帧长度（2字节，小端序）和帧数据
                outputStream.write(encoded.length & 0xFF);
                outputStream.write((encoded.length >> 8) & 0xFF);
                outputStream.write(encoded);

                offset += frameSizeBytes;
            }

            // 处理剩余不足一帧的数据：补0凑成完整帧
            int remaining = pcmData.length - offset;
            if (remaining > 0) {
                byte[] frameData = new byte[frameSizeBytes];
                System.arraycopy(pcmData, offset, frameData, 0, remaining);
                // 剩余部分自动为0（Java数组初始化）

                byte[] encoded = codec.encodeFrame(frameData);
                outputStream.write(encoded.length & 0xFF);
                outputStream.write((encoded.length >> 8) & 0xFF);
                outputStream.write(encoded);
            }
            return outputStream.toByteArray();
        } catch (Exception e) {
            log.error("PCM转Opus编码失败", e);
            throw new RuntimeException("音频编码失败", e);
        } finally {
            safeDestroy(codec);
        }
    }

    /**
     * 将 Opus 数据解码为 PCM
     * 输入数据格式：多个帧拼接，每帧前有2字节长度头（小端序）+ 帧数据
     *
     * @param opusData Opus编码后的数据（你 encodePcmToOpus 的输出）
     * @return 16位PCM数据(小端序)
     */
    public byte[] decodeOpusToPcm(byte[] opusData) {
        if (opusData == null || opusData.length < 2) {
            return new byte[0];
        }

        net.labymod.opus.OpusCodec codec = createCodec();
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            int offset = 0;

            while (offset + 2 <= opusData.length) {
                // 读取2字节帧长度（小端序）
                int len = (opusData[offset] & 0xFF) | ((opusData[offset + 1] & 0xFF) << 8);
                offset += 2;

                if (len == 0) {
                    // 长度异常：直接跳出或继续（这里选择跳出，避免死循环）
                    break;
                }
                if (offset + len > opusData.length) {
                    // 数据不完整：直接跳出
                    break;
                }

                // 取出该帧Opus数据
                byte[] frame = new byte[len];
                System.arraycopy(opusData, offset, frame, 0, len);
                offset += len;

                // 解码该帧 -> PCM
                // 返回PCM一般是一帧对应的PCM字节：FRAME_SIZE * CHANNELS * 2
                byte[] pcmFrame = codec.decodeFrame(frame);

                outputStream.write(pcmFrame);
            }

            return outputStream.toByteArray();
        } catch (Exception e) {
            log.error("Opus转PCM解码失败", e);
            throw new RuntimeException("音频解码失败", e);
        } finally {
            safeDestroy(codec);
        }
    }

    private void safeDestroy(net.labymod.opus.OpusCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.destroy();
        } catch (Exception e) {
            log.warn("释放 OpusCodec 失败", e);
        }
    }
}
