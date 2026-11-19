package com.wanghongbo.renewalagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import com.wanghongbo.renewalagent.model.Models.InvestigationView;
import com.wanghongbo.renewalagent.model.Models.InternalTask;
import com.wanghongbo.renewalagent.model.Models.RiskScanResponse;
import com.wanghongbo.renewalagent.model.Models.TaskStatus;
import com.wanghongbo.renewalagent.persistence.DatabaseService;
import com.wanghongbo.renewalagent.service.AgentRuntimeService;
import com.wanghongbo.renewalagent.service.FollowUpService;
import com.wanghongbo.renewalagent.service.RiskScanService;
import java.util.Optional;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class RenewalAgentIntegrationTest {

    @Autowired
    private DatabaseService database;

    @Autowired
    private RiskScanService riskScanService;

    @Autowired
    private AgentRuntimeService agentRuntimeService;

    @Autowired
    private FollowUpService followUpService;

    @Test
    @Order(1)
    void seedsAccountsAndDetectsRiskCandidates() {
        assertThat(database.accountCount()).isEqualTo(36);

        RiskScanResponse response = riskScanService.scan();

        assertThat(response.candidateCount()).isGreaterThanOrEqualTo(24);
        assertThat(database.findRiskCandidates()).isNotEmpty();
        assertThat(database.findRiskCandidates())
                .allSatisfy(candidate -> {
                    assertThat(candidate.score()).isGreaterThanOrEqualTo(60);
                    assertThat(candidate.factors()).hasSize(6);
                });
    }

    @Test
    @Order(2)
    void runsAgentAndCreatesTaskIdempotently() {
        InvestigationView created = agentRuntimeService.create(
                "ACC-003", "为什么该客户存在续费风险，应采取什么行动？");

        assertThat(created.investigation().status()).isEqualTo(InvestigationStatus.WAITING_APPROVAL);
        assertThat(created.evidence()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(created.steps()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(created.telemetry()).isNotNull();
        assertThat(created.telemetry().toolCalls()).isEqualTo(6);
        assertThat(created.investigation().summary()).contains("工单");
        assertThat(created.investigation().action()).isNotNull();
        assertThat(created.investigation().action().steps()).isNotEmpty();

        InternalTask first = agentRuntimeService.approve(created.investigation().id(), "确认");
        InternalTask second = agentRuntimeService.approve(created.investigation().id(), "重复确认");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo(TaskStatus.WAITING_FOLLOW_UP);
        assertThat(database.findTaskByInvestigation(created.investigation().id()))
                .get()
                .extracting(task -> task.id(), task -> task.accountId(), task -> task.status())
                .containsExactly(first.id(), "ACC-003", TaskStatus.WAITING_FOLLOW_UP);
    }

    @Test
    @Order(3)
    void recordsFollowUpOutcome() {
        Optional<InvestigationView> approved = database.findInvestigations(10).stream()
                .filter(investigation -> investigation.status() == InvestigationStatus.COMPLETED)
                .findFirst()
                .flatMap(investigation -> agentRuntimeService.view(investigation.id()));

        assertThat(approved).isPresent();
        InternalTask task = approved.get().task();
        assertThat(task).isNotNull();

        var followUp = followUpService.forceFollowUp(task.id());

        assertThat(followUp.taskId()).isEqualTo(task.id());
        assertThat(followUp.result()).isIn("IMPROVED", "FLAT", "WORSENED");
        assertThat(followUpService.forceFollowUp(task.id())).isNotNull();
    }
}
