package com.miaomiao.assistant.service.asr.provider;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.audio.AudioTranscriptionRequest;
import ai.z.openapi.service.audio.AudioTranscriptionResponse;
import com.miaomiao.assistant.service.asr.BaseASRProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 智谱AI ASR提供商
 */
@Slf4j
public class ZhipuASRProvider extends BaseASRProvider {

    private static final Set<String> SUPPORTED_MODELS = Set.of("chirp-beta");

    private final ZhipuAiClient client;

    public ZhipuASRProvider(String providerName, ZhipuAiClient client) {
        this.providerName = providerName;
        this.client = client;
        log.info("初始化智谱ASR Provider: {}", providerName);
    }

    @Override
    public boolean supportsModel(String model) {
        return SUPPORTED_MODELS.contains(model);
    }

    @Override
    public String[] getSupportedModels() {
        return SUPPORTED_MODELS.toArray(new String[0]);
    }

    @Override
    public ASRResult speechToText(byte[] audioData, ASROptions options) {
        Path tempFile = null;
        try {
            // 创建临时文件
            tempFile = createTempFile(audioData, options.getFormat());
            File file = tempFile.toFile();

            // 构建请求
            AudioTranscriptionRequest request = AudioTranscriptionRequest.builder()
                    .model(options.getModel())
                    .file(file)
                    .stream(false)
                    .build();

            // 调用ASR
            AudioTranscriptionResponse response = client.audio().createTranscription(request);

            if (response.isSuccess()) {
                return ASRResult.of(response.getData().getText());
            } else {
                throw new RuntimeException("ASR请求失败: " + response.getMsg());
            }

        } catch (Exception e) {
            log.error("ASR请求失败", e);
            throw new RuntimeException("语音识别失败", e);
        } finally {
            // 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public Flux<ASRResult> speechToTextStream(Flux<byte[]> audioStream, ASROptions options) {
        // 智谱目前不支持真正的流式ASR，这里转为批量处理
        return audioStream
                .reduce(new byte[0], this::concatBytes)
                .map(allData -> speechToText(allData, options))
                .flux();
    }

    private byte[] concatBytes(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private Path createTempFile(byte[] audioData, String format) throws IOException {
        String extension = getExtension(format);
        Path tempFile = Files.createTempFile("asr_", "." + extension);
        Files.write(tempFile, audioData);
        return tempFile;
    }

    private String getExtension(String format) {
        if (format == null || format.isEmpty()) {
            return "wav";
        }
        return format.toLowerCase().replace("audio/", "").replace("mpeg", "mp3");
    }
}
