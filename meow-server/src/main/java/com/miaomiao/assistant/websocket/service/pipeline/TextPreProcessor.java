package com.miaomiao.assistant.websocket.service.pipeline;

import java.util.List;

/**
 * TTS 文本预处理器接口
 * <p>
 * 实现管道/过滤器模式，用于在提交 TTS 前对文本进行预处理。
 * 每个处理器接收文本列表，返回处理后的文本列表（支持一对多，如拆分）。
 */
public interface TextPreProcessor {

    /**
     * 处理文本列表
     *
     * @param texts 输入文本列表
     * @return 处理后的文本列表
     */
    List<String> process(List<String> texts);

    /**
     * 获取处理器优先级（数字越小越先执行）
     */
    int getOrder();
}
