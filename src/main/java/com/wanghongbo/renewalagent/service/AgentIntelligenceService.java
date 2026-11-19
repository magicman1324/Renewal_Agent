package com.wanghongbo.renewalagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import com.wanghongbo.renewalagent.model.Models.ActionDraft;
import com.wanghongbo.renewalagent.model.Models.Decision;
import com.wanghongbo.renewalagent.model.Models.Evidence;
import com.wanghongbo.renewalagent.model.Models.FinalAnalysis;
import com.wanghongbo.renewalagent.model.Models.RiskFactor;
import com.wanghongbo.renewalagent.model.Models.ToolDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AgentIntelligenceService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final List<String> DETERMINISTIC_SEQUENCE = List.of(
            "getAccountProfile",
            "getUsageTrend",
            "getSupportTickets",
            "getBillingStatus",
            "searchRenewalPlaybook",
            "calculateRiskFactors"
    );

    private final AppProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AgentIntelligenceService(
            AppProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public Decision nextDecision(
            Account account,
            String question,
            List<ToolDefinition> tools,
            List<Evidence> evidence,
            Set<String> calledTools
    ) {
        if ("openai".equalsIgnoreCase(properties.agent().llm().mode())
                && !properties.agent().llm().apiKey().isBlank()) {
            try {
                return openAiDecision(account, question, tools, evidence, calledTools);
            } catch (RuntimeException ex) {
                // Deterministic planning is the explicit fail-safe for model outages or invalid JSON.
            }
        }
        for (String tool : DETERMINISTIC_SEQUENCE) {
            if (!calledTools.contains(tool)) {
                Map<String, Object> args = tool.equals("searchRenewalPlaybook")
                        ? Map.of("query", "续费风险处置 " + account.archetype())
                        : Map.of();
                return new Decision("CALL_TOOL", tool, args, deterministicReason(tool));
            }
        }
        return Decision.finish("已收集客户、使用、工单、账务、Playbook 和风险因子证据");
    }

    public FinalAnalysis analyze(Account account, String question, List<Evidence> evidence) {
        if ("openai".equalsIgnoreCase(properties.agent().llm().mode())
                && !properties.agent().llm().apiKey().isBlank()) {
            try {
                return openAiAnalyze(account, question, evidence);
            } catch (RuntimeException ex) {
                // Preserve a useful, auditable analysis instead of failing the whole investigation.
            }
        }
        return deterministicAnalysis(account, evidence);
    }

    private Decision openAiDecision(
            Account account,
            String question,
            List<ToolDefinition> tools,
            List<Evidence> evidence,
            Set<String> calledTools
    ) {
        String prompt = """
                你是企业客户续费风险调查 Agent。你只能从给定工具中选择下一步，不能编造工具或参数。
                已完成工具：%s
                客户：%s
                用户问题：%s
                可用工具：%s
                已有证据摘要：%s

                仅输出 JSON：
                {"action":"CALL_TOOL","tool":"工具名","arguments":{},"reason":"简短原因"}
                如果证据足够，仅输出：
                {"action":"FINISH","tool":null,"arguments":{},"reason":"原因"}
                """.formatted(
                calledTools,
                objectMapper.valueToTree(account),
                question,
                objectMapper.valueToTree(tools),
                summarizeEvidence(evidence)
        );
        JsonNode json = callChat(prompt, true);
        return objectMapper.convertValue(json, Decision.class);
    }

    private FinalAnalysis openAiAnalyze(Account account, String question, List<Evidence> evidence) {
        String prompt = """
                你是谨慎的企业客户续费分析师。只能基于给出的证据生成结论，不得编造数字。
                每个行动都要绑定证据标签。写操作只建议创建内部客户成功待办，不发送外部消息、不改价格。
                客户：%s
                问题：%s
                证据：%s

                仅输出 JSON：
                {"summary":"两段以内分析","rootCause":"主因",
                 "action":{"title":"任务标题","ownerRole":"CUSTOMER_SUCCESS|SUPPORT|FINANCE|SALES",
                 "priority":"HIGH|MEDIUM|LOW","summary":"动作摘要","rationale":"为何这样做",
                 "steps":["步骤"],"evidenceLabels":["证据标签"],"dueInDays":7}}
                """.formatted(objectMapper.valueToTree(account), question, objectMapper.valueToTree(evidence));
        JsonNode json = callChat(prompt, true);
        return objectMapper.convertValue(json, FinalAnalysis.class);
    }

    private JsonNode callChat(String prompt, boolean jsonMode) {
        if (properties.agent().llm().baseUrl().isBlank()) {
            throw new IllegalStateException("LLM base URL is empty");
        }
        String endpoint = properties.agent().llm().baseUrl().replaceAll("/+$", "") + "/chat/completions";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.agent().llm().model());
        request.put("temperature", 0.1);
        request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        if (jsonMode) {
            request.put("response_format", Map.of("type", "json_object"));
        }
        JsonNode response = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.agent().llm().apiKey())
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new IllegalStateException("Empty model response");
        }
        String content = response.path("choices").path(0).path("message").path("content").asText();
        if (content.isBlank()) {
            throw new IllegalStateException("Model did not return content");
        }
        try {
            return objectMapper.readTree(stripFence(content));
        } catch (Exception ex) {
            throw new IllegalStateException("Model returned invalid JSON", ex);
        }
    }

    private FinalAnalysis deterministicAnalysis(Account account, List<Evidence> evidence) {
        List<RiskFactor> factors = evidence.stream()
                .filter(item -> "risk".equals(item.source()))
                .flatMap(item -> {
                    Object raw = item.data().get("factors");
                    if (raw instanceof List<?> list) {
                        return list.stream().map(value -> objectMapper.convertValue(value, RiskFactor.class));
                    }
                    return java.util.stream.Stream.empty();
                })
                .toList();
        String diagnosisCode = diagnose(evidence);
        RiskFactor top = factors.stream()
                .filter(factor -> factor.code().equals(diagnosisCode))
                .findFirst()
                .orElseGet(() -> factors.stream()
                        .max((left, right) -> Double.compare(left.contribution(), right.contribution()))
                        .orElse(new RiskFactor("usage", "活跃用户下降", 0.5, 0.25, 12.5, "需要进一步人工确认")));

        String rootCause = "主要风险来自" + top.label() + "：" + top.evidence();
        String owner = switch (top.code()) {
            case "support" -> "SUPPORT";
            case "billing" -> "FINANCE";
            case "engagement" -> "SALES";
            default -> "CUSTOMER_SUCCESS";
        };
        String title = switch (owner) {
            case "SUPPORT" -> "升级工单并安排技术复盘";
            case "FINANCE" -> "确认付款计划并同步续约风险";
            case "SALES" -> "发起关键人高层回访";
            default -> "制定两周使用改善计划";
        };
        List<String> steps = switch (owner) {
            case "SUPPORT" -> List.of("确认最高优先级未解决工单", "指定技术负责人并给出恢复时间", "三天后检查工单与使用指标");
            case "FINANCE" -> List.of("核对未付发票和付款联系人", "确认付款日期与内部审批阻塞", "到期复查账务状态");
            case "SALES" -> List.of("联系客户关键决策人", "确认业务目标与续约决策链", "预约续约复盘会议");
            default -> List.of("确认识别出的低采用功能", "安排场景化培训和成功案例", "两周后复核采用率");
        };
        List<String> evidenceLabels = evidence.stream().map(Evidence::label).limit(6).toList();
        String summary = String.format(Locale.ROOT,
                "%s距续费 %d 天，风险信号集中在%s。建议先完成可验证的客户成功动作，并在到期后复查使用、工单和账务指标。",
                account.name(), account.renewalDays(), top.label());
        return new FinalAnalysis(
                summary,
                rootCause,
                new ActionDraft(
                        title,
                        owner,
                        top.contribution() >= 18 ? "HIGH" : "MEDIUM",
                        "由" + owner + "负责执行，并在复查节点提交结果。",
                        summary + " 证据包括：" + String.join("；", evidenceLabels),
                        steps,
                        evidenceLabels,
                        properties.followup().delayDays()
                )
        );
    }

    private String diagnose(List<Evidence> evidence) {
        double usageDrop = 0;
        double adoption = 1;
        long inactiveAdminDays = 0;
        long openP1 = 0;
        long overdueDays = 0;
        for (Evidence item : evidence) {
            if ("product".equals(item.source())) {
                usageDrop = number(item.data().get("usageDropRatio"));
                adoption = number(item.data().get("recentFeatureAdoption"));
                inactiveAdminDays = (long) number(item.data().get("inactiveAdminDaysInWindow"));
            }
            if ("support".equals(item.source())) {
                Object counts = item.data().get("priorityCounts");
                if (counts instanceof Map<?, ?> map) {
                    openP1 = (long) number(map.get("P1"));
                }
            }
            if ("billing".equals(item.source())) {
                overdueDays = (long) number(item.data().get("maxOverdueDays"));
            }
        }
        if (usageDrop >= 0.10 && adoption <= 0.32) {
            return "adoption";
        }
        if (openP1 >= 2) {
            return "support";
        }
        if (overdueDays >= 30) {
            return "billing";
        }
        if (inactiveAdminDays >= 20 && usageDrop < 0.15) {
            return "engagement";
        }
        if (adoption < 0.5) {
            return "adoption";
        }
        return "usage";
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private String deterministicReason(String tool) {
        return switch (tool) {
            case "getAccountProfile" -> "确认客户、续费和关键联系人上下文";
            case "getUsageTrend" -> "验证活跃用户与功能采用变化";
            case "getSupportTickets" -> "排除服务阻塞";
            case "getBillingStatus" -> "排除账务阻塞";
            case "searchRenewalPlaybook" -> "检索与当前风险匹配的处置方法";
            case "calculateRiskFactors" -> "对已收集证据进行确定性加权归因";
            default -> "补充调查";
        };
    }

    private String summarizeEvidence(List<Evidence> evidence) {
        return evidence.stream()
                .map(item -> item.source() + ": " + item.detail())
                .reduce((left, right) -> left + " | " + right)
                .orElse("暂无");
    }

    private String stripFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                return trimmed.substring(firstLine + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
