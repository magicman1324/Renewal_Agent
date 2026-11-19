package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.FollowUpResult;
import com.wanghongbo.renewalagent.model.Models.InternalTask;
import com.wanghongbo.renewalagent.model.Models.Investigation;
import com.wanghongbo.renewalagent.persistence.DatabaseService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class FollowUpService {

    private final DatabaseService database;
    private final RiskScoringService riskScoring;
    private final Clock clock;

    public FollowUpService(DatabaseService database, RiskScoringService riskScoring, Clock clock) {
        this.database = database;
        this.riskScoring = riskScoring;
        this.clock = clock;
    }

    public List<FollowUpResult> runDueFollowUps() {
        return database.findDueTasks(Instant.now(clock)).stream()
                .map(this::evaluate)
                .toList();
    }

    public FollowUpResult forceFollowUp(String taskId) {
        InternalTask task = database.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + taskId));
        return evaluate(task);
    }

    @Scheduled(cron = "0 15 * * * *", zone = "Asia/Shanghai")
    public void scheduledFollowUps() {
        runDueFollowUps();
    }

    private FollowUpResult evaluate(InternalTask task) {
        Account account = database.findAccount(task.accountId()).orElseThrow();
        Optional<Investigation> investigation = database.findInvestigation(task.investigationId());
        LocalDate usageFrom = LocalDate.now(clock).minusDays(60);
        double currentRisk = riskScoring.score(
                account,
                database.findUsage(account.id(), usageFrom),
                database.findOpenTickets(account.id()),
                database.findInvoices(account.id())
        ).score();
        double initialRisk = investigation.map(Investigation::riskScore).orElse(currentRisk);
        String result;
        if (currentRisk <= initialRisk - 5) {
            result = "IMPROVED";
        } else if (currentRisk >= initialRisk + 5) {
            result = "WORSENED";
        } else {
            result = "FLAT";
        }
        String detail = "复查风险分 %.2f，初始风险分 %.2f；使用、工单和账务状态已重新计算。"
                .formatted(currentRisk, initialRisk);
        FollowUpResult followUp = new FollowUpResult(
                "FUP-" + UUID.randomUUID(),
                task.id(),
                result,
                detail,
                Instant.now(clock)
        );
        database.addFollowUp(followUp);
        return followUp;
    }
}
