package com.wanghongbo.renewalagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.langfuse.dataset")
public record LangfuseDatasetProperties(boolean enabled, String name) {
}
