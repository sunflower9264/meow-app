package com.miaomiao.assistant.codec;

import ai.z.openapi.ZhipuAiClient;
import com.miaomiao.assistant.model.tts.TTSOptions;
import lombok.extern.slf4j.Slf4j;
import net.labymod.opus.OpusCodec;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * OpusEncoder 编码测试
 * <p>
 * 测试流程：
 * 1. 第一次运行时设置 FETCH_FROM_TTS = true，从TTS获取PCM并保存到文件
 * 2. 后续运行设置 FETCH_FROM_TTS = false，直接从文件加载PCM（不调用API）
 * 3. 对PCM进行Opus编码
 * 4. 保存Opus结果供验证
 * 5. 可选：解码Opus验证音频质量
 */
@Slf4j
public class OpusEncoderTest {

    // ==================== 配置项 ====================

    /**
     * 是否从TTS API获取数据（第一次运行设为true，后续设为false）
     * true: 调用TTS API获取PCM，并保存到文件
     * false: 从文件加载PCM，不调用API
     */
    private static final boolean FETCH_FROM_TTS = true;

    /**
     * 测试文本
     */
    private static final String TEST_TEXT = "你好，这是一个测试。今天天气真不错！";


    /**
     * 数据目录（用于保存/加载测试数据）
     */
    private static final String DATA_DIR = "D:/myworkspace/meow/meow-server/src/test/resources/data";

    // PCM文件路径
    private static final String PCM_FILE_PATH = DATA_DIR + "/test_audio.pcm";

    // Opus输出文件路径
    private static final String OPUS_FILE_PATH = DATA_DIR + "/test_audio.opus";


    // Opus参数
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNELS = 1;
    private static final int BITRATE = 64000;
    private static final int FRAME_SIZE = 480;

    // ==================== 测试方法 ====================

    /**
     * 测试完整的编码流程
     */
    @Test
    public void testOpusEncoding() throws Exception {
        log.info("========== 开始 Opus 编码测试 ==========");
        log.info("FETCH_FROM_TTS = {}", FETCH_FROM_TTS);

        // 确保数据目录存在
        ensureDataDirExists();

        // 1. 获取PCM数据（从TTS或从文件）
        byte[] pcmData;
        if (FETCH_FROM_TTS) {
            log.info("从TTS API获取PCM数据...");
            pcmData = fetchPcmFromTTS();
            savePcmToFile(pcmData, PCM_FILE_PATH);
            log.info("PCM数据已保存到: {}", PCM_FILE_PATH);
        } else {
            log.info("从文件加载PCM数据: {}", PCM_FILE_PATH);
            pcmData = loadPcmFromFile(PCM_FILE_PATH);
            log.info("已加载PCM数据: {} 字节", pcmData.length);
        }

        // 2. 初始化Opus编码器
        log.info("初始化Opus编码器...");
        initNativeLibrary();
        OpusCodec encoder = createEncoder();

        // 3. 编码PCM为Opus
        log.info("开始编码PCM -> Opus...");
        byte[] opusData = encodePcmToOpus(encoder, pcmData);
        log.info("编码完成: PCM {} 字节 -> Opus {} 字节 (压缩率: {:.1f}%)",
                pcmData.length, opusData.length,
                (100.0 * opusData.length / pcmData.length));

        // 4. 保存Opus数据
        saveOpusToFile(opusData, OPUS_FILE_PATH);
        log.info("Opus数据已保存到: {}", OPUS_FILE_PATH);

        // 清理
        encoder.destroy();
        log.info("========== 测试完成 ==========");
    }

    /**
     * 播放测试：对比原始PCM和Opus编解码后的音频
     * 先播放原始PCM，等待2秒，再播放解码后的PCM，可以听出是否有杂音
     */
    @Test
    public void testPlayAudioComparison() throws Exception {
        log.info("========== 开始音频播放对比测试 ==========");

        ensureDataDirExists();

        // 1. 加载原始PCM
        byte[] originalPcm = loadPcmFromFile(PCM_FILE_PATH);
        log.info("加载原始PCM: {} 字节", originalPcm.length);

        // 2. 加载Opus并解码
        byte[] opusData = loadPcmFromFile(OPUS_FILE_PATH);
        log.info("加载Opus数据: {} 字节", opusData.length);

        initNativeLibrary();
        OpusCodec codec = createEncoder();

        byte[] decodedPcm = decodeOpusToPcm(codec, opusData);
        log.info("Opus解码后PCM: {} 字节", decodedPcm.length);

        // 3. 播放原始PCM
        log.info("正在播放【原始PCM】...");
        playPcm(originalPcm);

//        // 4. 等待2秒
//        Thread.sleep(2000);
//
//        // 5. 播放解码后的PCM
//        log.info("正在播放【Opus编解码后的PCM】...");
//        playPcm(decodedPcm);
//
//        codec.destroy();
//        log.info("========== 播放对比测试完成 ==========");
    }

    /**
     * 播放PCM数据（24kHz, 16bit, 单声道）
     */
    private void playPcm(byte[] pcmData) {
        try {
            // 24kHz, 16bit, 单声道
            AudioFormat format = new AudioFormat(
                    SAMPLE_RATE,      // 采样率
                    16,              // 采样位数
                    CHANNELS,        // 声道数
                    true,            // 有符号
                    false            // 小端序
            );

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);

            line.open(format);
            line.start();

            // 分块播放，避免一次性写入太多数据
            int bufferSize = format.getFrameSize() * FRAME_SIZE * 10; // 10帧一块
            byte[] buffer = new byte[bufferSize];

            int offset = 0;
            while (offset < pcmData.length) {
                int remaining = pcmData.length - offset;
                int length = Math.min(buffer.length, remaining);

                System.arraycopy(pcmData, offset, buffer, 0, length);
                line.write(buffer, 0, length);

                offset += length;

                // 打印进度
                int progress = (offset * 100) / pcmData.length;
                log.debug("播放进度: {}%", progress);
            }

            // 等待缓冲区播放完毕
            line.drain();
            line.close();

            log.info("播放完成，音频时长: {:.2f}秒",
                    (double) pcmData.length / (SAMPLE_RATE * CHANNELS * 2));
        } catch (Exception e) {
            log.error("播放PCM失败", e);
            throw new RuntimeException("播放音频失败", e);
        }
    }

    /**
     * 解码Opus为PCM
     */
    private byte[] decodeOpusToPcm(OpusCodec codec, byte[] opusData) throws IOException {
        ByteArrayOutputStream decodedPcm = new ByteArrayOutputStream();

        int offset = 0;
        int frameCount = 0;

        while (offset < opusData.length) {
            // 读取帧长度
            if (offset + 2 > opusData.length) break;
            int frameSize = (opusData[offset + 1] & 0xFF) << 8 | (opusData[offset] & 0xFF);
            offset += 2;

            if (offset + frameSize > opusData.length) break;

            byte[] frameData = new byte[frameSize];
            System.arraycopy(opusData, offset, frameData, 0, frameSize);
            offset += frameSize;

            byte[] decodedFrame = codec.decodeFrame(frameData);
            decodedPcm.write(decodedFrame);
            frameCount++;
        }

        log.info("解码完成: {} 帧", frameCount);
        return decodedPcm.toByteArray();
    }

    // ==================== 辅助方法 ====================

    /**
     * 从TTS获取PCM数据
     */
    private byte[] fetchPcmFromTTS() {
        try {
            // 创建TTS客户端
            String apiKey = System.getenv("CHATGLM_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                throw new RuntimeException("请设置环境变量 CHATGLM_API_KEY");
            }

            ZhipuAiClient client = new ZhipuAiClient.Builder()
                    .apiKey(apiKey)
                    .build();

            // 创建TTS请求
            TTSOptions options = TTSOptions.builder()
                    .model("glm-tts")
                    .voice("female")     // 智谱TTS支持的voice: female, male等
                    .speed(1.0f)
                    .volume(1.0f)
                    .format("pcm")       // 获取PCM格式
                    .build();

            // 调用TTS（非流式，获取完整音频）
            log.info("调用TTS API: 文本='{}', 模型={}", TEST_TEXT, options.getModel());
            var response = client.audio().createSpeech(
                    ai.z.openapi.service.audio.AudioSpeechRequest.builder()
                            .model(options.getModel())
                            .input(TEST_TEXT)
                            .voice(options.getVoice())
                            .speed(options.getSpeed())
                            .volume(options.getVolume())
                            .responseFormat(options.getFormat())
                            .encodeFormat("base64")
                            .stream(false)
                            .build()
            );

            if (!response.isSuccess()) {
                throw new RuntimeException("TTS请求失败: " + response.getMsg());
            }

            // 读取音频文件（SDK内部已处理Base64解码，直接是PCM数据）
            java.io.File audioFile = response.getData();
            byte[] audioData = java.nio.file.Files.readAllBytes(audioFile.toPath());

            // 清理临时文件
            audioFile.delete();

            log.info("TTS返回PCM数据: {} 字节, 采样率={}Hz", audioData.length, SAMPLE_RATE);
            return audioData;

        } catch (Exception e) {
            log.error("从TTS获取PCM失败", e);
            throw new RuntimeException("TTS请求失败", e);
        }
    }

    /**
     * 初始化Native库
     */
    private void initNativeLibrary() {
        System.load("D:\\myworkspace\\meow\\meow-server\\native\\windows-x64\\opus-jni-native.dll");
    }

    /**
     * 创建Opus编码器
     */
    private OpusCodec createEncoder() {
        return OpusCodec.newBuilder()
                .withSampleRate(SAMPLE_RATE)
                .withChannels(CHANNELS)
                .withBitrate(BITRATE)
                .withFrameSize(FRAME_SIZE)
                .build();
    }

    /**
     * 编码PCM为Opus
     */
    private byte[] encodePcmToOpus(OpusCodec encoder, byte[] pcmData) {
        try {
            int frameSizeBytes = FRAME_SIZE * CHANNELS * 2;
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            int offset = 0;
            int frameCount = 0;

            while (offset + frameSizeBytes <= pcmData.length) {
                byte[] frameData = new byte[frameSizeBytes];
                System.arraycopy(pcmData, offset, frameData, 0, frameSizeBytes);

                byte[] encoded = encoder.encodeFrame(frameData);

                // 写入帧长度（2字节，小端序）
                outputStream.write(encoded.length & 0xFF);
                outputStream.write((encoded.length >> 8) & 0xFF);
                outputStream.write(encoded);

                offset += frameSizeBytes;
                frameCount++;
            }

            int remaining = pcmData.length - offset;
            if (remaining > 0) {
                log.debug("丢弃最后 {} 字节不完整帧", remaining);
            }

            log.debug("编码完成: {} 帧", frameCount);
            return outputStream.toByteArray();

        } catch (Exception e) {
            log.error("编码失败", e);
            throw new RuntimeException("编码失败", e);
        }
    }

    /**
     * 确保数据目录存在
     */
    private void ensureDataDirExists() {
        try {
            Path dir = Paths.get(DATA_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("创建数据目录: {}", DATA_DIR);
            }
        } catch (IOException e) {
            throw new RuntimeException("创建数据目录失败: " + DATA_DIR, e);
        }
    }

    /**
     * 保存PCM到文件
     */
    private void savePcmToFile(byte[] pcmData, String filePath) {
        try {
            Files.write(Paths.get(filePath), pcmData,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败: " + filePath, e);
        }
    }

    /**
     * 从文件加载PCM
     */
    private byte[] loadPcmFromFile(String filePath) {
        try {
            return Files.readAllBytes(Paths.get(filePath));
        } catch (IOException e) {
            throw new RuntimeException("加载文件失败: " + filePath, e);
        }
    }

    /**
     * 保存Opus到文件
     */
    private void saveOpusToFile(byte[] opusData, String filePath) {
        try {
            Files.write(Paths.get(filePath), opusData,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("保存Opus文件失败: " + filePath, e);
        }
    }
}
