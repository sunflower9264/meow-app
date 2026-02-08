package com.miaomiao.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 智谱AI音色克隆测试
 *
 * 使用步骤：
 * 1. 设置环境变量 CHATGLM_API_KEY（格式：id.secret）
 * 2. 运行main方法
 *
 * 流程：
 * 1. 上传音频文件 -> 获取 file_id
 * 2. 调用音色克隆 -> 获取 voice_id
 */
@Slf4j
public class VoiceCloneTest {

    private static final String API_BASE = "https://open.bigmodel.cn/api/paas/v4";

    // 测试音频文件路径
    private static final String AUDIO_FILE_PATH = "D:\\myworkspace\\meow\\meow-server\\src\\test\\resources\\manbo.mp3";

    public static void main(String[] args) {
        try {
            String apiKey = System.getenv("CHATGLM_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("请设置环境变量 CHATGLM_API_KEY");
            }

            OkHttpClient client = createHttpClient();
            ObjectMapper mapper = new ObjectMapper();

            // 步骤1: 上传音频文件
            String fileId = uploadAudioFile(client, mapper, apiKey, new File(AUDIO_FILE_PATH));
            log.info("文件上传成功，file_id: {}", fileId);

            // 步骤2: 调用音色克隆
            String voiceId = cloneVoice(client, mapper, apiKey, fileId);
            log.info("音色克隆成功，voice: {}", voiceId);

        } catch (Exception e) {
            log.error("测试失败", e);
            System.exit(1);
        }
    }

    /**
     * 上传音频文件
     *
     * POST /paas/v4/files
     * Content-Type: multipart/form-data
     *
     * 响应 FileObject:
     * {
     *   "id": "file_xxx",
     *   "object": "file",
     *   "bytes": 12345,
     *   "created_at": 1234567890,
     *   "filename": "manbo.mp3",
     *   "purpose": "voice-clone-input"
     * }
     */
    private static String uploadAudioFile(OkHttpClient client, ObjectMapper mapper, String apiKey, File file) throws IOException {
        if (!file.exists()) {
            throw new IllegalArgumentException("文件不存在: " + file.getPath());
        }

        log.info("上传文件: {} ({} bytes)", file.getName(), file.length());

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(file, MediaType.parse("audio/mpeg")))
                .addFormDataPart("purpose", "voice-clone-input")
                .build();

        Request request = new Request.Builder()
                .url(API_BASE + "/files")
                .post(requestBody)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            log.info("上传响应: {}", body);

            if (!response.isSuccessful()) {
                throw new IOException("上传失败: " + body);
            }

            JsonNode json = mapper.readTree(body);
            if (json.has("error")) {
                throw new IOException("上传错误: " + json.get("error"));
            }

            if (!json.has("id")) {
                throw new IOException("响应缺少id字段: " + body);
            }

            return json.get("id").asText();
        }
    }

    /**
     * 调用音色克隆
     *
     * POST /paas/v4/voice/clone
     * Content-Type: application/json
     *
     * 请求 VoiceCloneRequest:
     * {
     *   "model": "glm-tts-clone",
     *   "voice_name": "xxx",
     *   "input": "试听文本",
     *   "file_id": "file_xxx",
     *   "text": "示例音频文本(可选)",
     *   "request_id": "xxx(可选)"
     * }
     *
     * 响应 VoiceCloneResponse:
     * {
     *   "voice": "voice_xxx",
     *   "file_id": "file_xxx",
     *   "file_purpose": "voice-clone-output",
     *   "request_id": "xxx"
     * }
     */
    private static String cloneVoice(OkHttpClient client, ObjectMapper mapper, String apiKey, String fileId) throws IOException {
        log.info("调用音色克隆API...");

        String voiceName = "manbo_voice_" + System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        String jsonBody = String.format("""
            {
                "model": "glm-tts-clone",
                "voice_name": "%s",
                "input": "欢迎使用音色复刻服务，这将生成与示例音频相同音色的语音。",
                "file_id": "%s",
                "request_id": "%s"
            }
            """, voiceName, fileId, requestId);

        log.info("请求体: {}", jsonBody);

        Request request = new Request.Builder()
                .url(API_BASE + "/voice/clone")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            log.info("克隆响应: {}", body);

            if (!response.isSuccessful()) {
                throw new IOException("克隆失败: " + body);
            }

            JsonNode json = mapper.readTree(body);
            if (json.has("error")) {
                throw new IOException("克隆错误: " + json.get("error"));
            }

            if (!json.has("voice")) {
                throw new IOException("响应缺少voice字段: " + body);
            }

            return json.get("voice").asText();
        }
    }

    private static OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }
}
