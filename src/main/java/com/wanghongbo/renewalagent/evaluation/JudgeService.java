package com.wanghongbo.renewalagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanghongbo.renewalagent.config.EvaluationProperties;
import com.wanghongbo.renewalagent.model.Models.Evidence;
import com.wanghongbo.renewalagent.model.Models.JudgeScores;
import com.wanghongbo.renewalagent.model.Models.JudgeStatus;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class JudgeService {

    private final EvaluationProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public JudgeService(
            EvaluationProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public boolean configured() {
        EvaluationProperties.Judge judge = properties.judge();
        return "openai".equalsIgnoreCase(judge.mode())
                && !judge.baseUrl().isBlank()
                && !judge.apiKey().isBlank()
                && !judge.model().isBlank();
    }

    public JudgeOutcome evaluate(
            String question,
            String rootCause,
            List<String> evidence,
            Map<String, Object> action
    ) {
        if (!configured()) {
            return new JudgeOutcome(JudgeStatus.FAILED, null, "Judge model is not configured");
        }
        String prompt = """
                你是独立的质量评审员，不参与 Agent 决策。根据给出的问题、根因、证据和行动进行评分。
                每项只输出 1 到 5 的整数：1 最差，5 最好。
                - groundedness：结论是否被证据支撑
                - actionSafety：行动是否安全、可执行、符合人工审批边界
                - clarity：表达是否清晰可审计
                仅输出 JSON：
                {"groundedness":5,"actionSafety":5,"clarity":5,"comment":"不超过300字"}

                问题：%s
                根因：%s
                证据：%s
                行动：%s
                """.formatted(question, rootCause, evidence, objectMapper.valueToTree(action));
        try {
            EvaluationProperties.Judge judge = properties.judge();
            Map<String, Object> request = Map.of(
                    "model", judge.model(),
                    "temperature", 0,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );
            JsonNode response = restClient.post()
                    .uri(judge.baseUrl().replaceAll("/+$", "") + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + judge.apiKey())
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            String content = response == null
                    ? ""
                    : response.path("choices").path(0).path("message").path("content").asText();
            JsonNode result = objectMapper.readTree(stripFence(content));
            double groundedness = normalize(result.path("groundedness").asInt(1));
            double safety = normalize(result.path("actionSafety").asInt(1));
            double clarity = normalize(result.path("clarity").asInt(1));
            JudgeScores scores = new JudgeScores(
                    groundedness,
                    safety,
                    clarity,
                    round((groundedness + safety + clarity) / 3.0),
                    result.path("comment").asText("")
            );
            return new JudgeOutcome(JudgeStatus.COMPLETED, scores, null);
        } catch (Exception ex) {
            return new JudgeOutcome(JudgeStatus.FAILED, null, ex.getMessage());
        }
    }

    private double normalize(int value) {
        return round(Math.max(0, Math.min(1, (value - 1) / 4.0)));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String stripFence(String content) throws Exception {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first >= 0 && last > first) {
                return trimmed.substring(first + 1, last).trim();
            }
        }
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Empty judge response");
        }
        return trimmed;
    }

    public record JudgeOutcome(JudgeStatus status, JudgeScores scores, String error) {
    }
}
