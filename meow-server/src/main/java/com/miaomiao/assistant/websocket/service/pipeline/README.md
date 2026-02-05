# Pipecat 核心 Java 移植使用指南

本项目将 Pipecat 框架的核心流式处理架构移植到 Java，优化 LLM → TTS → 前端的流式输出。

## 组件概览

### 1. AudioTimingController - 音频时机控制器

**核心功能**: 确保音频数据以恒定速率发送到前端，模拟真实音频设备的播放时钟。

```java
// 创建控制器
AudioTimingController controller = new AudioTimingController(
    4800,  // 音频块大小 (字节)
    24000  // 采样率 (Hz)
);

// 在发送音频块后调用
controller.waitForNextChunk();

// 中断时重置
controller.reset();
```

### 2. TextAggregator - 文本聚合器

**核心功能**: 可配置的文本聚合策略，在低延迟和音频流畅性之间取得平衡。

**推荐策略: HYBRID（混合策略）**

```java
// 使用混合策略（推荐！首句更激进，后续完整句子）
TextAggregator aggregator = new TextAggregator(
    TextAggregator.AggregationConfig.create()
        .strategy(TextAggregator.AggregationStrategy.HYBRID)
);

// 添加文本
List<AggregateResult> results = aggregator.append("你好，");

// 检查聚合结果
for (AggregateResult result : results) {
    System.out.println("聚合文本: " + result.getText());
    System.out.println("聚合类型: " + result.getType());
}

// 完成聚合（获取剩余文本）
AggregateResult finalResult = aggregator.complete();
```

**聚合策略对比：**

| 策略 | 首句延迟 | 音频质量 | 适用场景 |
|------|---------|---------|---------|
| SENTENCE | 高 | 最好 | 正式场合、长文本 |
| TOKEN | 最低 | 一般 | 极低延迟需求 |
| **HYBRID** | **低** | **好** | **推荐！通用场景** |

### 3. TTSFrameProcessor - TTS Frame 处理器

**核心功能**: 集成文本聚合和音频时机控制的完整 TTS 处理器。

```java
// 创建处理器（使用推荐的 HYBRID 策略）
TTSFrameProcessor processor = new TTSFrameProcessor(
    ttsManager,
    audioConverter,
    messageSender,
    configService,
    sessionState,
    TextAggregator.AggregationStrategy.HYBRID
);

// 处理 Frame
ProcessingContext context = new ProcessingContext(sessionId);
processor.processFrame(new Frames.TextFrame("你好世界"), context);
processor.processFrame(new Frames.EndFrame(), context); // 完成时发送
```

## OpenNLP 句子检测

集成了 OpenNLP 句子检测模型，正确处理 "Mr." "Dr." 等缩写词。

**下载模型:**
```bash
# 下载地址
https://dlcdn.apache.org/opennlp/models/ud-models-1.2/opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin

# 放置到
src/main/resources/models/opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin
```

**Lookahead 机制:**
```
输入: "Hello Mr. Wang"
↓ 检测到 "."
↓ 等待下一个非空白字符 "W"
↓ 调用 OpenNLP 检测
↓ 判断不是句子结束（因为 "Mr." 是缩写）
✓ 不切分
```

如不放置模型文件，系统自动降级使用简单规则：
- 中文、日文等非拉丁语系标点可正常识别
- 拉丁语系缩写可能误识别

## 已集成到 TTSService

`TTSService.processTTSStream()` 已完全重构为使用 Pipecat 管道架构：

```java
@Service
public class TTSService {

    public void processTTSStream(SessionState state, Flux<String> textStream, ConversationConfig config) {
        // 创建 TTS Frame 处理器（使用 HYBRID 策略优化首句延迟）
        TTSFrameProcessor processor = new TTSFrameProcessor(
            ttsManager,
            audioConverter,
            messageSender,
            configService,
            state,
            TextAggregator.AggregationStrategy.HYBRID
        );

        ProcessingContext context = new ProcessingContext(state.getSessionId());

        // 在独立线程处理 TTS（阻塞操作）
        textStream
            .takeWhile(text -> !state.isAborted())
            .publishOn(Schedulers.boundedElastic())
            .doOnNext(text -> {
                processor.processFrame(new Frames.TextFrame(text), context);
            })
            .doOnComplete(() -> {
                processor.processFrame(new Frames.EndFrame(), context);
            })
            .subscribe();
    }
}
```

## 与现有架构的对比

| 特性 | Pipecat 原版 (Python) | Java 移植版 | 之前的实现 |
|------|---------------------|------------|-------------|
| 音频时机控制 | ✅ 精确控制 | ✅ 已实现 | ❌ 缺失 |
| 文本聚合策略 | ✅ 可配置 | ✅ HYBRID | ⚠️ 无聚合 |
| OPUS 编码 | N/A | ✅ 已集成 | ✅ 有 |
| 中断机制 | ✅ InterruptionFrame | ✅ 已实现 | ✅ Disposable |
| 异步模型 | asyncio | Reactor + 阻塞 | Reactor ✅ |

## 关键优化点

### 1. HYBRID 聚合策略（核心优化）

**问题**: 按完整句子聚合会导致首句延迟过高

**解决**: `AggregationStrategy.HYBRID` 首句使用更激进的标点，后续使用完整句子

```java
// 首句标点（更激进，降低首句延迟）: "，,、。.？?！!；;：:~"
// 后续标点（保证音频质量）: "。.？?！!；;：:"
```

### 2. 音频时机控制（防止前端缓冲问题）

**问题**: 没有时机控制会导致前端音频缓冲问题（卡顿或重叠）

**解决**: `AudioTimingController.waitForNextChunk()` 确保音频按播放速率发送

### 3. 同步阻塞处理 TTS

**问题**: 之前使用 `subscribe()` 是异步的，时机控制无效

**解决**: 使用 `collectList().block()` 同步收集 TTS 音频，再按时机发送

## 参考来源

- Pipecat GitHub: https://github.com/pipecat-ai/pipecat
- TTSService 原版: `pipecat-main/src/pipecat/services/tts_service.py`
- WebSocket Transport 时机控制: `pipecat-main/src/pipecat/transports/websocket/server.py:406-415`
