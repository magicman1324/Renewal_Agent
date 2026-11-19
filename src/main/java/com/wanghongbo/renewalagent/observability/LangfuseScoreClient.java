package com.wanghongbo.renewalagent.observability;

import com.wanghongbo.renewalagent.config.LangfuseProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class LangfuseScoreClient {

    private final LangfuseProperties properties;
    private final RestClient restClient;
    private final String authorization;

    public LangfuseScoreClient(LangfuseProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.host()).build();
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (properties.publicKey() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8)
        );
    }

    public String publish(String traceId, String observationId, Map<String, Double> scores, String comment) {
        if (!properties.enabled() || properties.publicKey().isBlank() || properties.secretKey().isBlank()) {
            return "SKIPPED";
        }
        try {
            List<Map<String, Object>> payload = scores.entrySet().stream()
                    .map(entry -> scorePayload(traceId, observationId, entry.getKey(), entry.getValue(), comment))
                    .toList();
            restClient.post()
                    .uri("/api/public/scores")
                    .header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return "SENT";
        } catch (RuntimeException ex) {
            return "FAILED";
        }
    }

    private Map<String, Object> scorePayload(
            String traceId,
            String observationId,
            String name,
            double value,
            String comment
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", traceId + "-" + name);
        payload.put("traceId", traceId);
        if (observationId != null && !observationId.isBlank()) {
            payload.put("observationId", observationId);
        }
        payload.put("name", name);
        payload.put("value", value);
        payload.put("dataType", "NUMERIC");
        payload.put("comment", truncate(comment));
        return payload;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
