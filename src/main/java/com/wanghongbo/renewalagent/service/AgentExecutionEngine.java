package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.ActionDraft;
import com.wanghongbo.renewalagent.model.Models.AgentExecutionResult;
import com.wanghongbo.renewalagent.model.Models.AgentTelemetry;
import com.wanghongbo.renewalagent.model.Models.AnalysisOutcome;
import com.wanghongbo.renewalagent.model.Models.Decision;
import com.wanghongbo.renewalagent.model.Models.Evidence;
import com.wanghongbo.renewalagent.model.Models.FinalAnalysis;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import com.wanghongbo.renewalagent.model.Models.PlanningOutcome;
import com.wanghongbo.renewalagent.model.Models.StepStatus;
import com.wanghongbo.renewalagent.model.Models.TokenUsage;
import com.wanghongbo.renewalagent.model.Models.ToolDefinition;
import com.wanghongbo.renewalagent.model.Models.ToolResult;
import com.wanghongbo.renewalagent.observability.AgentTracingService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionEngine {

    private final AppProperties properties;
    private final ToolService tools;
    private final AgentIntelligenceService intelligence;
    private final AgentTracingService tracing;
    private final Clock clock;

    public AgentExecutionEngine(
            AppProperties properties,
            ToolService tools,
            AgentIntelligenceService intelligence,
            AgentTracingService tracing,
            Clock clock
    ) {
        this.properties = properties;
        this.tools = tools;
        this.intelligence = intelligence;
        this.tracing = tracing;
        this.clock = clock;
    }

    public AgentExecutionResult execute(
            Account account,
            String executionId,
            String question,
            double riskScore,
            ExperimentContext experiment,
            AgentExecutionObserver observer
    ) {
        long started = System.nanoTime();
        AgentExecutionObserver safeObserver = observer == null ? AgentExecutionObserver.Noop.INSTANCE : observer;
        AgentTracingService.TraceSession trace = experiment == null
                ? tracing.startInvestigation(executionId, account.id(), riskScore, question)
                : tracing.startExperimentItem(
                        experiment.experimentId(),
                        experiment.experimentName(),
                        experiment.datasetId(),
                        experiment.itemId(),
                        question,
                        experiment.expectedOutput()
                );
        List<Evidence> evidence = new ArrayList<>();
        List<String> calledTools = new ArrayList<>();
        TokenUsage totalUsage = new TokenUsage(0, 0, "mixed");
        try {
            safeObserver.onStatus(InvestigationStatus.PLANNING);
            safeObserver.onStep("PLAN", "开始规划调查", "加载只读工具与当前客户上下文",
                    StepStatus.SUCCEEDED, 0);

            Set<String> called = new HashSet<>();
            List<ToolDefinition> definitions = tools.definitions();
            int maxCalls = Math.min(properties.agent().maxToolCalls(), definitions.size());
            for (int i = 0; i < maxCalls; i++) {
                AgentTracingService.Observation planning = planningObservation(trace, account, question, called);
                PlanningOutcome outcome;
                try {
                    outcome = intelligence.nextDecision(account, question, definitions, evidence, called);
                    tracing.completeGeneration(planning, String.valueOf(outcome.decision()), outcome.usage(), null);
                    totalUsage = add(totalUsage, outcome.usage());
                } catch (RuntimeException ex) {
                    tracing.completeGeneration(planning, null, TokenUsage.deterministic(), ex);
                    throw ex;
                }
                Decision decision = outcome.decision();
                if (!"CALL_TOOL".equalsIgnoreCase(decision.action()) || decision.tool() == null) {
                    break;
                }
                if (definitions.stream().noneMatch(tool -> tool.name().equals(decision.tool()))) {
                    safeObserver.onStep("POLICY", "工具调用被拒绝",
                            "未注册工具：" + decision.tool(), StepStatus.FAILED, 0);
                    break;
                }
                if (!called.add(decision.tool())) {
                    safeObserver.onStep("POLICY", "跳过重复工具",
                            decision.tool(), StepStatus.SKIPPED, 0);
                    break;
                }

                safeObserver.onStatus(InvestigationStatus.EXECUTING_TOOL);
                AgentTracingService.Observation toolObservation = tracing.startObservation(
                        trace,
                        decision.tool(),
                        "tool",
                        Map.of("arguments", decision.arguments(), "reason", decision.reason())
                );
                long toolStarted = System.nanoTime();
                try {
                    ToolResult result = tools.execute(
                            decision.tool(),
                            account,
                            decision.arguments() == null ? Map.of() : decision.arguments()
                    );
                    long durationMs = (System.nanoTime() - toolStarted) / 1_000_000;
                    Evidence item = new Evidence(
                            "EVD-" + UUID.randomUUID(),
                            executionId,
                            sourceFor(decision.tool()),
                            result.summary(),
                            result.summary(),
                            result.data(),
                            Instant.now(clock)
                    );
                    evidence.add(item);
                    safeObserver.onEvidence(item);
                    safeObserver.onStep(
                            "TOOL",
                            decision.tool(),
                            decision.reason() + " | " + result.summary(),
                            result.success() ? StepStatus.SUCCEEDED : StepStatus.FAILED,
                            durationMs
                    );
                    calledTools.add(decision.tool());
                    tracing.completeObservation(toolObservation, result.summary(),
                            result.success() ? null : new IllegalStateException(result.summary()));
                } catch (RuntimeException ex) {
                    tracing.completeObservation(toolObservation, null, ex);
                    throw ex;
                }
            }

            safeObserver.onStatus(InvestigationStatus.ANALYZING);
            AgentTracingService.Observation analysisObservation = analysisObservation(trace, account, question);
            AnalysisOutcome outcome;
            try {
                outcome = intelligence.analyze(account, question, evidence, riskScore);
                tracing.completeGeneration(
                        analysisObservation,
                        String.valueOf(outcome.analysis()),
                        outcome.usage(),
                        null
                );
                totalUsage = add(totalUsage, outcome.usage());
            } catch (RuntimeException ex) {
                tracing.completeGeneration(analysisObservation, null, TokenUsage.deterministic(), ex);
                throw ex;
            }
            FinalAnalysis analysis = validate(account, outcome.analysis(), riskScore);
            InvestigationStatus terminal = analysis.action() == null
                    ? InvestigationStatus.COMPLETED_NO_ACTION
                    : InvestigationStatus.WAITING_APPROVAL;
            safeObserver.onStep("ANALYZE", "生成行动草案",
                    analysis.rootCause(), StepStatus.SUCCEEDED, 0);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            AgentTelemetry telemetry = telemetry(trace, calledTools.size(), totalUsage, durationMs);
            tracing.completeTrace(trace, analysis.summary(), null);
            return new AgentExecutionResult(
                    terminal,
                    analysis.summary(),
                    analysis.rootCauseCode(),
                    analysis.rootCause(),
                    analysis.action(),
                    null,
                    List.copyOf(calledTools),
                    List.copyOf(evidence),
                    telemetry
            );
        } catch (Exception ex) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            AgentTelemetry telemetry = telemetry(trace, calledTools.size(), totalUsage, durationMs);
            tracing.completeTrace(trace, null, ex);
            return new AgentExecutionResult(
                    InvestigationStatus.FAILED,
                    null,
                    null,
                    null,
                    null,
                    safeMessage(ex),
                    List.copyOf(calledTools),
                    List.copyOf(evidence),
                    telemetry
            );
        }
    }

    private AgentTracingService.Observation planningObservation(
            AgentTracingService.TraceSession trace,
            Account account,
            String question,
            Set<String> called
    ) {
        Map<String, Object> input = Map.of(
                "accountId", account.id(),
                "question", question,
                "completedTools", called
        );
        if ("openai".equalsIgnoreCase(properties.agent().llm().mode())) {
            return tracing.startGeneration(
                    trace,
                    "agent.planner",
                    properties.agent().llm().model(),
                    String.valueOf(input)
            );
        }
        return tracing.startObservation(trace, "agent.planner.deterministic", "chain", input);
    }

    private AgentTracingService.Observation analysisObservation(
            AgentTracingService.TraceSession trace,
            Account account,
            String question
    ) {
        Map<String, Object> input = Map.of("accountId", account.id(), "question", question);
        if ("openai".equalsIgnoreCase(properties.agent().llm().mode())) {
            return tracing.startGeneration(
                    trace,
                    "agent.analyzer",
                    properties.agent().llm().model(),
                    String.valueOf(input)
            );
        }
        return tracing.startObservation(trace, "agent.analyzer.deterministic", "chain", input);
    }

    private FinalAnalysis validate(Account account, FinalAnalysis analysis, double riskScore) {
        if (analysis == null || analysis.summary() == null || analysis.rootCauseCode() == null) {
            throw new IllegalStateException("Analysis is incomplete");
        }
        boolean healthy = analysis.rootCauseCode().equals("HEALTHY")
                || riskScore < properties.risk().candidateThreshold();
        if (healthy) {
            return new FinalAnalysis(
                    analysis.summary(),
                    "HEALTHY",
                    analysis.rootCause(),
                    null
            );
        }
        ActionDraft action = analysis.action();
        if (action == null || action.title() == null || action.ownerRole() == null || action.steps().isEmpty()) {
            throw new IllegalStateException("Action draft is incomplete");
        }
        if (!Set.of("CUSTOMER_SUCCESS", "SUPPORT", "FINANCE", "SALES").contains(action.ownerRole())) {
            throw new IllegalStateException("Unsupported owner role: " + action.ownerRole());
        }
        if (action.dueInDays() < 1 || action.dueInDays() > 14) {
            throw new IllegalStateException("Action due date is outside the allowed range");
        }
        if (!Set.of(
                "ADOPTION_GAP",
                "SUPPORT_BLOCKED",
                "BILLING_BLOCKED",
                "SPONSOR_DISENGAGED",
                "TRUE_CHURN"
        ).contains(analysis.rootCauseCode())) {
            throw new IllegalStateException("Unsupported root cause code: " + analysis.rootCauseCode());
        }
        return analysis;
    }

    private AgentTelemetry telemetry(
            AgentTracingService.TraceSession trace,
            int toolCalls,
            TokenUsage usage,
            long durationMs
    ) {
        String traceId = tracing.enabled() ? trace.traceId() : null;
        return new AgentTelemetry(
                traceId,
                tracing.enabled() ? trace.rootObservationId() : null,
                toolCalls,
                usage.inputTokens(),
                usage.outputTokens(),
                durationMs,
                tracing.enabled() ? "ENABLED" : "DISABLED",
                tracing.traceUrl(traceId)
        );
    }

    private TokenUsage add(TokenUsage left, TokenUsage right) {
        return new TokenUsage(
                left.inputTokens() + right.inputTokens(),
                left.outputTokens() + right.outputTokens(),
                right.model()
        );
    }

    private String sourceFor(String tool) {
        return switch (tool) {
            case "getAccountProfile" -> "crm";
            case "getUsageTrend" -> "product";
            case "getSupportTickets" -> "support";
            case "getBillingStatus" -> "billing";
            case "searchRenewalPlaybook" -> "knowledge";
            case "calculateRiskFactors" -> "risk";
            default -> "tool";
        };
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    public record ExperimentContext(
            String experimentId,
            String experimentName,
            String datasetId,
            String itemId,
            String expectedOutput
    ) {
    }
}
