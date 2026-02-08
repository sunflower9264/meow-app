package com.miaomiao.assistant.model.asr.provider;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.audio.AudioTranscriptionRequest;
import ai.z.openapi.service.audio.AudioTranscriptionResponse;
import ai.z.openapi.service.audio.AudioTranscriptionChunk;
import com.miaomiao.assistant.model.asr.ASROptions;
import com.miaomiao.assistant.model.asr.ASRResult;
import com.miaomiao.assistant.model.asr.BaseASRModelProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 智谱AI ASR提供商
 */
@Slf4j
public class ZhipuASRProvider extends BaseASRModelProvider {

    private static final String FILE_EXTENSION = ".wav";

    private final ZhipuAiClient client;

    public ZhipuASRProvider(String providerName, ZhipuAiClient client) {
        this.providerName = providerName;
        this.client = client;
        log.info("初始化智谱ASR Provider: name={}", providerName);
    }

    @Override
    public Flux<ASRResult> speechToTextStream(Flux<byte[]> audioStream, ASROptions options) {
        return audioStream
                .reduce(new byte[0], this::concatBytes)
                .flatMapMany(allData -> {
                    if (allData.length == 0) {
                        return Flux.empty();
                    }

                    Path tempFile = null;
                    try {
                        tempFile = createTempFile(allData);

                        AudioTranscriptionRequest request = AudioTranscriptionRequest.builder()
                                .model(options.getModel())
                                .file(tempFile.toFile())
                                .stream(true)
                                .build();

                        AudioTranscriptionResponse response = client.audio().createTranscription(request);

                        Flux<ASRResult> chunkFlux = Flux.empty();
                        if (response.getFlowable() != null) {
                            chunkFlux = Flux.from(response.getFlowable())
                                    .handle((chunk, sink) -> {
                                        String text = extractChunkText(chunk);
                                        if (hasText(text)) {
                                            sink.next(new ASRResult(text, false, null));
                                        }
                                    });
                        }

                        String finalText = null;
                        if (response.getData() != null) {
                            finalText = response.getData().getText();
                        }
                        if (response.getFlowable() == null && !hasText(finalText) && !response.isSuccess()) {
                            throw new RuntimeException("ASR流式请求失败: " + response.getMsg());
                        }
                        Flux<ASRResult> finalFlux = hasText(finalText)
                                ? Flux.just(new ASRResult(finalText, true, null))
                                : Flux.empty();

                        Path finalTempFile = tempFile;
                        return chunkFlux.concatWith(finalFlux)
                                .doFinally(signal -> deleteTempFile(finalTempFile));
                    } catch (Exception e) {
                        deleteTempFile(tempFile);
                        log.error("ASR流式请求失败", e);
                        return Flux.error(new RuntimeException("语音流式识别失败", e));
                    }
                });
    }

    private byte[] concatBytes(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private String extractChunkText(AudioTranscriptionChunk chunk) {
        if (chunk == null) {
            return null;
        }

        String topLevelDelta = chunk.getDelta();
        if (hasText(topLevelDelta)) {
            return topLevelDelta;
        }

        if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        chunk.getChoices().forEach(choice -> {
            if (choice != null && choice.getDelta() != null && hasText(choice.getDelta().getContent())) {
                builder.append(choice.getDelta().getContent());
            }
        });
        String text = builder.toString();
        return hasText(text) ? text : null;
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private Path createTempFile(byte[] audioData) throws IOException {
        Path tempFile = Files.createTempFile("asr_", FILE_EXTENSION);
        Files.write(tempFile, audioData);
        return tempFile;
    }

    private void deleteTempFile(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
        }
    }

}
