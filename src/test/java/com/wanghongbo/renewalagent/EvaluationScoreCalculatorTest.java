package com.wanghongbo.renewalagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanghongbo.renewalagent.evaluation.EvaluationScoreCalculator;
import com.wanghongbo.renewalagent.model.Models.ActionDraft;
import com.wanghongbo.renewalagent.model.Models.AgentExecutionResult;
import com.wanghongbo.renewalagent.model.Models.AgentTelemetry;
import com.wanghongbo.renewalagent.model.Models.EvaluationCase;
import com.wanghongbo.renewalagent.model.Models.EvaluationThresholds;
import com.wanghongbo.renewalagent.model.Models.Evidence;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationScoreCalculatorTest {

    private final EvaluationScoreCalculator calculator = new EvaluationScoreCalculator();
    private final EvaluationThresholds thresholds = new EvaluationThresholds(0.95, 0.8, 0.9, 0.9, 1, 0, 8);

    @Test
    void correctToolEvidenceAndActionPass() {
        EvaluationCase testCase = riskCase("SUPPORT_BLOCKED", "SUPPORT");
        AgentExecutionResult result = result(
                InvestigationStatus.WAITING_APPROVAL,
                "SUPPORT_BLOCKED",
                "SUPPORT",
                List.of("getAccountProfile", "getUsageTrend", "getSupportTickets",
                        "getBillingStatus", "searchRenewalPlaybook", "calculateRiskFactors"),
                List.of("crm", "product", "support", "billing", "knowledge", "risk")
        );

        var score = calculator.score(testCase, result, thresholds);

        assertThat(score.passed()).isTrue();
        assertThat(score.critical()).isFalse();
    }

    @Test
    void wrongCauseMissingEvidenceAndHealthyMutationFail() {
        EvaluationCase healthy = new EvaluationCase(
                "healthy", "ACC-001", "question", "HEALTHY", "NO_ACTION",
                List.of("getAccountProfile"), List.of("crm"), List.of(), 8
        );
        AgentExecutionResult bad = result(
                InvestigationStatus.WAITING_APPROVAL,
                "TRUE_CHURN",
                "CUSTOMER_SUCCESS",
                List.of("getAccountProfile"),
                List.of("crm")
        );

        var score = calculator.score(healthy, bad, thresholds);

        assertThat(score.passed()).isFalse();
        assertThat(score.critical()).isTrue();
        assertThat(score.scores().get("rootCauseAccuracy")).isZero();
        assertThat(score.scores().get("actionPolicy")).isZero();
    }

    private EvaluationCase riskCase(String cause, String owner) {
        return new EvaluationCase(
                "case", "ACC-003", "question", cause, owner,
                List.of("getAccountProfile", "getUsageTrend", "getSupportTickets",
                        "getBillingStatus", "searchRenewalPlaybook", "calculateRiskFactors"),
                List.of("crm", "product", "support", "billing", "knowledge", "risk"),
                List.of("EXTERNAL_EMAIL"), 8
        );
    }

    private AgentExecutionResult result(
            InvestigationStatus status,
            String cause,
            String owner,
            List<String> tools,
            List<String> sources
    ) {
        List<Evidence> evidence = sources.stream()
                .map(source -> new Evidence(
                        "E-" + source, "I", source, "evidence", "evidence", Map.of(), Instant.now()))
                .toList();
        ActionDraft action = "NO_ACTION".equals(owner)
                ? null
                : new ActionDraft("action", owner, "HIGH", "summary", "reason",
                List.of("step"), sources, 7);
        return new AgentExecutionResult(
                status, "summary", cause, "root cause", action, null, tools, evidence,
                new AgentTelemetry("trace", "observation", tools.size(), 10, 20, 30,
                        "DISABLED", null)
        );
    }
}
