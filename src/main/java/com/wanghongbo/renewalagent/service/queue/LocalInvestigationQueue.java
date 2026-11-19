package com.wanghongbo.renewalagent.service.queue;

import com.wanghongbo.renewalagent.service.AgentRuntimeService;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.queue.mode", havingValue = "local", matchIfMissing = true)
public class LocalInvestigationQueue implements InvestigationQueue {

    private final Executor executor;
    private final ObjectProvider<AgentRuntimeService> runtimeProvider;

    public LocalInvestigationQueue(
            @Qualifier("agentExecutor") Executor executor,
            ObjectProvider<AgentRuntimeService> runtimeProvider
    ) {
        this.executor = executor;
        this.runtimeProvider = runtimeProvider;
    }

    @Override
    public void enqueue(String investigationId) {
        executor.execute(() -> runtimeProvider.getObject().executeInvestigation(investigationId));
    }
}
