package com.miaomiao.assistant.model.tts.provider;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.audio.AudioSpeechRequest;
import ai.z.openapi.service.audio.AudioSpeechStreamingResponse;
import ai.z.openapi.service.model.ModelData;
import com.miaomiao.assistant.model.tts.BaseTTSProvider;
import com.miaomiao.assistant.model.tts.TTSAudio;
import com.miaomiao.assistant.model.tts.TTSOptions;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.Base64;

/**
 * 智谱AI TTS提供商
 */
@Slf4j
public class ZhipuTTSProvider extends BaseTTSProvider {

    private final ZhipuAiClient client;

    public ZhipuTTSProvider(String providerName, ZhipuAiClient client) {
        this.providerName = providerName;
        this.client = client;
        log.info("初始化智谱TTS Provider: name={}", providerName);
    }

    @Override
    public TTSAudio textToSpeech(String text, TTSOptions options) {
        try {
            AudioSpeechRequest request = buildRequest(text, options, false);
            var response = client.audio().createSpeech(request);

            if (!response.isSuccess()) {
                throw new RuntimeException("TTS请求失败: " + response.getMsg());
            }

            // 获取音频文件并读取
            java.io.File audioFile = response.getData();
            byte[] audioData = java.nio.file.Files.readAllBytes(audioFile.toPath());

            // 清理临时文件
            audioFile.delete();

            return new TTSAudio(audioData, options.getFormat(), true);

        } catch (Exception e) {
            log.error("TTS请求失败", e);
            throw new RuntimeException("语音合成失败", e);
        }
    }

    @Override
    public Flux<TTSAudio> textToSpeechStream(String text, TTSOptions options) {
        try {
            AudioSpeechRequest request = buildRequest(text, options, true);
            AudioSpeechStreamingResponse response = client.audio().createStreamingSpeech(request);

            if (!response.isSuccess()) {
                return Flux.error(new RuntimeException("TTS流式请求失败: " + response.getMsg()));
            }

            Flowable<ModelData> flowable = response.getFlowable();
            if (flowable == null) {
                return Flux.error(new RuntimeException("TTS流式响应为空"));
            }

            String format = options.getFormat();

            // 将RxJava Flowable转换为Reactor Flux
            return Flux.from(flowable)
                    .map(modelData -> {
                        String base64Audio = extractBase64Audio(modelData);
                        byte[] audioData = base64Audio.isEmpty() ? new byte[0] : Base64.getDecoder().decode(base64Audio);
                        boolean isFinished = isStreamFinished(modelData);
                        return new TTSAudio(audioData, format, isFinished);
                    })
                    .filter(audio -> audio.getAudioData().length > 0)
                    .doOnComplete(() -> {})
                    .doOnError(error -> log.error("TTS流式响应错误", error));

        } catch (Exception e) {
            log.error("创建TTS流失败", e);
            return Flux.error(new RuntimeException("语音合成失败", e));
        }
    }

    private AudioSpeechRequest buildRequest(String text, TTSOptions options, boolean stream) {
        return AudioSpeechRequest.builder()
                .model(options.getModel())
                .input(text)
                .voice(options.getVoice())
                .speed(options.getSpeed())
                .volume(options.getVolume())
                .responseFormat(options.getFormat())
                .encodeFormat("base64")
                .stream(stream)
                .build();
    }

    private String extractBase64Audio(ModelData modelData) {
        if (modelData.getChoices() != null && !modelData.getChoices().isEmpty()) {
            var choice = modelData.getChoices().get(0);
            if (choice.getDelta() != null) {
                return choice.getDelta().getContent();
            }
        }
        if (modelData.getDelta() != null) {
            return modelData.getDelta();
        }
        return "";
    }

    private boolean isStreamFinished(ModelData modelData) {
        if (modelData.getChoices() != null && !modelData.getChoices().isEmpty()) {
            var choice = modelData.getChoices().get(0);
            return "stop".equals(choice.getFinishReason());
        }
        return false;
    }
}
