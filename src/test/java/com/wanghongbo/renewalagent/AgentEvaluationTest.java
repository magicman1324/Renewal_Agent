package com.wanghongbo.renewalagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wanghongbo.renewalagent.evaluation.EvaluationService;
import com.wanghongbo.renewalagent.model.Models.EvaluationRunView;
import com.wanghongbo.renewalagent.persistence.DatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "app.eval.ci", matches = "true")
class AgentEvaluationTest {

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private DatabaseService database;

    @Test
    void goldenSetMeetsBlockingThresholdsWithoutCreatingTasks() {
        int tasksBefore = database.countOpenTasks();

        EvaluationRunView run = evaluationService.runSynchronously(false);

        assertThat(run.items()).hasSize(36);
        assertThat(run.run().summary()).isNotNull();
        assertThat(run.run().summary().passed()).isTrue();
        assertThat(run.run().summary().rootCauseAccuracy()).isGreaterThanOrEqualTo(0.8);
        assertThat(run.run().summary().actionPolicyPassRate()).isEqualTo(1.0);
        assertThat(run.run().summary().criticalViolations()).isZero();
        assertThat(database.countOpenTasks()).isEqualTo(tasksBefore);
    }
}
