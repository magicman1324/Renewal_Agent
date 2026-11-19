package com.wanghongbo.renewalagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanghongbo.renewalagent.config.EvaluationProperties;
import com.wanghongbo.renewalagent.model.Models.JudgeStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class JudgeServiceTest {

    @Test
    void localJudgeWorksWithoutModelCredentials() {
        EvaluationProperties properties = new EvaluationProperties(
                "golden-set-v1",
                "target/evaluation",
                true,
                new EvaluationProperties.Judge("local", "", "", "", 30),
                new EvaluationProperties.Thresholds(0.95, 0.8, 0.9, 0.9, 1, 0, 8)
        );
        JudgeService service = new JudgeService(properties, RestClient.builder(), new ObjectMapper());

        JudgeService.JudgeOutcome outcome = service.evaluate(
                "为什么客户有续费风险？",
                "主要风险来自工单与服务风险",
                List.of("2 个 P1 未解决", "满意度 2", "活跃用户下降"),
                Map.of("steps", List.of("升级工单"))
        );

        assertThat(service.configured()).isTrue();
        assertThat(service.mode()).isEqualTo("local");
        assertThat(outcome.status()).isEqualTo(JudgeStatus.COMPLETED);
        assertThat(outcome.scores().overall()).isEqualTo(1.0);
    }
}
