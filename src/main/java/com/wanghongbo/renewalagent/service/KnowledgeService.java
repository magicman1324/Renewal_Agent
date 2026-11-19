package com.wanghongbo.renewalagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.model.Models.Account;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class KnowledgeService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AppProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public KnowledgeService(
            AppProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.rag().baseUrl()).build();
        this.objectMapper = objectMapper;
    }

    public KnowledgeResult search(Account account, String query) {
        if (properties.rag().enabled()) {
            try {
                Map<?, ?> response = restClient.post()
                        .uri(properties.rag().searchPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "tenantId", account.id(),
                                "query", query,
                                "topK", 4
                        ))
                        .retrieve()
                        .body(Map.class);
                if (response != null && response.get("sources") instanceof List<?> sources && !sources.isEmpty()) {
                    return new KnowledgeResult(
                            "RAG 服务",
                            summarizeSources(sources),
                            objectMapper.convertValue(response, MAP_TYPE)
                    );
                }
            } catch (RuntimeException ignored) {
                // The Agent remains usable when the companion RAG service is offline.
            }
        }
        return localPlaybook(account);
    }

    private KnowledgeResult localPlaybook(Account account) {
        String detail = switch (account.archetype()) {
            case SUPPORT_BLOCKED -> "支持阻塞处置：升级未解决 P1 工单，指定技术负责人，先恢复关键流程，再安排复盘。";
            case BILLING_BLOCKED -> "账务阻塞处置：核对发票与付款联系人，财务在 24 小时内确认付款计划，客户成功同步续约风险。";
            case SPONSOR_DISENGAGED -> "关键人失联处置：由客户成功负责人发起高层回访，重新确认业务目标、决策链和续约会议时间。";
            case ADOPTION_GAP -> "采用不足处置：识别低采用关键功能，安排场景化培训并设置两周使用目标。";
            case TRUE_CHURN -> "真实流失处置：联合销售与客户成功进行流失访谈，评估降级、迁移支持或正式退出方案。";
            case HEALTHY -> "客户健康：保持定期回访并关注续约前关键联系人变化。";
        };
        return new KnowledgeResult(
                "本地客户成功 Playbook",
                detail,
                Map.of("source", "built-in-playbook", "updatedAt", LocalDate.now().toString())
        );
    }

    private String summarizeSources(List<?> sources) {
        return sources.stream().limit(3)
                .map(source -> objectMapper.convertValue(source, MAP_TYPE))
                .map(source -> String.valueOf(source.getOrDefault("title", source.getOrDefault("content", source))))
                .reduce((left, right) -> left + "；" + right)
                .orElse("RAG 未返回可用摘要");
    }

    public record KnowledgeResult(String source, String detail, Map<String, Object> data) {
    }
}
