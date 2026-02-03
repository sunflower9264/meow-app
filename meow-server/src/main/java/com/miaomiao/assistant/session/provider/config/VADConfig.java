package com.miaomiao.assistant.session.provider.config;

import lombok.Data;

/**
 * VAD Provider Configuration
 */
@Data
public class VADConfig {
    private boolean enabled = true;
    private String serviceUrl = "http://127.0.0.1:8765";
    private double threshold = 0.5;
    private double thresholdLow = 0.15;
}
