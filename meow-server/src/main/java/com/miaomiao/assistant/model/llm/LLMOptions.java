package com.miaomiao.assistant.model.llm;

import lombok.Data;

@Data
public class LLMOptions {

    private String model;

    private Double temperature = 0.7;

    private Integer maxTokens = 2000;

    public static LLMOptions of(String model) {
        LLMOptions options = new LLMOptions();
        options.model = model;
        return options;
    }

    public static LLMOptions of(String model, Double temperature, Integer maxTokens) {
        LLMOptions options = of(model);
        options.temperature = temperature;
        options.maxTokens = maxTokens;
        return options;
    }
}