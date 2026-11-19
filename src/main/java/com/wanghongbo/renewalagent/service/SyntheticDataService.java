package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.model.Models.Archetype;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyntheticDataService {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataService.class);
    private static final String SEED_KEY = "renewal-agent-demo";
    private static final String SEED_VERSION = "demo-v2";
    private static final int ACCOUNT_COUNT = 36;
    private static final int HISTORY_DAYS = 180;
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

    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final Random random = new Random(20261119L);

    public SyntheticDataService(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfNeeded() {
        if (!properties.seed().enabled()) {
            return;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
        String version = jdbc.query("""
                SELECT seed_version FROM seed_metadata WHERE seed_key = ?
                """, rs -> rs.next() ? rs.getString(1) : null, SEED_KEY);
        if (count != null && count > 0 && SEED_VERSION.equals(version)) {
            return;
        }
        seed();
        log.info("Seeded {} synthetic SaaS accounts with {} days of history using {}",
                ACCOUNT_COUNT, HISTORY_DAYS, SEED_VERSION);
    }

    @Transactional
    public synchronized void seed() {
        clearOwnedDemoData();
        LocalDate today = LocalDate.now();
        List<Object[]> accountRows = new ArrayList<>();
        List<Object[]> usageRows = new ArrayList<>();
        List<Object[]> ticketRows = new ArrayList<>();
        List<Object[]> invoiceRows = new ArrayList<>();

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
                case TRUE_CHURN -> (int) (licensedSeats * 0.38);
            };
            int sponsorLastLoginDays = switch (archetype) {
                case SPONSOR_DISENGAGED -> 38;
                case TRUE_CHURN -> 21;
                case ADOPTION_GAP -> 18;
                default -> 1 + i % 5;
            };
            int renewalDays = 12 + (i * 13) % 76;
            BigDecimal annualRevenue = BigDecimal.valueOf(240_000L + (i * 37_000L));
            String name = NAMES[i] + "有限公司";
            String industry = INDUSTRIES[i % INDUSTRIES.length];
            accountRows.add(new Object[]{
                    id, name, industry, licensedSeats, activeSeats, "关键联系人-" + (i + 1),
                    sponsorLastLoginDays, renewalDays, archetype.name(), annualRevenue
            });

            for (int dayOffset = HISTORY_DAYS - 1; dayOffset >= 0; dayOffset--) {
                LocalDate date = today.minusDays(dayOffset);
                double progress = (HISTORY_DAYS - dayOffset) / (double) HISTORY_DAYS;
                double baseUtilization = switch (archetype) {
                    case HEALTHY -> 0.82 + noise(0.03);
                    case ADOPTION_GAP -> 0.72 - progress * 0.30;
                    case SUPPORT_BLOCKED -> 0.80 - progress * 0.22;
                    case BILLING_BLOCKED -> 0.80 + noise(0.02);
                    case SPONSOR_DISENGAGED -> 0.73 - progress * 0.05;
                    case TRUE_CHURN -> 0.95 - progress * 0.76;
                };
                double adoption = switch (archetype) {
                    case HEALTHY -> 0.74 + noise(0.035);
                    case ADOPTION_GAP -> 0.70 - progress * 0.52;
                    case SUPPORT_BLOCKED -> 0.68 - progress * 0.22;
                    case BILLING_BLOCKED -> 0.69 + noise(0.025);
                    case SPONSOR_DISENGAGED -> 0.60 - progress * 0.07;
                    case TRUE_CHURN -> 0.75 - progress * 0.62;
                };
                int activeUsers = Math.max(3, (int) Math.round(licensedSeats * baseUtilization));
                long events = Math.max(20, (long) (activeUsers * (16 + random.nextDouble() * 12)));
                boolean adminActive = !(archetype == Archetype.SPONSOR_DISENGAGED && dayOffset < 30)
                        && !(archetype == Archetype.TRUE_CHURN && dayOffset < 20)
                        && !(archetype == Archetype.ADOPTION_GAP && dayOffset < 14);
                usageRows.add(new Object[]{
                        id, Date.valueOf(date), activeUsers, clamp(adoption), events, adminActive
                });
            }

            if (archetype == Archetype.SUPPORT_BLOCKED) {
                ticketRows.add(ticket(id, 1, "P1", "OPEN", 2, today.minusDays(2)));
                ticketRows.add(ticket(id, 2, "P1", "OPEN", 2, today.minusDays(5)));
                ticketRows.add(ticket(id, 3, "P2", "OPEN", 3, today.minusDays(8)));
            } else if (archetype == Archetype.TRUE_CHURN) {
                ticketRows.add(ticket(id, 1, "P1", "OPEN", 2, today.minusDays(4)));
                ticketRows.add(ticket(id, 2, "P2", "OPEN", 2, today.minusDays(11)));
            } else if (archetype == Archetype.ADOPTION_GAP) {
                ticketRows.add(ticket(id, 1, "P3", "OPEN", 3, today.minusDays(13)));
            } else {
                ticketRows.add(ticket(id, 9, "P3", "RESOLVED", 5, today.minusDays(35)));
            }

            boolean overdue = archetype == Archetype.BILLING_BLOCKED
                    || archetype == Archetype.TRUE_CHURN;
            invoiceRows.add(new Object[]{
                    "INV-" + id, id, annualRevenue.divide(BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP),
                    Date.valueOf(today.minusDays(overdue ? 42 : 5)),
                    overdue ? "OVERDUE" : "PAID"
            });
        }

        jdbc.batchUpdate("""
                INSERT INTO accounts(id, name, industry, licensed_seats, active_seats, sponsor_name,
                    sponsor_last_login_days, renewal_days, archetype, annual_revenue)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, accountRows);
        jdbc.batchUpdate("""
                INSERT INTO usage_daily(account_id, usage_date, active_users, feature_adoption,
                    usage_events, admin_active) VALUES (?, ?, ?, ?, ?, ?)
                """, usageRows);
        jdbc.batchUpdate("""
                INSERT INTO tickets(id, account_id, priority, status, satisfaction, opened_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ticketRows);
        jdbc.batchUpdate("""
                INSERT INTO invoices(id, account_id, amount, due_date, status)
                VALUES (?, ?, ?, ?, ?)
                """, invoiceRows);
        int updated = jdbc.update("""
                UPDATE seed_metadata SET seed_version = ?, updated_at = ?
                WHERE seed_key = ?
                """, SEED_VERSION, new Timestamp(System.currentTimeMillis()), SEED_KEY);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO seed_metadata(seed_key, seed_version, updated_at)
                    VALUES (?, ?, ?)
                    """, SEED_KEY, SEED_VERSION, new Timestamp(System.currentTimeMillis()));
        }
    }

    private void clearOwnedDemoData() {
        jdbc.update("DELETE FROM risk_candidates WHERE account_id LIKE 'ACC-%'");
        jdbc.update("DELETE FROM invoices WHERE account_id LIKE 'ACC-%'");
        jdbc.update("DELETE FROM tickets WHERE account_id LIKE 'ACC-%'");
        jdbc.update("DELETE FROM usage_daily WHERE account_id LIKE 'ACC-%'");
        jdbc.update("DELETE FROM accounts WHERE id LIKE 'ACC-%'");
    }

    private Object[] ticket(
            String accountId,
            int suffix,
            String priority,
            String status,
            int satisfaction,
            LocalDate opened
    ) {
        return new Object[]{
                "TKT-" + accountId + "-" + suffix,
                accountId,
                priority,
                status,
                satisfaction,
                Timestamp.from(Instant.parse(opened + "T02:00:00Z"))
        };
    }

    private double noise(double amplitude) {
        return (random.nextDouble() - 0.5) * amplitude;
    }

    private double clamp(double value) {
        return Math.max(0.05, Math.min(0.98, value));
    }
}
