package com.miaomiao.assistant.tts;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 测试智谱 TTS API 原始返回
 */
public class ZhipuTTSTest {

    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/audio/speech";
    // 替换为你的 API Key
    private static final String API_KEY = System.getenv("CHATGLM_API_KEY");

    public static void main(String[] args) throws Exception {
        if (API_KEY == null || API_KEY.isEmpty()) {
            System.err.println("请设置环境变量 CHATGLM_API_KEY");
            return;
        }

        String text = "你好呀,欢迎来到智谱开放平台";
        
        // 测试 PCM 格式
        System.out.println("=== 测试 PCM 格式 ===");
        testTTS(text, "pcm", "speech.pcm");
        
        // 测试 WAV 格式
        System.out.println("\n=== 测试 WAV 格式 ===");
        testTTS(text, "wav", "speech.wav");
    }

    private static void testTTS(String text, String format, String outputFile) throws Exception {
        String requestBody = String.format("""
            {
                "model": "glm-tts",
                "input": "%s",
                "voice": "female",
                "speed": 1.0,
                "volume": 1.0,
                "response_format": "%s"
            }
            """, text, format);

        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        // 发送请求
        try (var os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        System.out.println("Response Code: " + responseCode);
        System.out.println("Content-Type: " + conn.getContentType());

        if (responseCode == 200) {
            // 保存到文件
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                int totalBytes = 0;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                System.out.println("数据已保存到 " + outputFile + "，共 " + totalBytes + " 字节");
            }
        } else {
            // 打印错误信息
            try (InputStream is = conn.getErrorStream()) {
                if (is != null) {
                    String error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    System.err.println("Error: " + error);
                }
            }
        }

        conn.disconnect();
    }
}
