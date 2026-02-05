package com.miaomiao.assistant;

import net.labymod.opus.OpusCodec;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Opus编解码测试工具
 * 用于测试PCM -> Opus编码、播放、Opus -> PCM解码的完整流程
 */
public class OpusCodecTest {

    // Opus编解码参数（与编码器保持一致）
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNELS = 1;
    private static final int FRAME_SIZE = 480;
    private static final int BITRATE = 64000;

    public static void main(String[] args) throws Exception {
        loadNativeLibrary();

        // 文件路径
        Path pcmPath = Paths.get("meow-server/src/test/resources/tts_001.pcm").toAbsolutePath();
        Path opusPath = Paths.get("meow-server/src/test/resources/tts_001.opus").toAbsolutePath();
        Path decodedPcmPath = Paths.get("meow-server/src/test/resources/tts_001_decoded.pcm").toAbsolutePath();

        // 1. 读取原始PCM文件
        System.out.println("读取PCM文件: " + pcmPath);
        byte[] originalPcm;
        try {
            originalPcm = Files.readAllBytes(pcmPath);
        } catch (java.io.IOException e) {
            throw new RuntimeException("读取PCM文件失败: " + pcmPath, e);
        }
        System.out.println("原始PCM大小: " + originalPcm.length + " 字节");

        // 2. 初始化Opus编解码器
        System.out.println("\n初始化Opus编解码器...");
        OpusCodec encoder = OpusCodec.newBuilder()
                .withSampleRate(SAMPLE_RATE)
                .withChannels(CHANNELS)
                .withBitrate(BITRATE)
                .withFrameSize(FRAME_SIZE)
                .build();

        OpusCodec decoder = OpusCodec.newBuilder()
                .withSampleRate(SAMPLE_RATE)
                .withChannels(CHANNELS)
                .withBitrate(BITRATE)
                .withFrameSize(FRAME_SIZE)
                .build();
        System.out.println("Opus编解码器初始化成功");

        // 3. PCM -> Opus 编码
        System.out.println("\n开始PCM -> Opus编码...");
        byte[] opusData = encodePcmToOpus(encoder, originalPcm);
        try {
            Files.write(opusPath, opusData);
        } catch (java.io.IOException e) {
            throw new RuntimeException("写入Opus文件失败: " + opusPath, e);
        }
        System.out.println("Opus编码完成，大小: " + opusData.length + " 字节");
        System.out.println("Opus文件已保存: " + opusPath);
        System.out.println("压缩比: " + String.format("%.2f%%", (opusData.length * 100.0 / originalPcm.length)));

        // 4. 播放原始PCM
        System.out.println("\n========== 播放原始PCM音频 ==========");
        playPcmAudio(originalPcm);

        // 5. 播放Opus（先解码再播放）
        System.out.println("\n========== 播放Opus音频（解码后播放） ==========");
        byte[] opusDecodedPcm = decodeOpusToPcm(decoder, opusData);
        playPcmAudio(opusDecodedPcm);

        // 6. 保存解码后的PCM文件（用于后续分析）
        try {
            Files.write(decodedPcmPath, opusDecodedPcm);
        } catch (java.io.IOException e) {
            throw new RuntimeException("写入解码PCM文件失败: " + decodedPcmPath, e);
        }
        System.out.println("解码后的PCM已保存: " + decodedPcmPath);

        // 7. 对比原始和解码后的PCM
        System.out.println("\n对比分析:");
        System.out.println("原始PCM大小: " + originalPcm.length + " 字节");
        System.out.println("解码PCM大小: " + opusDecodedPcm.length + " 字节");
        System.out.println("差异: " + Math.abs(originalPcm.length - opusDecodedPcm.length) + " 字节");

        // 计算MSE（均方误差）
        int minLen = Math.min(originalPcm.length, opusDecodedPcm.length);
        long sumSquaredError = 0;
        int maxDiff = 0;
        for (int i = 0; i < minLen; i += 2) { // 16位PCM，每次跳过2字节比较一个样本
            short original = (short) ((originalPcm[i + 1] << 8) | (originalPcm[i] & 0xFF));
            short decoded = (short) ((opusDecodedPcm[i + 1] << 8) | (opusDecodedPcm[i] & 0xFF));
            int diff = original - decoded;
            sumSquaredError += (long) diff * diff;
            maxDiff = Math.max(maxDiff, Math.abs(diff));
        }
        double mse = (double) sumSquaredError / (minLen / 2);
        System.out.println("均方误差(MSE): " + String.format("%.2f", mse));
        System.out.println("最大样本差异: " + maxDiff);

        // 清理资源
        encoder.destroy();
        decoder.destroy();
        System.out.println("\n测试完成！");
    }

    /**
     * 将PCM数据编码为Opus
     *
     * @param opusCodec Opus编码器
     * @param pcmData   16位PCM数据(小端序)
     * @return Opus编码后的数据（多个帧拼接，每帧前有2字节长度头）
     */
    private static byte[] encodePcmToOpus(OpusCodec opusCodec, byte[] pcmData) throws IOException {
        int frameSizeBytes = FRAME_SIZE * CHANNELS * 2; // 480 * 1 * 2 = 960字节
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        int offset = 0;
        int frameCount = 0;
        while (offset + frameSizeBytes <= pcmData.length) {
            byte[] frameData = new byte[frameSizeBytes];
            System.arraycopy(pcmData, offset, frameData, 0, frameSizeBytes);

            byte[] encoded = opusCodec.encodeFrame(frameData);

            // 写入帧长度（2字节，小端序）和帧数据
            outputStream.write(encoded.length & 0xFF);
            outputStream.write((encoded.length >> 8) & 0xFF);
            outputStream.write(encoded);

            offset += frameSizeBytes;
            frameCount++;
        }

        // 处理剩余不足一帧的数据：补0凑成完整帧
        int remaining = pcmData.length - offset;
        if (remaining > 0) {
            byte[] frameData = new byte[frameSizeBytes];
            System.arraycopy(pcmData, offset, frameData, 0, remaining);

            byte[] encoded = opusCodec.encodeFrame(frameData);
            outputStream.write(encoded.length & 0xFF);
            outputStream.write((encoded.length >> 8) & 0xFF);
            outputStream.write(encoded);
            frameCount++;
        }

        System.out.println("编码帧数: " + frameCount);
        return outputStream.toByteArray();
    }

    /**
     * 将Opus数据解码为PCM
     *
     * @param opusCodec Opus解码器
     * @param opusData  Opus编码后的数据（每帧前有2字节长度头）
     * @return 16位PCM数据(小端序)
     */
    private static byte[] decodeOpusToPcm(OpusCodec opusCodec, byte[] opusData) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int offset = 0;
        int frameCount = 0;

        while (offset + 2 <= opusData.length) {
            // 读取帧长度（2字节，小端序）
            int frameSize = (opusData[offset] & 0xFF) | ((opusData[offset + 1] & 0xFF) << 8);
            offset += 2;

            // 检查是否有足够的数据
            if (offset + frameSize > opusData.length) {
                System.err.println("警告: 帧数据不完整，已解析 " + frameCount + " 帧");
                break;
            }

            // 提取帧数据
            byte[] frameData = new byte[frameSize];
            System.arraycopy(opusData, offset, frameData, 0, frameSize);
            offset += frameSize;

            // 解码这一帧
            byte[] decoded = opusCodec.decodeFrame(frameData);
            outputStream.write(decoded);
            frameCount++;
        }

        System.out.println("解码帧数: " + frameCount);
        return outputStream.toByteArray();
    }

    /**
     * 播放PCM音频数据
     *
     * @param pcmData 16位PCM数据(小端序)
     */
    private static void playPcmAudio(byte[] pcmData) throws Exception {
        AudioFormat format = new AudioFormat(
                SAMPLE_RATE,
                16,              // 16位样本
                CHANNELS,        // 单声道
                true,            // 有符号
                false            // 小端序
        );

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("音频格式不支持: " + format);
        }

        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        System.out.println("开始播放... (音频长度: " + (pcmData.length / format.getFrameSize() * 1000.0 / SAMPLE_RATE) + " ms)");

        // 分块写入，避免一次性写入过多数据
        int bufferSize = 9600; // 约100ms的数据
        int offset = 0;
        while (offset < pcmData.length) {
            int remaining = pcmData.length - offset;
            int toWrite = Math.min(bufferSize, remaining);
            line.write(pcmData, offset, toWrite);
            offset += toWrite;
        }

        // 等待播放完成
        line.drain();
        line.close();
        System.out.println("播放完成");
    }

    /**
     * 加载Opus native库
     */
    private static void loadNativeLibrary() {
        String osName = System.getProperty("os.name").toLowerCase();
        String platform = osName.contains("win") ? "windows-x64" : "linux-x64";
        String libraryFileName = osName.contains("win") ? "opus-jni-native.dll" : "libopus-jni-native.so";

        // 尝试从native目录加载
        Path nativePath = Paths.get("meow-server/native", platform, libraryFileName).toAbsolutePath();

        if (Files.exists(nativePath)) {
            System.load(nativePath.toString());
            System.out.println("Native库加载成功: " + nativePath);
        } else {
            throw new RuntimeException("找不到native库: " + nativePath);
        }
    }
}
