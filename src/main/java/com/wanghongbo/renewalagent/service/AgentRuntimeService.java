package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.ActionDraft;
import com.wanghongbo.renewalagent.model.Models.AgentStep;
import com.wanghongbo.renewalagent.model.Models.Decision;
import com.wanghongbo.renewalagent.model.Models.Evidence;
import com.wanghongbo.renewalagent.model.Models.FinalAnalysis;
import com.wanghongbo.renewalagent.model.Models.InternalTask;
import com.wanghongbo.renewalagent.model.Models.Investigation;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import com.wanghongbo.renewalagent.model.Models.InvestigationView;
import com.wanghongbo.renewalagent.model.Models.StepStatus;
import com.wanghongbo.renewalagent.model.Models.TaskStatus;
import com.wanghongbo.renewalagent.model.Models.ToolDefinition;
import com.wanghongbo.renewalagent.model.Models.ToolResult;
import com.wanghongbo.renewalagent.persistence.DatabaseService;
import com.wanghongbo.renewalagent.service.queue.InvestigationQueue;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
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
    private final ToolService tools;
    private final AgentIntelligenceService intelligence;
    private final InvestigationQueue investigationQueue;
    private final Clock clock;

    public AgentRuntimeService(
            DatabaseService database,
            AppProperties properties,
            ToolService tools,
            AgentIntelligenceService intelligence,
            InvestigationQueue investigationQueue,
            Clock clock
    ) {
        this.database = database;
        this.properties = properties;
        this.tools = tools;
        this.intelligence = intelligence;
        this.investigationQueue = investigationQueue;
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
                task.map(value -> database.findFollowUps(value.id())).orElse(List.of())
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
        try {
            update(investigation, InvestigationStatus.PLANNING, null, null, null);
            addStep(investigation, "PLAN", "开始规划调查", "加载只读工具与当前客户上下文", StepStatus.SUCCEEDED, 0);

            Set<String> calledTools = new HashSet<>();
            List<ToolDefinition> definitions = tools.definitions();
            int maxCalls = Math.min(properties.agent().maxToolCalls(), definitions.size());
            for (int i = 0; i < maxCalls; i++) {
                List<Evidence> evidence = database.findEvidence(investigationId);
                Decision decision = intelligence.nextDecision(
                        account, investigation.question(), definitions, evidence, calledTools);
                if (!"CALL_TOOL".equalsIgnoreCase(decision.action()) || decision.tool() == null) {
                    break;
                }
                if (!definitions.stream().anyMatch(tool -> tool.name().equals(decision.tool()))) {
                    addStep(investigation, "POLICY", "工具调用被拒绝",
                            "未注册工具：" + decision.tool(), StepStatus.FAILED, 0);
                    break;
                }
                if (!calledTools.add(decision.tool())) {
                    addStep(investigation, "POLICY", "跳过重复工具",
                            decision.tool(), StepStatus.SKIPPED, 0);
                    break;
                }

                update(investigation, InvestigationStatus.EXECUTING_TOOL, null, null, null);
                long started = System.nanoTime();
                ToolResult result = tools.execute(decision.tool(), account,
                        decision.arguments() == null ? Map.of() : decision.arguments());
                long durationMs = (System.nanoTime() - started) / 1_000_000;
                String source = sourceFor(decision.tool());
                String label = result.summary();
                database.addEvidence(new Evidence(
                        "EVD-" + UUID.randomUUID(),
                        investigationId,
                        source,
                        label,
                        result.summary(),
                        result.data(),
                        Instant.now(clock)
                ));
                addStep(
                        investigation,
                        "TOOL",
                        decision.tool(),
                        decision.reason() + " | " + result.summary(),
                        result.success() ? StepStatus.SUCCEEDED : StepStatus.FAILED,
                        durationMs
                );
            }

            update(investigation, InvestigationStatus.ANALYZING, null, null, null);
            List<Evidence> evidence = database.findEvidence(investigationId);
            FinalAnalysis analysis = intelligence.analyze(account, investigation.question(), evidence);
            validateAnalysis(analysis);
            update(investigation, InvestigationStatus.WAITING_APPROVAL, analysis.summary(),
                    analysis.rootCause(), analysis.action());
            addStep(investigation, "ANALYZE", "生成行动草案",
                    analysis.rootCause(), StepStatus.SUCCEEDED, 0);
        } catch (Exception ex) {
            log.error("Investigation {} failed", investigationId, ex);
            database.updateInvestigation(
                    investigationId,
                    InvestigationStatus.FAILED,
                    investigation.riskScore(),
                    investigation.summary(),
                    investigation.rootCause(),
                    investigation.action(),
                    safeMessage(ex)
            );
            addStep(investigation, "ERROR", "调查失败", safeMessage(ex), StepStatus.FAILED, 0);
        }
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

    private void update(
            Investigation investigation,
            InvestigationStatus status,
            String summary,
            String rootCause,
            ActionDraft action
    ) {
        database.updateInvestigation(
                investigation.id(), status, investigation.riskScore(), summary, rootCause, action, null);
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

    private void validateAnalysis(FinalAnalysis analysis) {
        if (analysis == null || analysis.summary() == null || analysis.action() == null) {
            throw new IllegalStateException("Analysis is incomplete");
        }
        ActionDraft action = analysis.action();
        if (action.title() == null || action.ownerRole() == null || action.steps().isEmpty()) {
            throw new IllegalStateException("Action draft is incomplete");
        }
        if (!Set.of("CUSTOMER_SUCCESS", "SUPPORT", "FINANCE", "SALES").contains(action.ownerRole())) {
            throw new IllegalStateException("Unsupported owner role: " + action.ownerRole());
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
