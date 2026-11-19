package com.wanghongbo.renewalagent.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.config.EvaluationProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.AgentExecutionResult;
import com.wanghongbo.renewalagent.model.Models.EvaluationCase;
import com.wanghongbo.renewalagent.model.Models.EvaluationItemResult;
import com.wanghongbo.renewalagent.model.Models.EvaluationRun;
import com.wanghongbo.renewalagent.model.Models.EvaluationRunRequest;
import com.wanghongbo.renewalagent.model.Models.EvaluationRunStatus;
import com.wanghongbo.renewalagent.model.Models.EvaluationRunView;
import com.wanghongbo.renewalagent.model.Models.EvaluationSummary;
import com.wanghongbo.renewalagent.model.Models.EvaluationThresholds;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import com.wanghongbo.renewalagent.model.Models.JudgeScores;
import com.wanghongbo.renewalagent.model.Models.JudgeStatus;
import com.wanghongbo.renewalagent.observability.LangfuseScoreClient;
import com.wanghongbo.renewalagent.observability.LangfuseDatasetClient;
import com.wanghongbo.renewalagent.service.AgentExecutionEngine;
import com.wanghongbo.renewalagent.service.AgentExecutionObserver;
import com.wanghongbo.renewalagent.service.KnowledgeService;
import com.wanghongbo.renewalagent.service.RiskScoringService;
import com.wanghongbo.renewalagent.service.ToolService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class EvaluationService {

    private static final TypeReference<List<EvaluationCase>> CASE_LIST = new TypeReference<>() {
    };

    private final EvaluationProperties properties;
    private final AppProperties appProperties;
    private final RiskScoringService riskScoring;
    private final AgentExecutionEngine executionEngine;
    private final EvaluationScoreCalculator scoreCalculator;
    private final EvaluationRepository repository;
    private final JudgeService judgeService;
    private final LangfuseScoreClient scoreClient;
    private final LangfuseDatasetClient datasetClient;
    private final EvaluationFixtureFactory fixtureFactory;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Clock clock;

    public EvaluationService(
            EvaluationProperties properties,
            AppProperties appProperties,
            RiskScoringService riskScoring,
            AgentExecutionEngine executionEngine,
            EvaluationScoreCalculator scoreCalculator,
            EvaluationRepository repository,
            JudgeService judgeService,
            LangfuseScoreClient scoreClient,
            LangfuseDatasetClient datasetClient,
            EvaluationFixtureFactory fixtureFactory,
            KnowledgeService knowledgeService,
            ObjectMapper objectMapper,
            @Qualifier("agentExecutor") Executor executor,
            Clock clock
    ) {
        this.properties = properties;
        this.appProperties = appProperties;
        this.riskScoring = riskScoring;
        this.executionEngine = executionEngine;
        this.scoreCalculator = scoreCalculator;
        this.repository = repository;
        this.judgeService = judgeService;
        this.scoreClient = scoreClient;
        this.datasetClient = datasetClient;
        this.fixtureFactory = fixtureFactory;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.clock = clock;
    }

    public EvaluationRunView createRun(EvaluationRunRequest request) {
        boolean publish = request != null && Boolean.TRUE.equals(request.publishToLangfuse());
        return createRun(request, publish, true);
    }

    private EvaluationRunView createRun(EvaluationRunRequest request, boolean publish, boolean async) {
        boolean judge = request != null && Boolean.TRUE.equals(request.judge());
        String id = "EVAL-" + UUID.randomUUID();
        String mode = agentMode();
        String previous = repository.findPrevious(properties.datasetVersion(), mode)
                .map(EvaluationRun::id)
                .orElse(null);
        Instant now = Instant.now(clock);
        EvaluationRun run = new EvaluationRun(
                id,
                properties.datasetVersion(),
                mode,
                judge ? JudgeStatus.PENDING : JudgeStatus.NOT_REQUESTED,
                EvaluationRunStatus.PENDING,
                thresholds(),
                null,
                previous,
                null,
                "renewal-agent-0.2.0",
                "EXP-" + UUID.randomUUID(),
                now,
                null
        );
        repository.saveRun(run);
        if (async) {
            executor.execute(() -> executeRun(id, judge, publish));
        }
        return view(id).orElseThrow();
    }

    public EvaluationRunView runSynchronously(boolean judge) {
        return runSynchronously(judge, false);
    }

    public EvaluationRunView runSynchronously(boolean judge, boolean publish) {
        EvaluationRunRequest request = new EvaluationRunRequest(judge, false);
        EvaluationRunView created = createRun(request, publish, false);
        executeRun(created.run().id(), judge, publish);
        return view(created.run().id()).orElseThrow();
    }

    public Optional<EvaluationRunView> view(String id) {
        return repository.findRun(id)
                .map(run -> new EvaluationRunView(run, repository.findItems(id)));
    }

    public Optional<EvaluationRunView> latest() {
        return repository.findLatest()
                .map(run -> new EvaluationRunView(run, repository.findItems(run.id())));
    }

    public List<EvaluationRun> recent(int limit) {
        return repository.findRuns(Math.max(1, Math.min(limit, 100)));
    }

    public EvaluationRunView judgeRun(String runId) {
        EvaluationRunView view = view(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown evaluation run: " + runId));
        if (view.run().status() != EvaluationRunStatus.COMPLETED) {
            throw new IllegalStateException("Evaluation run is not complete");
        }
        List<EvaluationItemResult> judgedItems = new ArrayList<>();
        for (EvaluationItemResult item : view.items()) {
            JudgeScores scores = item.judgeScores();
            if (scores == null && item.judgeInput() != null && !item.judgeInput().isEmpty()) {
                JudgeService.JudgeOutcome outcome = judgeService.evaluate(
                        stringValue(item.judgeInput().get("question")),
                        stringValue(item.judgeInput().get("rootCause")),
                        stringList(item.judgeInput().get("evidence")),
                        mapValue(item.judgeInput().get("action"))
                );
                scores = outcome.scores();
                if (scores != null) {
                    repository.updateItemJudge(item.id(), scores);
                }
            }
            judgedItems.add(withJudge(item, scores));
        }
        EvaluationFixture fixture = fixtureFactory.create();
        EvaluationSummary summary = summarize(judgedItems, fixture);
        JudgeStatus judgeStatus = judgedItems.stream().allMatch(item -> item.judgeScores() != null)
                ? JudgeStatus.COMPLETED
                : JudgeStatus.FAILED;
        repository.finishRun(runId, EvaluationRunStatus.COMPLETED, judgeStatus, summary, summary.passed());
        writeReports(runId);
        return view(runId).orElseThrow();
    }

    public List<EvaluationCase> loadCases() {
        try (var stream = new ClassPathResource("eval/golden-set-v1.json").getInputStream()) {
            return objectMapper.readValue(stream, CASE_LIST);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load golden set", ex);
        }
    }

    public EvaluationThresholds thresholds() {
        EvaluationProperties.Thresholds value = properties.thresholds();
        return new EvaluationThresholds(
                value.completionRate(),
                value.rootCauseAccuracy(),
                value.toolSelectionAccuracy(),
                value.evidenceCoverage(),
                value.actionPolicyPassRate(),
                value.maxCriticalViolations(),
                value.maxToolCalls()
        );
    }

    private void executeRun(String runId, boolean judgeRequested, boolean publishToLangfuse) {
        EvaluationRun run = repository.findRun(runId).orElseThrow();
        repository.updateJudgeStatus(runId, judgeRequested ? JudgeStatus.PENDING : JudgeStatus.NOT_REQUESTED);
        List<EvaluationItemResult> items = new ArrayList<>();
        EvaluationFixture fixture = fixtureFactory.create();
        ToolService fixtureTools = new ToolService(
                fixture.gateway(),
                knowledgeService,
                riskScoring,
                clock
        );
        String experimentDatasetId = publishToLangfuse
                ? datasetClient.sync(loadCases(), fixture)
                : fixture.version();
        try {
            for (EvaluationCase testCase : loadCases()) {
                items.add(executeCase(
                        run, testCase, fixture, fixtureTools, judgeRequested,
                        publishToLangfuse, experimentDatasetId));
            }
            EvaluationSummary summary = summarize(items, fixture);
            JudgeStatus judgeStatus = judgeRequested
                    ? (items.stream().allMatch(item -> item.judgeScores() != null)
                    ? JudgeStatus.COMPLETED
                    : JudgeStatus.FAILED)
                    : JudgeStatus.NOT_REQUESTED;
            repository.finishRun(runId, EvaluationRunStatus.COMPLETED, judgeStatus, summary, summary.passed());
            writeReports(runId);
        } catch (RuntimeException ex) {
            EvaluationSummary summary = summarize(items, fixture);
            repository.finishRun(runId, EvaluationRunStatus.FAILED, JudgeStatus.FAILED, summary, false);
        }
    }

    private EvaluationItemResult executeCase(
            EvaluationRun run,
            EvaluationCase testCase,
            EvaluationFixture fixture,
            ToolService fixtureTools,
            boolean judgeRequested,
            boolean publishToLangfuse,
            String experimentDatasetId
    ) {
        Account account = Optional.ofNullable(fixture.accounts().get(testCase.accountId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Case " + testCase.caseId() + " references missing fixture account"));
        double riskScore = riskScoring.score(
                account,
                fixture.gateway().findUsage(account.id(), fixture.referenceDate().minusDays(60)),
                fixture.gateway().findOpenTickets(account.id()),
                fixture.gateway().findInvoices(account.id())
        ).score();
        AgentExecutionResult result = executionEngine.execute(
                account,
                "EVAL-" + testCase.caseId() + "-" + run.id(),
                testCase.question(),
                riskScore,
                new AgentExecutionEngine.ExperimentContext(
                        run.langfuseExperimentId(),
                        "renewal-agent-" + run.datasetVersion(),
                        experimentDatasetId,
                        testCase.caseId(),
                        objectMapper.valueToTree(testCase).toString()
                ),
                fixtureTools,
                AgentExecutionObserver.Noop.INSTANCE
        );
        EvaluationScoreCalculator.ScoreBreakdown breakdown = scoreCalculator.score(
                testCase, result, thresholds());
        Map<String, Double> scores = new LinkedHashMap<>(breakdown.scores());
        Map<String, Object> judgeInput = new LinkedHashMap<>();
        judgeInput.put("question", testCase.question());
        judgeInput.put("rootCause", result.rootCause());
        judgeInput.put("evidence", result.evidence().stream().map(item -> item.detail()).toList());
        judgeInput.put(
                "action",
                result.action() == null ? Map.of() : objectMapper.convertValue(result.action(), Map.class)
        );
        JudgeScores judgeScores = null;
        if (judgeRequested) {
            JudgeService.JudgeOutcome judged = judgeService.evaluate(
                    testCase.question(),
                    result.rootCause(),
                    result.evidence().stream().map(item -> item.detail()).toList(),
                    result.action() == null ? Map.of() : objectMapper.convertValue(result.action(), Map.class)
            );
            judgeScores = judged.scores();
            if (judgeScores != null) {
                scores.put("judge.overall", judgeScores.overall());
            }
        }
        EvaluationItemResult item = new EvaluationItemResult(
                "EVITEM-" + UUID.randomUUID(),
                run.id(),
                testCase.caseId(),
                testCase.accountId(),
                testCase.expectedRootCause(),
                result.rootCauseCode(),
                testCase.expectedOwnerRole(),
                breakdown.actualOwner(),
                testCase.requiredTools(),
                result.calledTools(),
                testCase.requiredEvidence(),
                breakdown.actualEvidence(),
                scores,
                breakdown.passed(),
                breakdown.critical(),
                result.telemetry().traceId(),
                result.telemetry().rootObservationId(),
                result.telemetry().durationMs(),
                result.telemetry().inputTokens(),
                result.telemetry().outputTokens(),
                judgeInput,
                judgeScores,
                result.error()
        );
        repository.saveItem(item);
        if (publishToLangfuse) {
            scoreClient.publish(
                    item.traceId(),
                    item.rootObservationId(),
                    scores,
                    "Golden-set scores for " + testCase.caseId()
            );
        }
        return item;
    }

    private EvaluationSummary summarize(List<EvaluationItemResult> items, EvaluationFixture fixture) {
        int total = items.size();
        int completed = (int) items.stream()
                .filter(item -> value(item, "completion") >= 1)
                .count();
        double completionRate = ratio(completed, total);
        double rootCause = average(items, "rootCauseAccuracy");
        double toolSelection = average(items, "toolSelectionAccuracy");
        double evidence = average(items, "evidenceCoverage");
        double actionPolicy = average(items, "actionPolicy");
        int critical = (int) items.stream().filter(EvaluationItemResult::criticalViolation).count();
        int maxToolCalls = items.stream().mapToInt(item -> item.actualTools().size()).max().orElse(0);
        List<Long> durations = items.stream().map(EvaluationItemResult::durationMs).sorted().toList();
        double averageDuration = durations.stream().mapToLong(Long::longValue).average().orElse(0);
        double p95 = durations.isEmpty() ? 0 : durations.get(Math.min(durations.size() - 1,
                (int) Math.ceil(durations.size() * 0.95) - 1));
        long inputTokens = items.stream().mapToLong(EvaluationItemResult::inputTokens).sum();
        long outputTokens = items.stream().mapToLong(EvaluationItemResult::outputTokens).sum();
        double judgeAverage = items.stream()
                .map(EvaluationItemResult::judgeScores)
                .filter(value -> value != null)
                .mapToDouble(JudgeScores::overall)
                .average().orElse(0);
        EvaluationThresholds thresholds = thresholds();
        List<EvaluationCase> cases = loadCases();
        boolean dataValid = cases.size() == fixture.accounts().size()
                && cases.stream().map(EvaluationCase::caseId).distinct().count() == cases.size()
                && cases.stream().map(EvaluationCase::accountId).distinct().count() == cases.size()
                && cases.stream().allMatch(testCase -> fixture.accounts().containsKey(testCase.accountId()));
        boolean passed = dataValid
                && completionRate >= thresholds.completionRate()
                && rootCause >= thresholds.rootCauseAccuracy()
                && toolSelection >= thresholds.toolSelectionAccuracy()
                && evidence >= thresholds.evidenceCoverage()
                && actionPolicy >= thresholds.actionPolicyPassRate()
                && critical <= thresholds.maxCriticalViolations();
        return new EvaluationSummary(
                total,
                completed,
                round(completionRate),
                round(rootCause),
                round(toolSelection),
                round(evidence),
                round(actionPolicy),
                critical,
                maxToolCalls,
                fixture.version(),
                fixture.hash(),
                dataValid ? "VALID" : "BLOCKED",
                round(averageDuration),
                round(p95),
                inputTokens,
                outputTokens,
                round(judgeAverage),
                passed
        );
    }

    private double average(List<EvaluationItemResult> items, String key) {
        return items.stream().mapToDouble(item -> value(item, key)).average().orElse(0);
    }

    private double value(EvaluationItemResult item, String key) {
        return item.scores().getOrDefault(key, 0.0);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : numerator / (double) denominator;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private EvaluationItemResult withJudge(EvaluationItemResult item, JudgeScores scores) {
        if (scores == null) {
            return item;
        }
        Map<String, Double> values = new LinkedHashMap<>(item.scores());
        values.put("judge.overall", scores.overall());
        return new EvaluationItemResult(
                item.id(), item.runId(), item.caseId(), item.accountId(),
                item.expectedRootCause(), item.actualRootCause(), item.expectedOwnerRole(),
                item.actualOwnerRole(), item.expectedTools(), item.actualTools(),
                item.expectedEvidence(), item.actualEvidence(), values, item.passed(),
                item.criticalViolation(), item.traceId(), item.rootObservationId(),
                item.durationMs(), item.inputTokens(), item.outputTokens(), item.judgeInput(),
                scores, item.error()
        );
    }

    private void writeReports(String runId) {
        try {
            EvaluationRunView view = view(runId).orElseThrow();
            Path directory = Path.of(properties.outputDir());
            Files.createDirectories(directory);
            Files.writeString(
                    directory.resolve("latest.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(view)
            );
            EvaluationSummary summary = view.run().summary();
            String markdown = "# Agent Evaluation " + runId + "\n\n"
                    + "- Status: " + (summary.passed() ? "PASSED" : "FAILED") + "\n"
                    + "- Cases: " + summary.totalCases() + "\n"
                    + "- Completion: " + summary.completionRate() + "\n"
                    + "- Root cause: " + summary.rootCauseAccuracy() + "\n"
                    + "- Tool selection: " + summary.toolSelectionAccuracy() + "\n"
                    + "- Evidence coverage: " + summary.evidenceCoverage() + "\n"
                    + "- Action policy: " + summary.actionPolicyPassRate() + "\n"
                    + "- Critical violations: " + summary.criticalViolations() + "\n";
            Files.writeString(directory.resolve("latest.md"), markdown);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write evaluation report", ex);
        }
    }

    private String agentMode() {
        return "openai".equalsIgnoreCase(appProperties.agent().llm().mode())
                ? "openai:" + appProperties.agent().llm().model()
                : "deterministic";
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map
                ? objectMapper.convertValue(map, new TypeReference<>() {
                })
                : Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
