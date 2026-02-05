package com.miaomiao.assistant.model.llm;

import lombok.Data;

@Data
public class LLMOptions {

    private String model;

    private Double temperature = 0.7;

    private Integer maxTokens = 1000;

    public static LLMOptions of(String model) {
        LLMOptions options = new LLMOptions();
        options.model = model;
        return options;
    }
}