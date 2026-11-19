package com.wanghongbo.renewalagent.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class Models {

    private Models() {
    }

    public enum Archetype {
        HEALTHY,
        ADOPTION_GAP,
        SUPPORT_BLOCKED,
        BILLING_BLOCKED,
        SPONSOR_DISENGAGED,
        TRUE_CHURN
    }

    public enum InvestigationStatus {
        CREATED,
        PLANNING,
        EXECUTING_TOOL,
        ANALYZING,
        WAITING_APPROVAL,
        COMPLETED,
        FAILED
    }

    public enum StepStatus {
        RUNNING,
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    public enum TaskStatus {
        OPEN,
        WAITING_FOLLOW_UP,
        IMPROVED,
        FLAT,
        WORSENED,
        CANCELLED
    }

    public record Account(
            String id,
            String name,
            String industry,
            int licensedSeats,
            int activeSeats,
            String sponsorName,
            int sponsorLastLoginDays,
            int renewalDays,
            Archetype archetype,
            BigDecimal annualRevenue
    ) {
    }

    public record UsagePoint(
            String accountId,
            LocalDate date,
            int activeUsers,
            double featureAdoption,
            long usageEvents,
            boolean adminActive
    ) {
    }

    public record Ticket(
            String id,
            String accountId,
            String priority,
            String status,
            int satisfaction,
            Instant openedAt
    ) {
    }

    public record Invoice(
            String id,
            String accountId,
            BigDecimal amount,
            LocalDate dueDate,
            String status
    ) {
    }

    public record RiskFactor(
            String code,
            String label,
            double normalizedScore,
            double weight,
            double contribution,
            String evidence
    ) {
    }

    public record RiskCandidate(
            String accountId,
            String accountName,
            String industry,
            double score,
            String severity,
            List<RiskFactor> factors,
            Instant scannedAt,
            int renewalDays,
            BigDecimal annualRevenue
    ) {
    }

    public record ToolResult(
            String toolName,
            boolean success,
            String summary,
            Map<String, Object> data
    ) {
        public static ToolResult failure(String toolName, String summary) {
            return new ToolResult(toolName, false, summary, Map.of());
        }
    }

    public record ToolDefinition(
            String name,
            String description,
            String permission,
            int timeoutSeconds,
            boolean idempotent,
            Map<String, Object> inputSchema
    ) {
    }

    public record Decision(
            String action,
            String tool,
            Map<String, Object> arguments,
            String reason
    ) {
        public static Decision finish(String reason) {
            return new Decision("FINISH", null, Map.of(), reason);
        }
    }

    public record FinalAnalysis(
            String summary,
            String rootCause,
            ActionDraft action
    ) {
    }

    public record ActionDraft(
            String title,
            String ownerRole,
            String priority,
            String summary,
            String rationale,
            List<String> steps,
            List<String> evidenceLabels,
            int dueInDays
    ) {
    }

    public record Investigation(
            String id,
            String accountId,
            String question,
            InvestigationStatus status,
            Double riskScore,
            String summary,
            String rootCause,
            ActionDraft action,
            String error,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record AgentStep(
            long id,
            String investigationId,
            int sequence,
            String type,
            String title,
            String detail,
            StepStatus status,
            long durationMs,
            Instant createdAt
    ) {
    }

    public record Evidence(
            String id,
            String investigationId,
            String source,
            String label,
            String detail,
            Map<String, Object> data,
            Instant createdAt
    ) {
    }

    public record InternalTask(
            String id,
            String investigationId,
            String accountId,
            String title,
            String ownerRole,
            String priority,
            List<String> steps,
            Instant dueAt,
            TaskStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record FollowUpResult(
            String id,
            String taskId,
            String result,
            String detail,
            Instant createdAt
    ) {
    }

    public record InvestigationView(
            Investigation investigation,
            List<AgentStep> steps,
            List<Evidence> evidence,
            InternalTask task,
            List<FollowUpResult> followUps
    ) {
    }

    public record CreateInvestigationRequest(String question) {
    }

    public record ApproveTaskRequest(String note) {
    }

    public record RiskScanResponse(int candidateCount, Instant scannedAt) {
    }

    public record SummaryResponse(
            int accountCount,
            int candidateCount,
            int highRiskCount,
            int openTaskCount
    ) {
    }
}
