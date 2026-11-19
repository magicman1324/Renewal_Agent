package com.wanghongbo.renewalagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.langfuse")
public record LangfuseProperties(
        boolean enabled,
        String host,
        String publicKey,
        String secretKey,
        String projectId,
        String environment,
        String release,
        int flushTimeoutMillis
) {
}
