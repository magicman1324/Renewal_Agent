package com.wanghongbo.renewalagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.Archetype;
import com.wanghongbo.renewalagent.model.Models.Invoice;
import com.wanghongbo.renewalagent.model.Models.Ticket;
import com.wanghongbo.renewalagent.model.Models.UsagePoint;
import com.wanghongbo.renewalagent.service.RiskScoringService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskScoringServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);
    private final AppProperties properties = new AppProperties(
            new AppProperties.Seed(false),
            new AppProperties.Risk(60, 70, 90, 30),
            new AppProperties.Agent(8, true, new AppProperties.Llm("deterministic", "", "", "", 30)),
            new AppProperties.Rag(false, "", ""),
            new AppProperties.Queue("inline", new AppProperties.RocketMq("", "", "", "")),
            new AppProperties.Lock("local"),
            new AppProperties.Followup(7)
    );
    private final RiskScoringService service = new RiskScoringService(properties, clock);

    @Test
    void policyFloorElevatesBillingBlockedRenewalRisk() {
        Account account = new Account(
                "ACC-X", "测试客户", "企业服务", 100, 85, "负责人",
                2, 30, Archetype.BILLING_BLOCKED, BigDecimal.valueOf(500_000)
        );
        List<UsagePoint> usage = stableUsage();
        List<Invoice> invoices = List.of(new Invoice(
                "INV-X", "ACC-X", BigDecimal.TEN, LocalDate.now(clock).minusDays(45), "OVERDUE"));

        RiskScoringService.ScoreResult result = service.score(account, usage, List.of(), invoices);

        assertThat(result.score()).isGreaterThanOrEqualTo(64);
        assertThat(result.factors()).hasSize(6);
        assertThat(service.isCandidate(account, result.score())).isTrue();
    }

    @Test
    void healthyAccountCanRemainBelowThreshold() {
        Account account = new Account(
                "ACC-H", "健康客户", "企业服务", 100, 88, "负责人",
                1, 45, Archetype.HEALTHY, BigDecimal.valueOf(100_000)
        );

        RiskScoringService.ScoreResult result = service.score(account, stableUsage(), List.of(), List.of());

        assertThat(result.score()).isLessThan(60);
        assertThat(service.isCandidate(account, result.score())).isFalse();
    }

    private List<UsagePoint> stableUsage() {
        List<UsagePoint> points = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        for (int i = 60; i >= 0; i--) {
            points.add(new UsagePoint("ACC-X", today.minusDays(i), 82, 0.76, 1500, true));
        }
        return points;
    }
}
