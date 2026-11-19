package com.wanghongbo.renewalagent.observability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanghongbo.renewalagent.config.LangfuseDatasetProperties;
import com.wanghongbo.renewalagent.config.LangfuseProperties;
import com.wanghongbo.renewalagent.evaluation.EvaluationFixture;
import com.wanghongbo.renewalagent.model.Models.EvaluationCase;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class LangfuseDatasetClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseDatasetClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final LangfuseProperties properties;
    private final LangfuseDatasetProperties datasetProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String authorization;

    public LangfuseDatasetClient(
            LangfuseProperties properties,
            LangfuseDatasetProperties datasetProperties,
            RestClient.Builder builder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.datasetProperties = datasetProperties;
        this.restClient = builder.baseUrl(properties.host()).build();
        this.objectMapper = objectMapper;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (properties.publicKey() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8)
        );
    }

    public String sync(List<EvaluationCase> cases, EvaluationFixture fixture) {
        if (!properties.enabled() || !datasetProperties.enabled()
                || properties.publicKey().isBlank() || properties.secretKey().isBlank()) {
            return fixture.version();
        }
        try {
            Map<String, Object> dataset = findDataset();
            if (dataset == null) {
                Map<?, ?> created = restClient.post()
                        .uri("/api/public/v2/datasets")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "name", datasetProperties.name(),
                                "description", "SaaS renewal agent immutable golden set",
                                "metadata", Map.of(
                                        "datasetVersion", fixture.version(),
                                        "fixtureHash", fixture.hash(),
                                        "source", "git"
                                )
                        ))
                        .retrieve()
                        .body(Map.class);
                dataset = created == null ? null : objectMapper.convertValue(created, MAP_TYPE);
            }
            if (dataset == null) {
                return fixture.version();
            }
            for (EvaluationCase testCase : cases) {
                upsertItem(testCase, fixture);
            }
            Object id = dataset.get("id");
            return id == null ? datasetProperties.name() : String.valueOf(id);
        } catch (RuntimeException ex) {
            log.warn("Unable to sync Langfuse dataset {}; local evaluation will continue",
                    datasetProperties.name(), ex);
            return fixture.version();
        }
    }

    private Map<String, Object> findDataset() {
        try {
            Map<?, ?> found = restClient.get()
                    .uri("/api/public/v2/datasets/{name}", datasetProperties.name())
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(Map.class);
            return found == null ? null : objectMapper.convertValue(found, MAP_TYPE);
        } catch (HttpClientErrorException.NotFound ex) {
            return null;
        }
    }

    private void upsertItem(EvaluationCase testCase, EvaluationFixture fixture) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("accountId", testCase.accountId());
        input.put("question", testCase.question());
        input.put("datasetVersion", fixture.version());
        input.put("fixtureHash", fixture.hash());

        Map<String, Object> expectedOutput = new LinkedHashMap<>();
        expectedOutput.put("rootCause", testCase.expectedRootCause());
        expectedOutput.put("ownerRole", testCase.expectedOwnerRole());
        expectedOutput.put("requiredTools", testCase.requiredTools());
        expectedOutput.put("requiredEvidence", testCase.requiredEvidence());
        expectedOutput.put("forbiddenActions", testCase.forbiddenActions());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", datasetProperties.name());
        payload.put("id", stableItemId(fixture.version(), testCase.caseId()));
        payload.put("input", input);
        payload.put("expectedOutput", expectedOutput);
        payload.put("metadata", Map.of("caseId", testCase.caseId(), "source", "git"));
        restClient.post()
                .uri("/api/public/dataset-items")
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private String stableItemId(String datasetVersion, String caseId) {
        String value = "renewal-agent-" + datasetVersion + "-" + caseId;
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
