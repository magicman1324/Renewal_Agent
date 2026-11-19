package com.wanghongbo.renewalagent.evaluation;

import com.wanghongbo.renewalagent.config.EvaluationProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.Archetype;
import com.wanghongbo.renewalagent.model.Models.Invoice;
import com.wanghongbo.renewalagent.model.Models.Ticket;
import com.wanghongbo.renewalagent.model.Models.UsagePoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.springframework.stereotype.Service;

@Service
public class EvaluationFixtureFactory {

    private static final int ACCOUNT_COUNT = 36;
    private static final int HISTORY_DAYS = 61;
    private static final String[] NAMES = {
            "北辰协作", "云帆科技", "山海数据", "远川软件", "星桥网络", "青禾智能",
            "知行云", "启明信息", "万象零售", "极光医疗", "澄明物流", "东岸制造",
            "鸣川能源", "博雅教育", "联屿金融", "拓维咨询", "简一设计", "森罗传媒",
            "九章实验室", "凌波安全", "云雀电商", "恒川汽车", "灵犀游戏", "远望地产",
            "盛景旅游", "海岳通信", "合一工业", "瑞泽生物", "知微财税", "木棉消费",
            "星河专车", "长桥证券", "拾光影像", "南风餐饮", "新元硬件", "澜图咨询"
    };
    private static final String[] INDUSTRIES = {
            "企业服务", "医疗健康", "零售消费", "物流供应链", "制造业", "金融科技"
    };

    private final EvaluationProperties properties;
    private final Clock clock;

    public EvaluationFixtureFactory(EvaluationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public EvaluationFixture create() {
        LocalDate today = LocalDate.now(clock);
        Random random = new Random(0x5EED_2026L);
        Map<String, Account> accounts = new LinkedHashMap<>();
        Map<String, List<UsagePoint>> usage = new LinkedHashMap<>();
        Map<String, List<Ticket>> tickets = new LinkedHashMap<>();
        Map<String, List<Invoice>> invoices = new LinkedHashMap<>();
        StringBuilder canonical = new StringBuilder();

        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            String id = String.format(Locale.ROOT, "ACC-%03d", i + 1);
            Archetype archetype = Archetype.values()[i % Archetype.values().length];
            int licensedSeats = 40 + (i % 8) * 15;
            int activeSeats = switch (archetype) {
                case HEALTHY -> (int) (licensedSeats * 0.84);
                case ADOPTION_GAP -> (int) (licensedSeats * 0.42);
                case SUPPORT_BLOCKED -> (int) (licensedSeats * 0.68);
                case BILLING_BLOCKED -> (int) (licensedSeats * 0.81);
                case SPONSOR_DISENGAGED -> (int) (licensedSeats * 0.72);
                case TRUE_CHURN -> (int) (licensedSeats * 0.34);
            };
            int sponsorDays = switch (archetype) {
                case SPONSOR_DISENGAGED -> 38;
                case TRUE_CHURN -> 21;
                case ADOPTION_GAP -> 18;
                default -> 1 + i % 5;
            };
            Account account = new Account(
                    id,
                    NAMES[i] + "有限公司",
                    INDUSTRIES[i % INDUSTRIES.length],
                    licensedSeats,
                    activeSeats,
                    "评测联系人-" + (i + 1),
                    sponsorDays,
                    12 + (i * 13) % 76,
                    archetype,
                    BigDecimal.valueOf(240_000L + (i * 37_000L))
            );
            accounts.put(id, account);
            canonical.append(id).append('|').append(archetype).append('|')
                    .append(licensedSeats).append('|').append(activeSeats).append('\n');

            List<UsagePoint> accountUsage = new ArrayList<>();
            for (int day = HISTORY_DAYS - 1; day >= 0; day--) {
                double progress = (HISTORY_DAYS - day) / (double) HISTORY_DAYS;
                double utilization = switch (archetype) {
                    case HEALTHY -> 0.84 + noise(random, 0.03);
                    case ADOPTION_GAP -> 0.72 - progress * 0.30;
                    case SUPPORT_BLOCKED -> 0.80 - progress * 0.22;
                    case BILLING_BLOCKED -> 0.81 + noise(random, 0.02);
                    case SPONSOR_DISENGAGED -> 0.72 - progress * 0.05;
                    case TRUE_CHURN -> 0.95 - progress * 0.76;
                };
                double adoption = switch (archetype) {
                    case HEALTHY -> 0.75 + noise(random, 0.03);
                    case ADOPTION_GAP -> 0.70 - progress * 0.50;
                    case SUPPORT_BLOCKED -> 0.68 - progress * 0.22;
                    case BILLING_BLOCKED -> 0.70 + noise(random, 0.02);
                    case SPONSOR_DISENGAGED -> 0.60 - progress * 0.07;
                    case TRUE_CHURN -> 0.75 - progress * 0.62;
                };
                int activeUsers = Math.max(3, (int) Math.round(licensedSeats * utilization));
                boolean adminActive = !(archetype == Archetype.SPONSOR_DISENGAGED && day < 30)
                        && !(archetype == Archetype.TRUE_CHURN && day < 20)
                        && !(archetype == Archetype.ADOPTION_GAP && day < 14);
                UsagePoint point = new UsagePoint(
                        id,
                        today.minusDays(day),
                        activeUsers,
                        clamp(adoption),
                        Math.max(20, Math.round(activeUsers * (16 + random.nextDouble() * 12))),
                        adminActive
                );
                accountUsage.add(point);
                canonical.append(day).append(':').append(activeUsers).append(':')
                        .append(String.format(Locale.ROOT, "%.4f", point.featureAdoption()))
                        .append(':').append(adminActive).append(';');
            }
            usage.put(id, List.copyOf(accountUsage));

            List<Ticket> accountTickets = new ArrayList<>();
            if (archetype == Archetype.SUPPORT_BLOCKED) {
                accountTickets.add(ticket(id, 1, "P1", "OPEN", 2, today.minusDays(2)));
                accountTickets.add(ticket(id, 2, "P1", "OPEN", 2, today.minusDays(5)));
                accountTickets.add(ticket(id, 3, "P2", "OPEN", 3, today.minusDays(8)));
            } else if (archetype == Archetype.TRUE_CHURN) {
                accountTickets.add(ticket(id, 1, "P1", "OPEN", 2, today.minusDays(4)));
                accountTickets.add(ticket(id, 2, "P2", "OPEN", 2, today.minusDays(11)));
            } else if (archetype == Archetype.ADOPTION_GAP) {
                accountTickets.add(ticket(id, 1, "P3", "OPEN", 3, today.minusDays(13)));
            } else {
                accountTickets.add(ticket(id, 9, "P3", "RESOLVED", 5, today.minusDays(35)));
            }
            tickets.put(id, List.copyOf(accountTickets));

            boolean overdue = archetype == Archetype.BILLING_BLOCKED || archetype == Archetype.TRUE_CHURN;
            invoices.put(id, List.of(new Invoice(
                    "INV-" + id,
                    id,
                    account.annualRevenue().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP),
                    today.minusDays(overdue ? 42 : 5),
                    overdue ? "OVERDUE" : "PAID"
            )));
            canonical.append('\n');
        }

        return new EvaluationFixture(
                properties.datasetVersion(),
                sha256(canonical.toString()),
                today,
                Map.copyOf(accounts),
                Map.copyOf(usage),
                Map.copyOf(tickets),
                Map.copyOf(invoices)
        );
    }

    private Ticket ticket(
            String accountId,
            int suffix,
            String priority,
            String status,
            int satisfaction,
            LocalDate opened
    ) {
        return new Ticket(
                "TKT-" + accountId + "-" + suffix,
                accountId,
                priority,
                status,
                satisfaction,
                opened.atStartOfDay().toInstant(ZoneOffset.UTC)
        );
    }

    private double noise(Random random, double amplitude) {
        return (random.nextDouble() - 0.5) * amplitude;
    }

    private double clamp(double value) {
        return Math.max(0.05, Math.min(0.98, value));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate evaluation fixture hash", ex);
        }
    }
}
