# Meow 喵喵助手

一个基于 Vue 3 + Spring Boot 的智能语音助手Demo，支持文本对话和语音交互。

## 功能特性

- **文本对话** - 支持流式响应，实时打字效果
- **语音交互** - 语音转文字（ASR）、文字转语音（TTS）
- **双链路支持** - 文本输入和语音输入并行处理
- **中断机制** - 支持打断当前对话
- **实时通信** - 基于 WebSocket 的双向通信

## 技术栈

### 前端（meow-client）
- Vue 3 + Vite + Pinia
- opus-decoder（音频解码）
- WebSocket 实时通信

### 后端（meow-server）
- Spring Boot 3.2.5 + Java 17
- Spring WebSocket
- 智谱 AI SDK
- Opus JNI（音频编码）

## 目录结构

```
meow/
├── meow-client/    # Vue 3 前端应用
├── meow-server/    # Spring Boot 后端应用
└── docs/          # 项目文档
```

## 快速开始

### 前端
```bash
cd meow-client
npm install
npm run dev
```

### 后端
```bash
cd meow-server
mvn spring-boot:run
```

## 通信协议

WebSocket 消息类型：
- **客户端→服务端**: `text`（文本）、`audio`（音频）
- **服务端→客户端**: `text`（文本）、`stt`（识别）、`llm_token`（流式）、`tts`（音频帧）

## 许可证

MIT License
