package com.miaomiao.assistant.model.asr;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ASRResult {
    /**
     * 识别的文本
     */
    private String text;

    /**
     * 是否是最终结果（流式识别时使用）
     */
    private boolean isFinal;

    /**
     * 置信度 (0-1)
     */
    private Float confidence;

    public static ASRResult of(String text) {
        return new ASRResult(text, true, null);
    }
}