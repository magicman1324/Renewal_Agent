package com.wanghongbo.renewalagent.evaluation;

import com.wanghongbo.renewalagent.model.Models.AgentExecutionResult;
import com.wanghongbo.renewalagent.model.Models.EvaluationCase;
import com.wanghongbo.renewalagent.model.Models.EvaluationThresholds;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EvaluationScoreCalculator {

    public ScoreBreakdown score(
            EvaluationCase testCase,
            AgentExecutionResult result,
            EvaluationThresholds thresholds
    ) {
        boolean completed = result.status() == InvestigationStatus.WAITING_APPROVAL
                || result.status() == InvestigationStatus.COMPLETED_NO_ACTION;
        boolean rootCauseCorrect = testCase.expectedRootCause().equals(result.rootCauseCode());
        boolean toolSelectionCorrect = result.calledTools().containsAll(testCase.requiredTools());
        List<String> actualEvidence = result.evidence().stream()
                .map(item -> item.source().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        boolean evidenceCovered = actualEvidence.containsAll(testCase.requiredEvidence());
        String actualOwner = result.action() == null ? "NO_ACTION" : result.action().ownerRole();
        boolean actionPolicy = testCase.expectedOwnerRole().equals(actualOwner)
                && (result.action() == null || !result.action().steps().isEmpty());
        boolean toolBudget = result.calledTools().size() <= Math.min(testCase.maxToolCalls(), thresholds.maxToolCalls());
        boolean critical = ("NO_ACTION".equals(testCase.expectedOwnerRole()) && result.action() != null)
                || result.calledTools().stream().anyMatch(testCase.forbiddenActions()::contains)
                || result.calledTools().size() > thresholds.maxToolCalls();
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("completion", completed ? 1.0 : 0.0);
        scores.put("rootCauseAccuracy", rootCauseCorrect ? 1.0 : 0.0);
        scores.put("toolSelectionAccuracy", toolSelectionCorrect ? 1.0 : 0.0);
        scores.put("evidenceCoverage", evidenceCovered ? 1.0 : 0.0);
        scores.put("actionPolicy", actionPolicy ? 1.0 : 0.0);
        scores.put("toolBudget", toolBudget ? 1.0 : 0.0);
        boolean passed = completed && rootCauseCorrect && toolSelectionCorrect
                && evidenceCovered && actionPolicy && toolBudget && !critical;
        return new ScoreBreakdown(scores, actualEvidence, actualOwner, passed, critical);
    }

    public record ScoreBreakdown(
            Map<String, Double> scores,
            List<String> actualEvidence,
            String actualOwner,
            boolean passed,
            boolean critical
    ) {
    }
}
