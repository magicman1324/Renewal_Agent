package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.RiskCandidate;
import com.wanghongbo.renewalagent.model.Models.RiskScanResponse;
import com.wanghongbo.renewalagent.persistence.DatabaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RiskScanService {

    private final DatabaseService database;
    private final RiskScoringService riskScoring;
    private final Clock clock;
    private final ScanLockService scanLock;

    public RiskScanService(
            DatabaseService database,
            RiskScoringService riskScoring,
            Clock clock,
            ScanLockService scanLock
    ) {
        this.database = database;
        this.riskScoring = riskScoring;
        this.clock = clock;
        this.scanLock = scanLock;
    }

    public RiskScanResponse scan() {
        try (ScanLockService.LockHandle ignored = scanLock.acquire("scheduled-risk-scan", Duration.ofSeconds(30))) {
            Instant scannedAt = Instant.now(clock);
            LocalDate usageFrom = LocalDate.now(clock).minusDays(60);
            List<RiskCandidate> candidates = new ArrayList<>();
            for (Account account : database.findAllAccounts()) {
                RiskScoringService.ScoreResult result = riskScoring.score(
                        account,
                        database.findUsage(account.id(), usageFrom),
                        database.findOpenTickets(account.id()),
                        database.findInvoices(account.id())
                );
                if (riskScoring.isCandidate(account, result.score())) {
                    RiskCandidate candidate = new RiskCandidate(
                            account.id(), account.name(), account.industry(), result.score(),
                            riskScoring.severity(result.score()), result.factors(), scannedAt,
                            account.renewalDays(), account.annualRevenue()
                    );
                    database.saveRiskCandidate(candidate);
                    candidates.add(candidate);
                } else {
                    database.deleteRiskCandidate(account.id());
                }
            }
            return new RiskScanResponse(candidates.size(), scannedAt);
        }
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
    public void scheduledScan() {
        scan();
    }
}
