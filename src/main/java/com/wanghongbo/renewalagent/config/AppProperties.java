package com.wanghongbo.renewalagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Seed seed,
        Risk risk,
        Agent agent,
        Rag rag,
        Queue queue,
        Lock lock,
        Followup followup
) {
    public record Seed(boolean enabled) {
    }

    public record Risk(
            double candidateThreshold,
            double highThreshold,
            int renewalWindowDays,
            int shortWindowDays
    ) {
    }

    public record Agent(
            int maxToolCalls,
            boolean inline,
            Llm llm
    ) {
    }

    public record Llm(
            String mode,
            String baseUrl,
            String apiKey,
            String model,
            int timeoutSeconds
    ) {
    }

    public record Rag(
            boolean enabled,
            String baseUrl,
            String searchPath
    ) {
    }

    public record Queue(
            String mode,
            RocketMq rocketmq
    ) {
    }

    public record RocketMq(
            String namesrvAddr,
            String producerGroup,
            String consumerGroup,
            String topic
    ) {
    }

    public record Lock(String mode) {
    }

    public record Followup(int delayDays) {
    }
}
