package com.wanghongbo.renewalagent.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanghongbo.renewalagent.model.Models.EvaluationItemResult;
import com.wanghongbo.renewalagent.model.Models.EvaluationRun;
import com.wanghongbo.renewalagent.model.Models.EvaluationRunStatus;
import com.wanghongbo.renewalagent.model.Models.EvaluationSummary;
import com.wanghongbo.renewalagent.model.Models.EvaluationThresholds;
import com.wanghongbo.renewalagent.model.Models.JudgeScores;
import com.wanghongbo.renewalagent.model.Models.JudgeStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EvaluationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Double>> SCORE_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvaluationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void saveRun(EvaluationRun run) {
        jdbc.update("""
                INSERT INTO evaluation_runs(id, dataset_version, mode, judge_status, status,
                    thresholds_json, summary_json, previous_run_id, passed, engine_version,
                    langfuse_experiment_id, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.id(), run.datasetVersion(), run.mode(), run.judgeStatus().name(),
                run.status().name(), write(run.thresholds()), writeNullable(run.summary()),
                run.previousRunId(), run.passed(), run.engineVersion(), run.langfuseExperimentId(),
                java.sql.Timestamp.from(run.startedAt()),
                run.completedAt() == null ? null : java.sql.Timestamp.from(run.completedAt()));
    }

    public void finishRun(
            String id,
            EvaluationRunStatus status,
            JudgeStatus judgeStatus,
            EvaluationSummary summary,
            boolean passed
    ) {
        jdbc.update("""
                UPDATE evaluation_runs SET status = ?, judge_status = ?, summary_json = ?,
                    passed = ?, completed_at = ? WHERE id = ?
                """, status.name(), judgeStatus.name(), writeNullable(summary), passed,
                java.sql.Timestamp.from(java.time.Instant.now()), id);
    }

    public void updateJudgeStatus(String id, JudgeStatus judgeStatus) {
        jdbc.update("UPDATE evaluation_runs SET judge_status = ? WHERE id = ?", judgeStatus.name(), id);
    }

    public Optional<EvaluationRun> findRun(String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, dataset_version, mode, judge_status, status, thresholds_json,
                           summary_json, previous_run_id, passed, engine_version,
                           langfuse_experiment_id, started_at, completed_at
                    FROM evaluation_runs WHERE id = ?
                    """, this::mapRun, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<EvaluationRun> findLatest() {
        return jdbc.query("""
                SELECT id, dataset_version, mode, judge_status, status, thresholds_json,
                       summary_json, previous_run_id, passed, engine_version,
                       langfuse_experiment_id, started_at, completed_at
                FROM evaluation_runs ORDER BY started_at DESC LIMIT 1
                """, this::mapRun).stream().findFirst();
    }

    public List<EvaluationRun> findRuns(int limit) {
        return jdbc.query("""
                SELECT id, dataset_version, mode, judge_status, status, thresholds_json,
                       summary_json, previous_run_id, passed, engine_version,
                       langfuse_experiment_id, started_at, completed_at
                FROM evaluation_runs ORDER BY started_at DESC LIMIT ?
                """, this::mapRun, limit);
    }

    public Optional<EvaluationRun> findPrevious(String datasetVersion, String mode) {
        return jdbc.query("""
                SELECT id, dataset_version, mode, judge_status, status, thresholds_json,
                       summary_json, previous_run_id, passed, engine_version,
                       langfuse_experiment_id, started_at, completed_at
                FROM evaluation_runs
                WHERE dataset_version = ? AND mode = ? AND status = 'COMPLETED'
                ORDER BY started_at DESC LIMIT 1
                """, this::mapRun, datasetVersion, mode).stream().findFirst();
    }

    public void saveItem(EvaluationItemResult item) {
        jdbc.update("""
                INSERT INTO evaluation_items(id, run_id, case_id, account_id, expected_root_cause,
                    actual_root_cause, expected_owner_role, actual_owner_role, expected_tools_json,
                    actual_tools_json, expected_evidence_json, actual_evidence_json, scores_json,
                    passed, critical_violation, trace_id, root_observation_id, duration_ms,
                    input_tokens, output_tokens, judge_input_json, judge_scores_json, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, item.id(), item.runId(), item.caseId(), item.accountId(),
                item.expectedRootCause(), item.actualRootCause(), item.expectedOwnerRole(),
                item.actualOwnerRole(), write(item.expectedTools()), write(item.actualTools()),
                write(item.expectedEvidence()), write(item.actualEvidence()), write(item.scores()),
                item.passed(), item.criticalViolation(), item.traceId(), item.rootObservationId(),
                item.durationMs(), item.inputTokens(), item.outputTokens(),
                writeNullable(item.judgeInput()), writeNullable(item.judgeScores()), item.error());
    }

    public List<EvaluationItemResult> findItems(String runId) {
        return jdbc.query("""
                SELECT id, run_id, case_id, account_id, expected_root_cause, actual_root_cause,
                       expected_owner_role, actual_owner_role, expected_tools_json, actual_tools_json,
                       expected_evidence_json, actual_evidence_json, scores_json, passed,
                       critical_violation, trace_id, root_observation_id, duration_ms, input_tokens,
                       output_tokens, judge_input_json, judge_scores_json, error
                FROM evaluation_items WHERE run_id = ? ORDER BY case_id
                """, this::mapItem, runId);
    }

    public void updateItemJudge(String itemId, JudgeScores judgeScores) {
        jdbc.update(
                "UPDATE evaluation_items SET judge_scores_json = ? WHERE id = ?",
                writeNullable(judgeScores),
                itemId
        );
    }

    private EvaluationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Timestamp completed = rs.getTimestamp("completed_at");
        Object passed = rs.getObject("passed");
        return new EvaluationRun(
                rs.getString("id"),
                rs.getString("dataset_version"),
                rs.getString("mode"),
                JudgeStatus.valueOf(rs.getString("judge_status")),
                EvaluationRunStatus.valueOf(rs.getString("status")),
                read(rs.getString("thresholds_json"), EvaluationThresholds.class),
                readNullable(rs.getString("summary_json"), EvaluationSummary.class),
                rs.getString("previous_run_id"),
                passed == null ? null : rs.getBoolean("passed"),
                rs.getString("engine_version"),
                rs.getString("langfuse_experiment_id"),
                rs.getTimestamp("started_at").toInstant(),
                completed == null ? null : completed.toInstant()
        );
    }

    private EvaluationItemResult mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new EvaluationItemResult(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("case_id"),
                rs.getString("account_id"),
                rs.getString("expected_root_cause"),
                rs.getString("actual_root_cause"),
                rs.getString("expected_owner_role"),
                rs.getString("actual_owner_role"),
                readList(rs.getString("expected_tools_json")),
                readList(rs.getString("actual_tools_json")),
                readList(rs.getString("expected_evidence_json")),
                readList(rs.getString("actual_evidence_json")),
                readStringDoubleMap(rs.getString("scores_json")),
                rs.getBoolean("passed"),
                rs.getBoolean("critical_violation"),
                rs.getString("trace_id"),
                rs.getString("root_observation_id"),
                rs.getLong("duration_ms"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                readObjectMap(rs.getString("judge_input_json")),
                readNullable(rs.getString("judge_scores_json"), JudgeScores.class),
                rs.getString("error")
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize evaluation data", ex);
        }
    }

    private String writeNullable(Object value) {
        return value == null ? null : write(value);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to deserialize evaluation data", ex);
        }
    }

    private <T> T readNullable(String value, Class<T> type) {
        return value == null ? null : read(value, type);
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Double> readStringDoubleMap(String value) {
        try {
            return objectMapper.readValue(value, SCORE_MAP);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Map<String, Object> readObjectMap(String value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
