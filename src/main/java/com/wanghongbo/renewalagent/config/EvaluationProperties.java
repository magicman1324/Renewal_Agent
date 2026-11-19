package com.wanghongbo.renewalagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.evaluation")
public record EvaluationProperties(
        String datasetVersion,
        String outputDir,
        boolean ciFailOnThreshold,
        Judge judge,
        Thresholds thresholds
) {
    public record Judge(
            String mode,
            String baseUrl,
            String apiKey,
            String model,
            int timeoutSeconds
    ) {
    }

    public record Thresholds(
            double completionRate,
            double rootCauseAccuracy,
            double toolSelectionAccuracy,
            double evidenceCoverage,
            double actionPolicyPassRate,
            int maxCriticalViolations,
            int maxToolCalls
    ) {
    }
}
