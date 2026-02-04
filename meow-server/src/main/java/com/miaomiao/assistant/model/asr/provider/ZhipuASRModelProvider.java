package com.miaomiao.assistant.model.asr.provider;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.audio.AudioTranscriptionRequest;
import ai.z.openapi.service.audio.AudioTranscriptionResponse;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.asr.ASROptions;
import com.miaomiao.assistant.model.asr.ASRResult;
import com.miaomiao.assistant.model.asr.BaseASRModelProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 智谱AI ASR提供商
 */
@Slf4j
public class ZhipuASRModelProvider extends BaseASRModelProvider {

    private final ZhipuAiClient client;

    public ZhipuASRModelProvider(String providerName, AIServiceConfig.ProviderConfig providerConfig) {
        super.providerName = providerName;
        super.apiKey = providerConfig.getApiKey();
        super.baseUrl = providerConfig.getBaseUrl();
        super.enableTokenCache = providerConfig.getEnableTokenCache();
        ZhipuAiClient.Builder builder = ZhipuAiClient.builder().apiKey(providerConfig.getApiKey());
        if (providerConfig.getEnableTokenCache()) {
            super.tokenExpire = providerConfig.getTokenExpire();
            builder.enableTokenCache().tokenExpire(super.tokenExpire);
        }
        this.client = builder.build();
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
