package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.ActionDraft;
import com.wanghongbo.renewalagent.model.Models.AgentStep;
import com.wanghongbo.renewalagent.model.Models.AgentExecutionResult;
import com.wanghongbo.renewalagent.model.Models.AgentTelemetry;
import com.wanghongbo.renewalagent.model.Models.Evidence;
import com.wanghongbo.renewalagent.model.Models.InternalTask;
import com.wanghongbo.renewalagent.model.Models.Investigation;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import com.wanghongbo.renewalagent.model.Models.InvestigationView;
import com.wanghongbo.renewalagent.model.Models.StepStatus;
import com.wanghongbo.renewalagent.model.Models.TaskStatus;
import com.wanghongbo.renewalagent.persistence.DatabaseService;
import com.wanghongbo.renewalagent.observability.LangfuseScoreClient;
import com.wanghongbo.renewalagent.observability.AgentTracingService;
import com.wanghongbo.renewalagent.service.queue.InvestigationQueue;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final DatabaseService database;
    private final AppProperties properties;
    private final AgentExecutionEngine executionEngine;
    private final InvestigationQueue investigationQueue;
    private final LangfuseScoreClient scoreClient;
    private final AgentTracingService tracing;
    private final Clock clock;

    public AgentRuntimeService(
            DatabaseService database,
            AppProperties properties,
            AgentExecutionEngine executionEngine,
            InvestigationQueue investigationQueue,
            LangfuseScoreClient scoreClient,
            AgentTracingService tracing,
            Clock clock
    ) {
        this.database = database;
        this.properties = properties;
        this.executionEngine = executionEngine;
        this.investigationQueue = investigationQueue;
        this.scoreClient = scoreClient;
        this.tracing = tracing;
        this.clock = clock;
    }

    public InvestigationView create(String accountId, String question) {
        Account account = database.findAccount(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));
        String id = "INV-" + UUID.randomUUID();
        Instant now = Instant.now(clock);
        double riskScore = database.findRiskCandidate(accountId)
                .map(candidate -> candidate.score())
                .orElse(0.0);
        Investigation investigation = new Investigation(
                id,
                account.id(),
                question == null || question.isBlank()
                        ? "为什么该客户存在续费风险，应采取什么行动？"
                        : question.trim(),
                InvestigationStatus.CREATED,
                riskScore,
                null,
                null,
                null,
                null,
                now,
                now
        );
        database.createInvestigation(investigation);
        addStep(investigation, "SYSTEM", "调查任务已创建", "等待 Agent 规划", StepStatus.SUCCEEDED, 0);
        if (properties.agent().inline()) {
            executeInvestigation(id);
        } else {
            investigationQueue.enqueue(id);
        }
        return view(id).orElseThrow();
    }

    public Optional<InvestigationView> view(String id) {
        Optional<Investigation> investigation = database.findInvestigation(id);
        if (investigation.isEmpty()) {
            return Optional.empty();
        }
        Optional<InternalTask> task = database.findTaskByInvestigation(id);
        return Optional.of(new InvestigationView(
                investigation.get(),
                database.findSteps(id),
                database.findEvidence(id),
                task.orElse(null),
                task.map(value -> database.findFollowUps(value.id())).orElse(List.of()),
                database.findTelemetry(id)
                        .map(telemetry -> new AgentTelemetry(
                                telemetry.traceId(),
                                telemetry.rootObservationId(),
                                telemetry.toolCalls(),
                                telemetry.inputTokens(),
                                telemetry.outputTokens(),
                                telemetry.durationMs(),
                                telemetry.langfuseStatus(),
                                scoreClientUrl(telemetry.traceId())
                        ))
                        .orElse(null)
        ));
    }

    public List<Investigation> recent(int limit) {
        return database.findInvestigations(limit);
    }

    public void executeInvestigation(String investigationId) {
        Optional<Investigation> loaded = database.findInvestigation(investigationId);
        if (loaded.isEmpty()) {
            return;
        }
        Investigation investigation = loaded.get();
        if (investigation.status() != InvestigationStatus.CREATED) {
            return;
        }
        Account account = database.findAccount(investigation.accountId()).orElseThrow();
        AgentExecutionResult result = executionEngine.execute(
                account,
                investigationId,
                investigation.question(),
                investigation.riskScore() == null ? 0 : investigation.riskScore(),
                null,
                new PersistingObserver(investigation)
        );
        database.updateInvestigation(
                investigationId,
                result.status(),
                investigation.riskScore(),
                result.summary(),
                result.rootCause(),
                result.action(),
                result.error()
        );
        database.saveTelemetry(investigationId, result.telemetry());
        if (result.status() == InvestigationStatus.FAILED) {
            addStep(investigation, "ERROR", "调查失败", result.error(), StepStatus.FAILED, 0);
        }
        publishOnlineScores(result);
    }

    @Transactional
    public synchronized InternalTask approve(String investigationId, String note) {
        Investigation investigation = database.findInvestigation(investigationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown investigation"));
        Optional<InternalTask> existing = database.findTaskByInvestigation(investigationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (investigation.status() != InvestigationStatus.WAITING_APPROVAL || investigation.action() == null) {
            throw new IllegalStateException("Investigation is not waiting for approval");
        }
        Instant now = Instant.now(clock);
        ActionDraft action = investigation.action();
        InternalTask task = new InternalTask(
                "TASK-" + UUID.randomUUID(),
                investigation.id(),
                investigation.accountId(),
                action.title(),
                action.ownerRole(),
                action.priority(),
                action.steps(),
                now.plus(action.dueInDays(), ChronoUnit.DAYS),
                TaskStatus.WAITING_FOLLOW_UP,
                now,
                now
        );
        InternalTask saved = database.createTaskIfAbsent(task);
        database.updateInvestigation(
                investigationId,
                InvestigationStatus.COMPLETED,
                investigation.riskScore(),
                investigation.summary(),
                investigation.rootCause(),
                investigation.action(),
                null
        );
        addStep(investigation, "APPROVAL", "用户确认行动草案",
                note == null || note.isBlank() ? "已创建内部待办：" + saved.id() : note,
                StepStatus.SUCCEEDED, 0);
        return saved;
    }

    private void addStep(
            Investigation investigation,
            String type,
            String title,
            String detail,
            StepStatus status,
            long durationMs
    ) {
        database.addStep(new AgentStep(
                0,
                investigation.id(),
                database.nextStepSequence(investigation.id()),
                type,
                title,
                detail,
                status,
                durationMs,
                Instant.now(clock)
        ));
    }

    private void publishOnlineScores(AgentExecutionResult result) {
        if (result.telemetry() == null || result.telemetry().traceId() == null) {
            return;
        }
        Map<String, Double> scores = new java.util.LinkedHashMap<>();
        scores.put("agent.completed", result.status() == InvestigationStatus.FAILED ? 0.0 : 1.0);
        scores.put("agent.evidence_count", Math.min(1.0, result.evidence().size() / 6.0));
        scores.put("agent.action_policy", result.status() == InvestigationStatus.FAILED ? 0.0 : 1.0);
        scores.put("agent.tool_calls", (double) result.calledTools().size());
        scoreClient.publish(
                result.telemetry().traceId(),
                result.telemetry().rootObservationId(),
                scores,
                "Online renewal-agent execution metrics"
        );
    }

    private String scoreClientUrl(String traceId) {
        return tracing.traceUrl(traceId);
    }

    private final class PersistingObserver implements AgentExecutionObserver {
        private final Investigation investigation;

        private PersistingObserver(Investigation investigation) {
            this.investigation = investigation;
        }

        @Override
        public void onStatus(InvestigationStatus status) {
            database.updateInvestigation(
                    investigation.id(),
                    status,
                    investigation.riskScore(),
                    null,
                    null,
                    null,
                    null
            );
        }

        @Override
        public void onStep(
                String type,
                String title,
                String detail,
                StepStatus status,
                long durationMs
        ) {
            addStep(investigation, type, title, detail, status, durationMs);
        }

        @Override
        public void onEvidence(Evidence evidence) {
            database.addEvidence(evidence);
        }
    }
}
