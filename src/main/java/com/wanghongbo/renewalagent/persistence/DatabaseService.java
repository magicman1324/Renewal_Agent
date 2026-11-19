package com.wanghongbo.renewalagent.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.ActionDraft;
import com.wanghongbo.renewalagent.model.Models.AgentStep;
import com.wanghongbo.renewalagent.model.Models.Archetype;
import com.wanghongbo.renewalagent.model.Models.Evidence;
import com.wanghongbo.renewalagent.model.Models.FollowUpResult;
import com.wanghongbo.renewalagent.model.Models.InternalTask;
import com.wanghongbo.renewalagent.model.Models.Investigation;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import com.wanghongbo.renewalagent.model.Models.Invoice;
import com.wanghongbo.renewalagent.model.Models.RiskCandidate;
import com.wanghongbo.renewalagent.model.Models.RiskFactor;
import com.wanghongbo.renewalagent.model.Models.StepStatus;
import com.wanghongbo.renewalagent.model.Models.TaskStatus;
import com.wanghongbo.renewalagent.model.Models.Ticket;
import com.wanghongbo.renewalagent.model.Models.UsagePoint;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseService {

    private static final TypeReference<List<RiskFactor>> FACTOR_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DatabaseService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public int accountCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
        return count == null ? 0 : count;
    }

    public List<Account> findAllAccounts() {
        return jdbc.query("""
                SELECT id, name, industry, licensed_seats, active_seats, sponsor_name,
                       sponsor_last_login_days, renewal_days, archetype, annual_revenue
                FROM accounts ORDER BY id
                """, this::mapAccount);
    }

    public Optional<Account> findAccount(String accountId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, name, industry, licensed_seats, active_seats, sponsor_name,
                           sponsor_last_login_days, renewal_days, archetype, annual_revenue
                    FROM accounts WHERE id = ?
                    """, this::mapAccount, accountId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<UsagePoint> findUsage(String accountId, LocalDate from) {
        return jdbc.query("""
                SELECT account_id, usage_date, active_users, feature_adoption, usage_events, admin_active
                FROM usage_daily WHERE account_id = ? AND usage_date >= ? ORDER BY usage_date
                """, (rs, rowNum) -> new UsagePoint(
                rs.getString("account_id"),
                rs.getDate("usage_date").toLocalDate(),
                rs.getInt("active_users"),
                rs.getDouble("feature_adoption"),
                rs.getLong("usage_events"),
                rs.getBoolean("admin_active")
        ), accountId, from);
    }

    public List<Ticket> findOpenTickets(String accountId) {
        return jdbc.query("""
                SELECT id, account_id, priority, status, satisfaction, opened_at
                FROM tickets WHERE account_id = ? AND status <> 'RESOLVED' ORDER BY opened_at DESC
                """, (rs, rowNum) -> new Ticket(
                rs.getString("id"),
                rs.getString("account_id"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getInt("satisfaction"),
                rs.getTimestamp("opened_at").toInstant()
        ), accountId);
    }

    public List<Invoice> findInvoices(String accountId) {
        return jdbc.query("""
                SELECT id, account_id, amount, due_date, status
                FROM invoices WHERE account_id = ? ORDER BY due_date DESC
                """, (rs, rowNum) -> new Invoice(
                rs.getString("id"),
                rs.getString("account_id"),
                rs.getBigDecimal("amount"),
                rs.getDate("due_date").toLocalDate(),
                rs.getString("status")
        ), accountId);
    }

    public void saveRiskCandidate(RiskCandidate candidate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_candidates WHERE account_id = ?",
                Integer.class,
                candidate.accountId()
        );
        if (count != null && count > 0) {
            jdbc.update("""
                    UPDATE risk_candidates SET score = ?, severity = ?, factors_json = ?, scanned_at = ?
                    WHERE account_id = ?
                    """, candidate.score(), candidate.severity(), write(candidate.factors()),
                    java.sql.Timestamp.from(candidate.scannedAt()), candidate.accountId());
            return;
        }
        jdbc.update("""
                INSERT INTO risk_candidates(account_id, score, severity, factors_json, scanned_at)
                VALUES (?, ?, ?, ?, ?)
                """, candidate.accountId(), candidate.score(), candidate.severity(),
                write(candidate.factors()), java.sql.Timestamp.from(candidate.scannedAt()));
    }

    public void deleteRiskCandidate(String accountId) {
        jdbc.update("DELETE FROM risk_candidates WHERE account_id = ?", accountId);
    }

    public List<RiskCandidate> findRiskCandidates() {
        return jdbc.query("""
                SELECT r.account_id, a.name, a.industry, r.score, r.severity, r.factors_json,
                       r.scanned_at, a.renewal_days, a.annual_revenue
                FROM risk_candidates r JOIN accounts a ON a.id = r.account_id
                ORDER BY r.score DESC, a.annual_revenue DESC
                """, (rs, rowNum) -> new RiskCandidate(
                rs.getString("account_id"),
                rs.getString("name"),
                rs.getString("industry"),
                rs.getDouble("score"),
                rs.getString("severity"),
                readFactors(rs.getString("factors_json")),
                rs.getTimestamp("scanned_at").toInstant(),
                rs.getInt("renewal_days"),
                rs.getBigDecimal("annual_revenue")
        ));
    }

    public Optional<RiskCandidate> findRiskCandidate(String accountId) {
        return jdbc.query("""
                SELECT r.account_id, a.name, a.industry, r.score, r.severity, r.factors_json,
                       r.scanned_at, a.renewal_days, a.annual_revenue
                FROM risk_candidates r JOIN accounts a ON a.id = r.account_id
                WHERE r.account_id = ?
                """, (rs, rowNum) -> new RiskCandidate(
                rs.getString("account_id"),
                rs.getString("name"),
                rs.getString("industry"),
                rs.getDouble("score"),
                rs.getString("severity"),
                readFactors(rs.getString("factors_json")),
                rs.getTimestamp("scanned_at").toInstant(),
                rs.getInt("renewal_days"),
                rs.getBigDecimal("annual_revenue")
        ), accountId).stream().findFirst();
    }

    public void createInvestigation(Investigation investigation) {
        jdbc.update("""
                INSERT INTO investigations(id, account_id, question, status, risk_score, summary,
                    root_cause, action_json, error, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, investigation.id(), investigation.accountId(), investigation.question(),
                investigation.status().name(), investigation.riskScore(), investigation.summary(),
                investigation.rootCause(), writeNullable(investigation.action()), investigation.error(),
                java.sql.Timestamp.from(investigation.createdAt()),
                java.sql.Timestamp.from(investigation.updatedAt()));
    }

    public void updateInvestigation(
            String id,
            InvestigationStatus status,
            Double riskScore,
            String summary,
            String rootCause,
            ActionDraft action,
            String error
    ) {
        jdbc.update("""
                UPDATE investigations SET status = ?, risk_score = ?, summary = ?, root_cause = ?,
                    action_json = ?, error = ?, updated_at = ? WHERE id = ?
                """, status.name(), riskScore, summary, rootCause, writeNullable(action), error,
                java.sql.Timestamp.from(Instant.now()), id);
    }

    public Optional<Investigation> findInvestigation(String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, account_id, question, status, risk_score, summary, root_cause,
                           action_json, error, created_at, updated_at
                    FROM investigations WHERE id = ?
                    """, this::mapInvestigation, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<Investigation> findInvestigations(int limit) {
        return jdbc.query("""
                SELECT id, account_id, question, status, risk_score, summary, root_cause,
                       action_json, error, created_at, updated_at
                FROM investigations ORDER BY created_at DESC LIMIT ?
                """, this::mapInvestigation, limit);
    }

    public int nextStepSequence(String investigationId) {
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq_no), 0) + 1 FROM agent_steps WHERE investigation_id = ?",
                Integer.class, investigationId);
        return seq == null ? 1 : seq;
    }

    public void addStep(AgentStep step) {
        jdbc.update("""
                INSERT INTO agent_steps(investigation_id, seq_no, step_type, title, detail, status,
                    duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, step.investigationId(), step.sequence(), step.type(), step.title(), step.detail(),
                step.status().name(), step.durationMs(), java.sql.Timestamp.from(step.createdAt()));
    }

    public List<AgentStep> findSteps(String investigationId) {
        return jdbc.query("""
                SELECT id, investigation_id, seq_no, step_type, title, detail, status, duration_ms, created_at
                FROM agent_steps WHERE investigation_id = ? ORDER BY seq_no, id
                """, (rs, rowNum) -> new AgentStep(
                rs.getLong("id"),
                rs.getString("investigation_id"),
                rs.getInt("seq_no"),
                rs.getString("step_type"),
                rs.getString("title"),
                rs.getString("detail"),
                StepStatus.valueOf(rs.getString("status")),
                rs.getLong("duration_ms"),
                rs.getTimestamp("created_at").toInstant()
        ), investigationId);
    }

    public void addEvidence(Evidence evidence) {
        jdbc.update("""
                INSERT INTO evidence(id, investigation_id, source, label, detail, data_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, evidence.id(), evidence.investigationId(), evidence.source(), evidence.label(),
                evidence.detail(), write(evidence.data()), java.sql.Timestamp.from(evidence.createdAt()));
    }

    public List<Evidence> findEvidence(String investigationId) {
        return jdbc.query("""
                SELECT id, investigation_id, source, label, detail, data_json, created_at
                FROM evidence WHERE investigation_id = ? ORDER BY created_at, id
                """, (rs, rowNum) -> new Evidence(
                rs.getString("id"),
                rs.getString("investigation_id"),
                rs.getString("source"),
                rs.getString("label"),
                rs.getString("detail"),
                readMap(rs.getString("data_json")),
                rs.getTimestamp("created_at").toInstant()
        ), investigationId);
    }

    public synchronized InternalTask createTaskIfAbsent(InternalTask task) {
        Optional<InternalTask> existing = findTaskByInvestigation(task.investigationId());
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.update("""
                INSERT INTO internal_tasks(id, investigation_id, account_id, title, owner_role,
                    priority, steps_json, due_at, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.investigationId(), task.accountId(), task.title(), task.ownerRole(),
                task.priority(), write(task.steps()), java.sql.Timestamp.from(task.dueAt()),
                task.status().name(), java.sql.Timestamp.from(task.createdAt()),
                java.sql.Timestamp.from(task.updatedAt()));
        return task;
    }

    public Optional<InternalTask> findTaskByInvestigation(String investigationId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, investigation_id, account_id, title, owner_role, priority,
                           steps_json, due_at, status, created_at, updated_at
                    FROM internal_tasks WHERE investigation_id = ?
                    """, this::mapTask, investigationId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<InternalTask> findTask(String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, investigation_id, account_id, title, owner_role, priority,
                           steps_json, due_at, status, created_at, updated_at
                    FROM internal_tasks WHERE id = ?
                    """, this::mapTask, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<InternalTask> findOpenTasks() {
        return jdbc.query("""
                SELECT id, investigation_id, account_id, title, owner_role, priority,
                       steps_json, due_at, status, created_at, updated_at
                FROM internal_tasks WHERE status IN ('OPEN', 'WAITING_FOLLOW_UP')
                ORDER BY due_at, created_at DESC
                """, this::mapTask);
    }

    public List<InternalTask> findDueTasks(Instant now) {
        return jdbc.query("""
                SELECT id, investigation_id, account_id, title, owner_role, priority,
                       steps_json, due_at, status, created_at, updated_at
                FROM internal_tasks WHERE status = 'WAITING_FOLLOW_UP' AND due_at <= ?
                ORDER BY due_at
                """, this::mapTask, java.sql.Timestamp.from(now));
    }

    public void addFollowUp(FollowUpResult result) {
        jdbc.update("""
                INSERT INTO followups(id, task_id, result, detail, created_at) VALUES (?, ?, ?, ?, ?)
                """, result.id(), result.taskId(), result.result(), result.detail(),
                java.sql.Timestamp.from(result.createdAt()));
        jdbc.update("""
                UPDATE internal_tasks SET status = ?, updated_at = ? WHERE id = ?
                """, result.result(), java.sql.Timestamp.from(result.createdAt()), result.taskId());
    }

    public List<FollowUpResult> findFollowUps(String taskId) {
        return jdbc.query("""
                SELECT id, task_id, result, detail, created_at FROM followups
                WHERE task_id = ? ORDER BY created_at DESC
                """, (rs, rowNum) -> new FollowUpResult(
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("result"),
                rs.getString("detail"),
                rs.getTimestamp("created_at").toInstant()
        ), taskId);
    }

    public int countOpenTasks() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM internal_tasks WHERE status IN ('OPEN', 'WAITING_FOLLOW_UP')",
                Integer.class);
        return count == null ? 0 : count;
    }

    public int countHighRisk() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_candidates WHERE severity = 'HIGH'", Integer.class);
        return count == null ? 0 : count;
    }

    private Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("industry"),
                rs.getInt("licensed_seats"),
                rs.getInt("active_seats"),
                rs.getString("sponsor_name"),
                rs.getInt("sponsor_last_login_days"),
                rs.getInt("renewal_days"),
                Archetype.valueOf(rs.getString("archetype")),
                rs.getBigDecimal("annual_revenue")
        );
    }

    private Investigation mapInvestigation(ResultSet rs, int rowNum) throws SQLException {
        Object scoreValue = rs.getObject("risk_score");
        return new Investigation(
                rs.getString("id"),
                rs.getString("account_id"),
                rs.getString("question"),
                InvestigationStatus.valueOf(rs.getString("status")),
                scoreValue == null ? null : ((Number) scoreValue).doubleValue(),
                rs.getString("summary"),
                rs.getString("root_cause"),
                readAction(rs.getString("action_json")),
                rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private InternalTask mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new InternalTask(
                rs.getString("id"),
                rs.getString("investigation_id"),
                rs.getString("account_id"),
                rs.getString("title"),
                rs.getString("owner_role"),
                rs.getString("priority"),
                readStrings(rs.getString("steps_json")),
                rs.getTimestamp("due_at").toInstant(),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private List<RiskFactor> readFactors(String value) {
        try {
            return objectMapper.readValue(value, FACTOR_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> readStrings(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private ActionDraft readAction(String value) {
        try {
            return value == null ? null : objectMapper.readValue(value, ActionDraft.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize database value", ex);
        }
    }

    private String writeNullable(Object value) {
        return value == null ? null : write(value);
    }
}
