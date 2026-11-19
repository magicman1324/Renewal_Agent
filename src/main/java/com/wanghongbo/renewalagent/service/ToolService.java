package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.Invoice;
import com.wanghongbo.renewalagent.model.Models.RiskFactor;
import com.wanghongbo.renewalagent.model.Models.Ticket;
import com.wanghongbo.renewalagent.model.Models.ToolDefinition;
import com.wanghongbo.renewalagent.model.Models.ToolResult;
import com.wanghongbo.renewalagent.model.Models.UsagePoint;
import com.wanghongbo.renewalagent.persistence.AgentDataGateway;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ToolService {

    private static final Set<String> ALLOWED_READ_TOOLS = Set.of(
            "getAccountProfile",
            "getUsageTrend",
            "getSupportTickets",
            "getBillingStatus",
            "searchRenewalPlaybook",
            "calculateRiskFactors"
    );

    private final AgentDataGateway agentDataGateway;
    private final KnowledgeService knowledgeService;
    private final RiskScoringService riskScoringService;
    private final Clock clock;

    public ToolService(
            AgentDataGateway agentDataGateway,
            KnowledgeService knowledgeService,
            RiskScoringService riskScoringService,
            Clock clock
    ) {
        this.agentDataGateway = agentDataGateway;
        this.knowledgeService = knowledgeService;
        this.riskScoringService = riskScoringService;
        this.clock = clock;
    }

    public List<ToolDefinition> definitions() {
        return List.of(
                tool("getAccountProfile", "读取客户、订阅和关键联系人信息", Map.of()),
                tool("getUsageTrend", "读取近60天活跃用户、席位和功能采用变化", Map.of()),
                tool("getSupportTickets", "读取未解决工单、优先级和满意度", Map.of()),
                tool("getBillingStatus", "读取发票、逾期天数和账务状态", Map.of()),
                tool("searchRenewalPlaybook", "从 RAG 服务检索续费处置手册", Map.of("query", "string")),
                tool("calculateRiskFactors", "计算确定性风险和归因因子", Map.of())
        );
    }

    public ToolResult execute(String toolName, Account account, Map<String, Object> arguments) {
        if (!ALLOWED_READ_TOOLS.contains(toolName)) {
            return ToolResult.failure(toolName, "工具不存在或无只读权限");
        }
        LocalDate usageFrom = LocalDate.now(clock).minusDays(60);
        return switch (toolName) {
            case "getAccountProfile" -> accountProfile(account);
            case "getUsageTrend" -> usageTrend(account, agentDataGateway.findUsage(account.id(), usageFrom));
            case "getSupportTickets" -> supportTickets(account, agentDataGateway.findOpenTickets(account.id()));
            case "getBillingStatus" -> billingStatus(account, agentDataGateway.findInvoices(account.id()));
            case "searchRenewalPlaybook" -> playbook(account, arguments);
            case "calculateRiskFactors" -> riskFactors(
                    account,
                    agentDataGateway.findUsage(account.id(), usageFrom),
                    agentDataGateway.findOpenTickets(account.id()),
                    agentDataGateway.findInvoices(account.id())
            );
            default -> ToolResult.failure(toolName, "未实现工具");
        };
    }

    private ToolResult accountProfile(Account account) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", account.id());
        data.put("name", account.name());
        data.put("industry", account.industry());
        data.put("licensedSeats", account.licensedSeats());
        data.put("activeSeats", account.activeSeats());
        data.put("renewalDays", account.renewalDays());
        data.put("sponsor", account.sponsorName());
        data.put("sponsorLastLoginDays", account.sponsorLastLoginDays());
        data.put("annualRevenue", account.annualRevenue());
        return new ToolResult("getAccountProfile", true,
                account.name() + "，续费剩余 " + account.renewalDays() + " 天", data);
    }

    private ToolResult usageTrend(Account account, List<UsagePoint> usage) {
        LocalDate recentStart = LocalDate.now(clock).minusDays(30);
        LocalDate previousStart = LocalDate.now(clock).minusDays(60);
        double recentUsers = usage.stream().filter(p -> !p.date().isBefore(recentStart))
                .mapToInt(UsagePoint::activeUsers).average().orElse(0);
        double previousUsers = usage.stream()
                .filter(p -> !p.date().isBefore(previousStart) && p.date().isBefore(recentStart))
                .mapToInt(UsagePoint::activeUsers).average().orElse(recentUsers);
        double recentAdoption = usage.stream().filter(p -> !p.date().isBefore(recentStart))
                .mapToDouble(UsagePoint::featureAdoption).average().orElse(0);
        double drop = riskScoringService.usageDrop(account, usage);
        long inactiveAdminDays = usage.stream().filter(p -> !p.adminActive()).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recentActiveUsers", round(recentUsers));
        data.put("previousActiveUsers", round(previousUsers));
        data.put("usageDropRatio", round(drop));
        data.put("recentFeatureAdoption", round(recentAdoption));
        data.put("inactiveAdminDaysInWindow", inactiveAdminDays);
        return new ToolResult("getUsageTrend", true,
                String.format(Locale.ROOT, "近30天活跃用户下降 %.1f%%，功能采用率 %.1f%%",
                        drop * 100, recentAdoption * 100), data);
    }

    private ToolResult supportTickets(Account account, List<Ticket> tickets) {
        Map<String, Long> counts = tickets.stream()
                .collect(Collectors.groupingBy(Ticket::priority, LinkedHashMap::new, Collectors.counting()));
        int minimumSatisfaction = tickets.stream().mapToInt(Ticket::satisfaction).min().orElse(5);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openTicketCount", tickets.size());
        data.put("priorityCounts", counts);
        data.put("minimumSatisfaction", minimumSatisfaction);
        data.put("tickets", tickets.stream().limit(5).map(ticket -> Map.of(
                "id", ticket.id(),
                "priority", ticket.priority(),
                "status", ticket.status(),
                "satisfaction", ticket.satisfaction()
        )).toList());
        return new ToolResult("getSupportTickets", true,
                "未解决工单 " + tickets.size() + " 个，最低满意度 " + minimumSatisfaction, data);
    }

    private ToolResult billingStatus(Account account, List<Invoice> invoices) {
        LocalDate today = LocalDate.now(clock);
        long maxOverdueDays = invoices.stream()
                .filter(invoice -> !"PAID".equals(invoice.status()))
                .mapToLong(invoice -> Math.max(0, ChronoUnit.DAYS.between(invoice.dueDate(), today)))
                .max().orElse(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invoiceCount", invoices.size());
        data.put("maxOverdueDays", maxOverdueDays);
        data.put("unpaidCount", invoices.stream().filter(i -> !"PAID".equals(i.status())).count());
        data.put("invoices", invoices.stream().limit(3).map(invoice -> Map.of(
                "id", invoice.id(),
                "status", invoice.status(),
                "dueDate", invoice.dueDate().toString(),
                "amount", invoice.amount()
        )).toList());
        return new ToolResult("getBillingStatus", true,
                maxOverdueDays > 0 ? "最长逾期 " + maxOverdueDays + " 天" : "账务正常", data);
    }

    private ToolResult playbook(Account account, Map<String, Object> arguments) {
        String query = String.valueOf(arguments.getOrDefault("query", "续费风险处置"));
        KnowledgeService.KnowledgeResult knowledge = knowledgeService.search(account, query);
        return new ToolResult("searchRenewalPlaybook", true, knowledge.detail(),
                Map.of("source", knowledge.source(), "query", query, "metadata", knowledge.data()));
    }

    private ToolResult riskFactors(
            Account account,
            List<UsagePoint> usage,
            List<Ticket> tickets,
            List<Invoice> invoices
    ) {
        RiskScoringService.ScoreResult result = riskScoringService.score(account, usage, tickets, invoices);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("score", result.score());
        data.put("factors", result.factors());
        return new ToolResult("calculateRiskFactors", true,
                "客户风险分 " + result.score(), data);
    }

    private ToolDefinition tool(String name, String description, Map<String, Object> properties) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", properties,
                "additionalProperties", false
        );
        return new ToolDefinition(name, description, "READ", 8, true, schema);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
