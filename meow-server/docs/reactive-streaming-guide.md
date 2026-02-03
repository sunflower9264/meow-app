# Java 后端流式编程（Reactive Streaming）详解

本文档详细讲解项目中使用的 **Project Reactor** 流式编程模式，帮助你理解后端的 `Flux`、`Mono`、`Sinks` 等响应式编程概念。

---

## 目录

1. [为什么使用流式编程？](#1-为什么使用流式编程)
2. [核心概念速览](#2-核心概念速览)
3. [Flux 详解](#3-flux-详解)
4. [Mono 详解](#4-mono-详解)
5. [Sinks 详解](#5-sinks-详解)
6. [项目中的实际应用](#6-项目中的实际应用)
7. [常用操作符参考](#7-常用操作符参考)
8. [调试技巧](#8-调试技巧)

---

## 1. 为什么使用流式编程？

### 传统方式 vs 流式方式

**传统方式（阻塞）**：
```java
// 等待 LLM 返回完整响应（可能需要 10 秒）
String fullResponse = llm.getResponse(prompt);  // 阻塞 10 秒
// 然后一次性转语音
byte[] audio = tts.textToSpeech(fullResponse);  // 再阻塞 5 秒
// 用户总共等待 15 秒才能听到第一个字
```

**流式方式（非阻塞）**：
```java
// LLM 边生成边返回
Flux<String> textStream = llm.responseStream(prompt);
// TTS 边收到文字边转语音
Flux<byte[]> audioStream = tts.textToSpeechStream(textStream);
// 用户 1 秒内就能听到第一个字！
```

### 本项目的流式管道

```
用户语音 → ASR识别 → LLM生成(流式) → TTS语音合成(流式) → 返回用户
                         ↓                    ↓
                    一个字一个字地          一句话一句话地
                    从大模型流出            转成语音返回
```

---

## 2. 核心概念速览

| 概念 | 类比 | 说明 |
|------|------|------|
| `Flux<T>` | 水管/传送带 | 0~N 个元素的异步序列 |
| `Mono<T>` | 单个包裹 | 0~1 个元素的异步结果 |
| `Sinks` | 水龙头 | 手动往 Flux/Mono 里"注水"的控制器 |
| `subscribe()` | 打开水龙头 | 真正开始执行流的操作 |
| 操作符 | 流水线上的加工站 | `map`, `filter`, `flatMap` 等 |

---

## 3. Flux 详解

### 3.1 什么是 Flux？

`Flux<T>` 代表一个可以发出 **0 到 N 个元素** 的异步序列，可能以成功或错误结束。

```java
import reactor.core.publisher.Flux;

// 创建一个简单的 Flux
Flux<String> words = Flux.just("Hello", "World", "!");

// 订阅才会执行
words.subscribe(
    word -> System.out.println(word),      // 每个元素的处理
    error -> System.err.println(error),    // 发生错误时
    () -> System.out.println("完成！")      // 所有元素处理完毕
);

// 输出:
// Hello
// World
// !
// 完成！
```

### 3.2 Flux 的生命周期

```
创建 Flux  →  添加操作符  →  subscribe()  →  数据开始流动
   ↓              ↓              ↓              ↓
Flux.just()    .map()        订阅者接收     onNext(元素)
Flux.create()  .filter()     开始消费       onError(异常)
Flux.from()    .flatMap()                   onComplete()
```

### 3.3 项目中的 Flux 示例

```java
// 来自 LLMProvider.java
public abstract Flux<LLMResponse> responseStream(
    List<ChatMessage> messages, 
    Double temperature, 
    Integer maxTokens
);
```

这个方法返回一个 `Flux<LLMResponse>`，大模型生成的每一小段文字都会作为一个 `LLMResponse` 发射出来。

---

## 4. Mono 详解

### 4.1 什么是 Mono？

`Mono<T>` 代表一个可以发出 **0 或 1 个元素** 的异步结果。

```java
import reactor.core.publisher.Mono;

// 创建一个 Mono
Mono<String> greeting = Mono.just("Hello");

// 从普通方法创建异步 Mono
Mono<TTSAudio> audioMono = Mono.fromSupplier(() -> textToSpeech(text));

// 订阅
greeting.subscribe(
    value -> System.out.println(value),
    error -> System.err.println(error),
    () -> System.out.println("完成")
);
```

### 4.2 Mono vs Flux

| 场景 | 使用 |
|------|------|
| 返回单个结果 | `Mono<T>` |
| 返回多个结果 | `Flux<T>` |
| HTTP 请求返回 | `Mono<Response>` |
| 流式数据 | `Flux<Chunk>` |

### 4.3 项目中的 Mono 示例

```java
// 来自 TTSProvider.java
public abstract Mono<TTSAudio> textToSpeechAsync(String text);

// 来自 EdgeTTSProvider.java - 把同步方法包装成异步
@Override
public Mono<TTSAudio> textToSpeechAsync(String text) {
    return Mono.fromSupplier(() -> textToSpeech(text));
}
```

---

## 5. Sinks 详解

### 5.1 什么是 Sinks？

`Sinks` 是一个 **手动控制的发射器**，允许你在代码的任意位置向 Flux 中发送数据。

把它想象成：
- `Flux` = 水管
- `Sinks` = 水龙头控制器

### 5.2 Sinks 的类型

```java
// 1. Sinks.Many - 发射多个元素（变成 Flux）
Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
Flux<String> flux = sink.asFlux();

// 2. Sinks.One - 发射单个元素（变成 Mono）
Sinks.One<String> sinkOne = Sinks.one();
Mono<String> mono = sinkOne.asMono();
```

### 5.3 Sinks.Many 的三种模式

| 模式 | 方法 | 说明 | 适用场景 |
|------|------|------|----------|
| **unicast** | `Sinks.many().unicast()` | 只能有一个订阅者 | 单一消费者 |
| **multicast** | `Sinks.many().multicast()` | 多个订阅者共享数据 | 广播场景 |
| **replay** | `Sinks.many().replay()` | 新订阅者可以收到历史数据 | 需要回放 |

### 5.4 Sinks 的核心方法

```java
Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

// 发送一个元素
sink.tryEmitNext("Hello");

// 发送完成信号
sink.tryEmitComplete();

// 发送错误信号
sink.tryEmitError(new RuntimeException("出错了"));

// 转换为 Flux 供订阅
Flux<String> flux = sink.asFlux();
```

### 5.5 项目中的 Sinks 实战

```java
// 来自 ChatGLMLLMProvider.java
@Override
public Flux<LLMResponse> responseStream(List<ChatMessage> messages, ...) {
    // 1. 创建一个 Sink
    Sinks.Many<LLMResponse> sink = Sinks.many().multicast().onBackpressureBuffer();

    // 2. 创建 SSE 连接监听器
    EventSourceListener listener = new EventSourceListener() {
        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            // 解析 LLM 返回的数据
            JsonNode jsonNode = objectMapper.readTree(data);
            String content = jsonNode.path("choices").get(0).path("delta").path("content").asText();
            
            // 3. 往 Sink 里发射数据
            if (!content.isEmpty()) {
                sink.tryEmitNext(new LLMResponse(content, false));
            }
            
            // 检查是否结束
            if ("[DONE]".equals(data)) {
                sink.tryEmitNext(new LLMResponse("", true));
                sink.tryEmitComplete();  // 发送完成信号
            }
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            sink.tryEmitError(t);  // 发送错误信号
        }
    };

    // 4. 发起 SSE 请求
    EventSource.Factory factory = EventSources.createFactory(client);
    factory.newEventSource(request, listener);

    // 5. 返回 Flux 供外部订阅
    return sink.asFlux();
}
```

**工作流程图**：
```
ChatGLM API (SSE)          Sink                    外部订阅者
     |                      |                         |
     |-- "你" ------------> tryEmitNext() ---------> onNext("你")
     |-- "好" ------------> tryEmitNext() ---------> onNext("好")
     |-- "！" ------------> tryEmitNext() ---------> onNext("！")
     |-- [DONE] ----------> tryEmitComplete() -----> onComplete()
```

---

## 6. 项目中的实际应用

### 6.1 完整的流式管道

来自 `ConversationWebSocketHandler.java`：

```java
private void processLLMAndTTS(SessionState state, String text) throws IOException {
    LLMProvider llm = llmFactory.getDefaultProvider();
    TTSProvider tts = ttsFactory.getDefaultProvider();

    // 1. 创建文本片段的 Sink（用于收集 LLM 输出的完整句子）
    Sinks.Many<String> textSink = Sinks.many().unicast().onBackpressureBuffer();
    StringBuilder currentBuffer = new StringBuilder();

    // 2. 启动 LLM 流式响应
    Flux<LLMResponse> llmStream = llm.responseStream(messages, temperature, maxTokens);

    // 3. 订阅 LLM 流，处理每个 token
    llmStream.subscribe(
        llmResponse -> {
            String content = llmResponse.getText();
            currentBuffer.append(content);

            // 检测句子边界（句号、问号等）
            int punctPos = findLastPunctuation(currentBuffer.toString(), "。？！");
            if (punctPos >= 0) {
                String sentence = currentBuffer.substring(0, punctPos + 1);
                
                // 发送完整句子到 TTS 流
                textSink.tryEmitNext(sentence);
                
                // 清空已处理的部分
                currentBuffer.delete(0, punctPos + 1);
            }
            
            // LLM 结束时，处理剩余文本
            if (llmResponse.isFinished()) {
                if (currentBuffer.length() > 0) {
                    textSink.tryEmitNext(currentBuffer.toString());
                }
                textSink.tryEmitComplete();
            }
        },
        error -> textSink.tryEmitComplete(),
        () -> log.debug("LLM stream completed")
    );

    // 4. 将文本流转换为 TextSegment 流
    Flux<TextSegment> textSegmentStream = textSink.asFlux()
            .map(txt -> new TextSegment(txt, true, false));

    // 5. TTS 接收文本流，输出音频流
    Flux<TTSAudio> ttsStream = tts.textStreamToSpeechStream(textSegmentStream);

    // 6. 订阅音频流，发送给客户端
    ttsStream.subscribe(
        ttsAudio -> sendTTSMessage(state, ttsAudio),
        error -> sendError(state.getSession(), error.getMessage()),
        () -> log.debug("TTS stream completed")
    );
}
```

### 6.2 流程图解

```
         LLM API (SSE)
              |
              | "你好" "，" "我" "是" "AI" "助" "手" "。" "请" "问" ...
              ↓
    ┌─────────────────────┐
    │   LLM Flux 流       │  ← 一个字一个字地流出
    └─────────┬───────────┘
              │
              ↓
    ┌─────────────────────┐
    │   句子缓冲 Buffer   │  ← 累积到完整句子
    │   "你好，我是AI助手。"│
    └─────────┬───────────┘
              │
              │ textSink.tryEmitNext()
              ↓
    ┌─────────────────────┐
    │   Text Sink         │  ← 发射完整句子
    └─────────┬───────────┘
              │
              │ .asFlux().map()
              ↓
    ┌─────────────────────┐
    │   TextSegment Flux  │
    └─────────┬───────────┘
              │
              │ tts.textStreamToSpeechStream()
              ↓
    ┌─────────────────────┐
    │   TTS Flux 流       │  ← 句子转语音
    └─────────┬───────────┘
              │
              │ subscribe()
              ↓
    ┌─────────────────────┐
    │   WebSocket 发送    │  → 用户听到语音
    └─────────────────────┘
```

### 6.3 TTS 流式处理

来自 `EdgeTTSProvider.java`：

```java
@Override
public Flux<TTSAudio> textStreamToSpeechStream(Flux<TextSegment> textStream) {
    // concatMap: 保证顺序处理，一句话处理完再处理下一句
    return textStream.concatMap(segment -> {
        
        // Flux.create: 手动创建一个 Flux
        return Flux.create(sink -> {
            // 发起 HTTP 请求
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(Call call, Response response) {
                    // 流式读取音频数据
                    InputStream inputStream = response.body().byteStream();
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        byte[] chunk = Arrays.copyOf(buffer, bytesRead);
                        
                        TTSAudio audio = new TTSAudio();
                        audio.setAudioData(chunk);
                        audio.setFinished(false);
                        
                        // 发射音频块
                        sink.next(audio);
                    }
                    
                    // 发送完成信号
                    sink.complete();
                }
                
                @Override
                public void onFailure(Call call, IOException e) {
                    sink.error(e);
                }
            });
        });
    });
}
```

---

## 7. 常用操作符参考

### 7.1 转换操作符

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `map` | 同步转换每个元素 | `flux.map(s -> s.toUpperCase())` |
| `flatMap` | 异步转换，并发执行 | `flux.flatMap(id -> fetchUser(id))` |
| `concatMap` | 异步转换，顺序执行 | `flux.concatMap(text -> tts(text))` |
| `filter` | 过滤元素 | `flux.filter(n -> n > 0)` |

### 7.2 map vs flatMap vs concatMap

```java
// map: 1对1 同步转换
Flux.just(1, 2, 3)
    .map(n -> n * 2)           // 结果: 2, 4, 6
    .subscribe(System.out::println);

// flatMap: 1对多 异步转换，不保证顺序
Flux.just("A", "B", "C")
    .flatMap(letter -> 
        Flux.just(letter + "1", letter + "2"))  // 结果: 可能是 A1,B1,A2,B2...
    .subscribe(System.out::println);

// concatMap: 1对多 异步转换，保证顺序
Flux.just("A", "B", "C")
    .concatMap(letter -> 
        Flux.just(letter + "1", letter + "2"))  // 结果: A1,A2,B1,B2,C1,C2
    .subscribe(System.out::println);
```

**项目中为什么用 concatMap？**

TTS 必须按顺序处理句子，否则用户听到的语音会乱序：
```java
// 必须用 concatMap 保证顺序
textStream.concatMap(segment -> callTTSService(segment))
```

### 7.3 组合操作符

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `concat` | 顺序连接多个 Flux | `Flux.concat(flux1, flux2)` |
| `merge` | 并行合并多个 Flux | `Flux.merge(flux1, flux2)` |
| `zip` | 配对合并 | `Flux.zip(flux1, flux2)` |

### 7.4 错误处理

```java
flux
    .doOnError(e -> log.error("出错了", e))   // 记录错误
    .onErrorReturn("默认值")                   // 出错时返回默认值
    .onErrorResume(e -> Flux.just("备用数据")) // 出错时切换到备用流
    .retry(3)                                  // 出错时重试 3 次
```

---

## 8. 调试技巧

### 8.1 添加日志

```java
flux
    .doOnNext(item -> log.debug("收到: {}", item))
    .doOnError(e -> log.error("错误: ", e))
    .doOnComplete(() -> log.debug("完成"))
    .doOnSubscribe(s -> log.debug("订阅开始"))
    .subscribe();
```

### 8.2 使用 log() 操作符

```java
flux
    .log()  // 自动记录所有信号（onNext, onError, onComplete）
    .subscribe();
```

### 8.3 常见问题排查

| 问题 | 可能原因 | 解决方案 |
|------|----------|----------|
| 流不执行 | 没有 subscribe() | 添加 .subscribe() |
| 数据乱序 | 使用了 flatMap | 改用 concatMap |
| 流提前结束 | tryEmitComplete() 调用太早 | 检查完成条件 |
| 内存溢出 | 背压处理不当 | 使用 onBackpressureBuffer() |

---

## 总结

### 核心记忆点

1. **Flux** = 0~N 个元素的异步流（水管）
2. **Mono** = 0~1 个元素的异步结果（单个包裹）
3. **Sinks** = 手动控制的发射器（水龙头）
4. **subscribe()** = 触发执行（打开水龙头）
5. **concatMap** = 保证顺序的异步转换
6. **tryEmitNext/Complete/Error** = 向 Sink 发送信号

### 项目中的核心模式

```java
// 1. 创建 Sink
Sinks.Many<T> sink = Sinks.many().unicast().onBackpressureBuffer();

// 2. 在回调中发射数据
callback.onData(data -> sink.tryEmitNext(data));
callback.onComplete(() -> sink.tryEmitComplete());
callback.onError(e -> sink.tryEmitError(e));

// 3. 返回 Flux 供订阅
return sink.asFlux();
```

---

## 参考资料

- [Project Reactor 官方文档](https://projectreactor.io/docs/core/release/reference/)
- [Reactor 中文指南](https://github.com/reactor/reactor-core)
- [Spring WebFlux 官方文档](https://docs.spring.io/spring-framework/docs/current/reference/html/web-reactive.html)
