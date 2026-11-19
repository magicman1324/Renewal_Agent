package com.wanghongbo.renewalagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanghongbo.renewalagent.config.LangfuseProperties;
import com.wanghongbo.renewalagent.model.Models.TokenUsage;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentTracingServiceTest {

    @Test
    void exportsExperimentRootAndToolObservationWithLangfuseAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        LangfuseProperties properties = new LangfuseProperties(
                true,
                "https://cloud.langfuse.com",
                "pk-test",
                "sk-test",
                "project-test",
                "evaluation",
                "test-release",
                1000
        );
        AgentTracingService tracing = new AgentTracingService(properties, exporter);

        AgentTracingService.TraceSession root = tracing.startExperimentItem(
                "experiment-1", "golden-set", "golden-set-v1", "case-1",
                "question", "{\"expectedRootCause\":\"SUPPORT_BLOCKED\"}");
        AgentTracingService.Observation tool = tracing.startObservation(
                root, "getSupportTickets", "tool", Map.of("accountId", "ACC-003"));
        tracing.completeObservation(tool, "2 open P1 tickets", null);
        AgentTracingService.Observation generation = tracing.startGeneration(
                root, "agent.analyzer", "judge-model", "input");
        tracing.completeGeneration(generation, "output", new TokenUsage(120, 40, "judge-model"), null);
        tracing.completeTrace(root, "{\"rootCause\":\"SUPPORT_BLOCKED\"}", null);

        assertThat(exporter.getFinishedSpanItems()).hasSize(3);
        assertThat(exporter.getFinishedSpanItems())
                .allSatisfy(span -> {
                    assertThat(span.getTraceId()).isEqualTo(root.traceId());
                    assertThat(span.getAttributes().asMap())
                            .containsKey(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.experiment.id"));
                });
        assertThat(exporter.getFinishedSpanItems())
                .anySatisfy(span -> {
                    assertThat(span.getName()).isEqualTo("experiment-item");
                    assertThat(span.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.type")))
                            .isEqualTo("agent");
                });
        assertThat(exporter.getFinishedSpanItems())
                .anySatisfy(span -> {
                    assertThat(span.getName()).isEqualTo("getSupportTickets");
                    assertThat(span.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.type")))
                            .isEqualTo("tool");
                });
        assertThat(exporter.getFinishedSpanItems())
                .anySatisfy(span -> {
                    assertThat(span.getName()).isEqualTo("agent.analyzer");
                    assertThat(span.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.input_tokens")))
                            .isEqualTo(120L);
                    assertThat(span.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.output_tokens")))
                            .isEqualTo(40L);
                });
        tracing.close();
    }
}
