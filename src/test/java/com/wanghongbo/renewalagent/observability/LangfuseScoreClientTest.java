package com.wanghongbo.renewalagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.wanghongbo.renewalagent.config.LangfuseProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LangfuseScoreClientTest {

    @Test
    void postsScoresWithBasicAuthAndIdempotentIds() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/public/scores", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            LangfuseProperties properties = new LangfuseProperties(
                    true,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "pk-test",
                    "sk-test",
                    "project",
                    "test",
                    "test",
                    1000
            );
            LangfuseScoreClient client = new LangfuseScoreClient(properties, RestClient.builder());

            String status = client.publish(
                    "trace-1",
                    "observation-1",
                    Map.of("completion", 1.0, "rootCauseAccuracy", 1.0),
                    "test"
            );

            assertThat(status).isEqualTo("SENT");
            assertThat(authorization.get()).startsWith("Basic ");
            assertThat(body.get())
                    .contains("\"traceId\":\"trace-1\"")
                    .contains("\"observationId\":\"observation-1\"")
                    .contains("\"id\":\"trace-1-completion\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledClientSkipsNetwork() {
        LangfuseProperties properties = new LangfuseProperties(
                false, "http://127.0.0.1:1", "", "", "", "test", "test", 1000);

        assertThat(new LangfuseScoreClient(properties, RestClient.builder())
                .publish("trace", "observation", Map.of("score", 1.0), "test"))
                .isEqualTo("SKIPPED");
    }
}
