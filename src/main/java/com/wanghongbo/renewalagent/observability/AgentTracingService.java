package com.wanghongbo.renewalagent.observability;

import com.wanghongbo.renewalagent.config.LangfuseProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.TokenUsage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentTracingService {

    private static final Logger log = LoggerFactory.getLogger(AgentTracingService.class);
    private static final String SCOPE = "renewal-agent";

    private final LangfuseProperties properties;
    private final OpenTelemetry openTelemetry;
    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;

    @Autowired
    public AgentTracingService(LangfuseProperties properties) {
        this(properties, createExporter(properties));
    }

    AgentTracingService(LangfuseProperties properties, SpanExporter spanExporter) {
        this.properties = properties;
        if (!properties.enabled() || spanExporter == null) {
            this.tracerProvider = null;
            this.openTelemetry = OpenTelemetry.noop();
        } else {
            this.tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(new BaggageSpanProcessor())
                    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                    .build();
            this.openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
        }
        this.tracer = openTelemetry.getTracer(SCOPE);
    }

    public TraceSession startInvestigation(
            String investigationId,
            String accountId,
            double riskScore,
            String question
    ) {
        Map<String, String> baggage = commonBaggage();
        baggage.put("langfuse.user.id", "customer-success-agent");
        baggage.put("langfuse.session.id", accountId);
        baggage.put("langfuse.trace.name", "agent.renewal-investigation");
        baggage.put("langfuse.trace.metadata.investigation_id", investigationId);
        baggage.put("langfuse.trace.metadata.account_id", accountId);
        baggage.put("langfuse.trace.metadata.risk_score", String.valueOf(riskScore));
        Attributes attributes = Attributes.builder()
                .put("langfuse.observation.type", "agent")
                .put("langfuse.observation.input", question)
                .put("service.name", "renewal-agent")
                .build();
        return startRoot("agent.renewal-investigation", baggage, attributes);
    }

    public TraceSession startExperimentItem(
            String experimentId,
            String experimentName,
            String datasetId,
            String itemId,
            String question,
            String expectedOutput
    ) {
        Map<String, String> baggage = commonBaggage();
        baggage.put("langfuse.experiment.id", experimentId);
        baggage.put("langfuse.experiment.name", experimentName);
        baggage.put("langfuse.experiment.dataset.id", datasetId);
        baggage.put("langfuse.experiment.item.id", itemId);
        baggage.put("langfuse.environment", "evaluation");
        baggage.put("langfuse.user.id", "agent-evaluator");
        baggage.put("langfuse.session.id", experimentId);
        Attributes attributes = Attributes.builder()
                .put("langfuse.observation.type", "agent")
                .put("langfuse.observation.input", question)
                .put("langfuse.experiment.item.expected_output", expectedOutput)
                .put("langfuse.experiment.description", "SaaS renewal agent golden-set evaluation")
                .build();
        TraceSession session = startRoot("experiment-item", baggage, attributes);
        session.rootSpan().setAttribute(
                "langfuse.experiment.item.root_observation_id",
                session.rootObservationId()
        );
        return session;
    }

    public Observation startObservation(
            TraceSession session,
            String name,
            String type,
            Map<String, Object> input
    ) {
        Span span = tracer.spanBuilder(name)
                .setParent(session.context())
                .setAttribute("langfuse.observation.type", type)
                .setAttribute("langfuse.observation.input", jsonString(input))
                .startSpan();
        return new Observation(span);
    }

    public Observation startGeneration(TraceSession session, String name, String model, String input) {
        Span span = tracer.spanBuilder(name)
                .setParent(session.context())
                .setAttribute("langfuse.observation.type", "generation")
                .setAttribute("langfuse.observation.input", input)
                .setAttribute("gen_ai.system", "openai-compatible")
                .setAttribute("gen_ai.request.model", model)
                .startSpan();
        return new Observation(span);
    }

    public void completeObservation(Observation observation, String output, Throwable error) {
        if (observation == null) {
            return;
        }
        Span span = observation.span();
        if (output != null) {
            span.setAttribute("langfuse.observation.output", output);
        }
        if (error != null) {
            span.setAttribute("langfuse.observation.level", "ERROR");
            span.setAttribute("langfuse.observation.status_message", safeMessage(error));
            span.setStatus(StatusCode.ERROR, safeMessage(error));
        }
        span.end();
    }

    public void completeGeneration(Observation observation, String output, TokenUsage usage, Throwable error) {
        if (observation == null) {
            return;
        }
        Span span = observation.span();
        if (output != null) {
            span.setAttribute("langfuse.observation.output", output);
        }
        if (usage != null) {
            span.setAttribute("gen_ai.usage.input_tokens", usage.inputTokens());
            span.setAttribute("gen_ai.usage.output_tokens", usage.outputTokens());
            span.setAttribute("gen_ai.response.model", usage.model());
        }
        if (error != null) {
            span.setAttribute("langfuse.observation.level", "ERROR");
            span.setAttribute("langfuse.observation.status_message", safeMessage(error));
            span.setStatus(StatusCode.ERROR, safeMessage(error));
        }
        span.end();
    }

    public void completeTrace(TraceSession session, String output, Throwable error) {
        if (session == null) {
            return;
        }
        Span span = session.rootSpan();
        if (output != null) {
            span.setAttribute("langfuse.observation.output", output);
        }
        if (error != null) {
            span.setAttribute("langfuse.observation.level", "ERROR");
            span.setAttribute("langfuse.observation.status_message", safeMessage(error));
            span.setStatus(StatusCode.ERROR, safeMessage(error));
        }
        span.end();
        if (tracerProvider != null) {
            tracerProvider.forceFlush().join(properties.flushTimeoutMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public boolean enabled() {
        return tracerProvider != null;
    }

    public String traceUrl(String traceId) {
        if (!enabled() || properties.projectId() == null || properties.projectId().isBlank()
                || traceId == null || traceId.isBlank()) {
            return null;
        }
        return properties.host().replaceAll("/+$", "")
                + "/project/" + properties.projectId() + "/traces/" + traceId;
    }

    public TraceSession createTestSession() {
        return startRoot("test", Map.of(), Attributes.empty());
    }

    @PreDestroy
    public void close() {
        if (tracerProvider != null) {
            tracerProvider.forceFlush().join(properties.flushTimeoutMillis(), TimeUnit.MILLISECONDS);
            tracerProvider.shutdown().join(properties.flushTimeoutMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private TraceSession startRoot(String name, Map<String, String> baggageValues, Attributes attributes) {
        io.opentelemetry.api.baggage.BaggageBuilder baggageBuilder = Baggage.current().toBuilder();
        baggageValues.forEach(baggageBuilder::put);
        Context parent = Context.current().with(baggageBuilder.build());
        Span span = tracer.spanBuilder(name)
                .setParent(parent)
                .setAllAttributes(attributes)
                .startSpan();
        Context context = parent.with(span);
        return new TraceSession(
                context,
                span,
                span.getSpanContext().getTraceId(),
                span.getSpanContext().getSpanId()
        );
    }

    private Map<String, String> commonBaggage() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("langfuse.environment", properties.environment());
        values.put("langfuse.release", properties.release());
        values.put("langfuse.trace.tags", "renewal-agent,synthetic");
        return values;
    }

    private static SpanExporter createExporter(LangfuseProperties properties) {
        if (!properties.enabled()) {
            return null;
        }
        if (properties.publicKey() == null || properties.publicKey().isBlank()
                || properties.secretKey() == null || properties.secretKey().isBlank()) {
            log.warn("Langfuse tracing requested but API keys are missing; tracing is disabled");
            return null;
        }
        String token = Base64.getEncoder().encodeToString(
                (properties.publicKey() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8)
        );
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.host().replaceAll("/+$", "") + "/api/public/otel/v1/traces")
                .setHeaders(() -> Map.of(
                        "Authorization", "Basic " + token,
                        "x-langfuse-ingestion-version", "4"
                ))
                .setTimeout(Duration.ofSeconds(10))
                .build();
    }

    private String jsonString(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append('"').append(entry.getKey()).append("\":\"")
                    .append(String.valueOf(entry.getValue()).replace("\"", "\\\"")).append('"');
            first = false;
        }
        return builder.append('}').toString();
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record TraceSession(
            Context context,
            Span rootSpan,
            String traceId,
            String rootObservationId
    ) {
    }

    public record Observation(Span span) {
    }

    private static final class BaggageSpanProcessor
            implements io.opentelemetry.sdk.trace.SpanProcessor {
        @Override
        public void onStart(Context parentContext, io.opentelemetry.sdk.trace.ReadWriteSpan span) {
            Baggage.fromContext(parentContext).forEach((key, value) ->
                    span.setAttribute(AttributeKey.stringKey(key), value.getValue()));
        }

        @Override
        public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }
}
