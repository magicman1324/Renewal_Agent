package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.Invoice;
import com.wanghongbo.renewalagent.model.Models.RiskFactor;
import com.wanghongbo.renewalagent.model.Models.Ticket;
import com.wanghongbo.renewalagent.model.Models.UsagePoint;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class RiskScoringService {

    private final AppProperties properties;
    private final Clock clock;

    public RiskScoringService(AppProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public ScoreResult score(Account account, List<UsagePoint> usage, List<Ticket> tickets, List<Invoice> invoices) {
        LocalDate today = LocalDate.now(clock);
        int window = properties.risk().shortWindowDays();
        LocalDate recentStart = today.minusDays(window);
        LocalDate previousStart = today.minusDays(window * 2L);

        double recentActive = average(usage.stream()
                .filter(point -> !point.date().isBefore(recentStart))
                .mapToInt(UsagePoint::activeUsers).average().orElse(0));
        double previousActive = average(usage.stream()
                .filter(point -> !point.date().isBefore(previousStart) && point.date().isBefore(recentStart))
                .mapToInt(UsagePoint::activeUsers).average().orElse(recentActive));
        double dropRatio = previousActive <= 0 ? 0 : Math.max(0, (previousActive - recentActive) / previousActive);

        double recentAdoption = usage.stream()
                .filter(point -> !point.date().isBefore(recentStart))
                .mapToDouble(UsagePoint::featureAdoption).average().orElse(0.5);

        long openP1 = tickets.stream().filter(t -> "P1".equals(t.priority())).count();
        long openP2 = tickets.stream().filter(t -> "P2".equals(t.priority())).count();
        int lowestSatisfaction = tickets.stream().mapToInt(Ticket::satisfaction).min().orElse(5);
        long overdueDays = invoices.stream()
                .filter(invoice -> !"PAID".equals(invoice.status()))
                .mapToLong(invoice -> Math.max(0, ChronoUnit.DAYS.between(invoice.dueDate(), today)))
                .max().orElse(0);

        double usageRisk = normalize(dropRatio, 0.20);
        double seatRisk = normalize(1.0 - (account.activeSeats() / (double) account.licensedSeats()), 0.60);
        double adoptionRisk = normalize(1.0 - recentAdoption, 0.45);
        double supportRisk = normalize(openP1 * 0.35 + openP2 * 0.15
                + (lowestSatisfaction <= 2 ? 0.25 : 0), 0.65);
        double billingRisk = normalize(overdueDays, 30.0);
        double engagementRisk = normalize(account.sponsorLastLoginDays(), 30.0);

        List<RiskFactor> factors = List.of(
                factor("usage", "活跃用户下降", usageRisk, 0.25,
                        String.format(Locale.ROOT, "近30天活跃用户较前30天下降 %.1f%%", dropRatio * 100)),
                factor("seats", "席位利用率不足", seatRisk, 0.15,
                        String.format(Locale.ROOT, "%d/%d 席位活跃", account.activeSeats(), account.licensedSeats())),
                factor("adoption", "关键功能采用不足", adoptionRisk, 0.15,
                        String.format(Locale.ROOT, "近30天平均功能采用率 %.1f%%", recentAdoption * 100)),
                factor("support", "工单与服务风险", supportRisk, 0.20,
                        String.format(Locale.ROOT, "%d 个P1、%d 个P2未解决工单，最低满意度 %d",
                                openP1, openP2, lowestSatisfaction)),
                factor("billing", "账务阻塞", billingRisk, 0.15,
                        overdueDays > 0 ? "发票已逾期 " + overdueDays + " 天" : "未发现逾期发票"),
                factor("engagement", "关键人活跃度", engagementRisk, 0.10,
                        "关键联系人已 " + account.sponsorLastLoginDays() + " 天未登录")
        );

        double weightedScore = factors.stream().mapToDouble(RiskFactor::contribution).sum();
        double policyFloor = policyFloor(openP1, overdueDays, account.sponsorLastLoginDays(), adoptionRisk, usageRisk, seatRisk);
        double score = Math.min(100, Math.max(weightedScore, policyFloor));
        return new ScoreResult(round(score), factors);
    }

    public String severity(double score) {
        if (score >= properties.risk().highThreshold()) {
            return "HIGH";
        }
        if (score >= properties.risk().candidateThreshold()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    public boolean isCandidate(Account account, double score) {
        return account.renewalDays() <= properties.risk().renewalWindowDays()
                && score >= properties.risk().candidateThreshold();
    }

    public double usageDrop(Account account, List<UsagePoint> usage) {
        LocalDate today = LocalDate.now(clock);
        int window = properties.risk().shortWindowDays();
        LocalDate recentStart = today.minusDays(window);
        double recent = usage.stream().filter(p -> !p.date().isBefore(recentStart))
                .mapToInt(UsagePoint::activeUsers).average().orElse(0);
        double previous = usage.stream()
                .filter(p -> !p.date().isBefore(today.minusDays(window * 2L)) && p.date().isBefore(recentStart))
                .mapToInt(UsagePoint::activeUsers).average().orElse(recent);
        return previous <= 0 ? 0 : Math.max(0, (previous - recent) / previous);
    }

    private double policyFloor(
            long openP1,
            long overdueDays,
            int sponsorDays,
            double adoptionRisk,
            double usageRisk,
            double seatRisk
    ) {
        if (openP1 >= 2) {
            return 68;
        }
        if (overdueDays >= 30) {
            return 64;
        }
        if (sponsorDays >= 30) {
            return 62;
        }
        if (adoptionRisk >= 0.9 && (usageRisk >= 0.5 || seatRisk >= 0.7)) {
            return 61;
        }
        return 0;
    }

    private RiskFactor factor(String code, String label, double normalized, double weight, String evidence) {
        return new RiskFactor(code, label, round(normalized), weight,
                round(normalized * weight * 100), evidence);
    }

    private double normalize(double value, double fullRiskAt) {
        return Math.max(0, Math.min(1, value / fullRiskAt));
    }

    private double average(double value) {
        return value;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record ScoreResult(double score, List<RiskFactor> factors) {
    }
}
