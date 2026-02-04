package com.miaomiao.assistant.model;

import lombok.Data;

@Data
public class BaseModelProvider {

    protected String providerName;

    protected String apiKey;

    protected String baseUrl;

    protected Boolean enableTokenCache = false;

    protected Integer tokenExpire = 3600000;
}
