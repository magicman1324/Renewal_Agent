package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.model.Models.Evidence;
import com.wanghongbo.renewalagent.model.Models.InvestigationStatus;
import com.wanghongbo.renewalagent.model.Models.StepStatus;

public interface AgentExecutionObserver {

    void onStatus(InvestigationStatus status);

    void onStep(
            String type,
            String title,
            String detail,
            StepStatus status,
            long durationMs
    );

    void onEvidence(Evidence evidence);

    final class Noop implements AgentExecutionObserver {
        public static final Noop INSTANCE = new Noop();

        private Noop() {
        }

        @Override
        public void onStatus(InvestigationStatus status) {
        }

        @Override
        public void onStep(String type, String title, String detail, StepStatus status, long durationMs) {
        }

        @Override
        public void onEvidence(Evidence evidence) {
        }
    }
}
