package com.wanghongbo.renewalagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wanghongbo.renewalagent.config.LangfuseDatasetProperties;
import com.wanghongbo.renewalagent.config.LangfuseProperties;
import com.wanghongbo.renewalagent.evaluation.EvaluationFixture;
import com.wanghongbo.renewalagent.model.Models.EvaluationCase;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LangfuseDatasetClientTest {

    @Test
    void createsDatasetAndUpsertsStableItems() throws Exception {
        AtomicInteger itemCalls = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/public/v2/datasets/renewal-agent-golden-set-v2",
                exchange -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                });
        server.createContext("/api/public/v2/datasets", exchange -> {
            byte[] response = "{\"id\":\"dataset-1\",\"name\":\"renewal-agent-golden-set-v2\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/public/dataset-items", exchange -> {
            itemCalls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            LangfuseProperties properties = new LangfuseProperties(
                    true,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "pk",
                    "sk",
                    "project",
                    "test",
                    "test",
                    1000
            );
            LangfuseDatasetClient client = new LangfuseDatasetClient(
                    properties,
                    new LangfuseDatasetProperties(true, "renewal-agent-golden-set-v2"),
                    RestClient.builder(),
                    new ObjectMapper()
            );
            EvaluationFixture fixture = new EvaluationFixture(
                    "golden-set-v2", "a".repeat(64), LocalDate.of(2026, 11, 19),
                    Map.of(), Map.of(), Map.of(), Map.of()
            );
            EvaluationCase testCase = new EvaluationCase(
                    "case-1", "ACC-001", "question", "SUPPORT_BLOCKED", "SUPPORT",
                    List.of("tool"), List.of("evidence"), List.of(), 8
            );

            String datasetId = client.sync(List.of(testCase), fixture);

            assertThat(datasetId).isEqualTo("dataset-1");
            assertThat(itemCalls.get()).isEqualTo(1);
            assertThat(body.get())
                    .contains("\"datasetName\":\"renewal-agent-golden-set-v2\"")
                    .contains("\"id\":\"renewal-agent-golden-set-v2-case-1\"");
        } finally {
            server.stop(0);
        }
    }
}
