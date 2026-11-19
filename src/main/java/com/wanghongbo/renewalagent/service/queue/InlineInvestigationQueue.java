package com.wanghongbo.renewalagent.service.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.queue.mode", havingValue = "inline")
public class InlineInvestigationQueue implements InvestigationQueue {

    @Override
    public void enqueue(String investigationId) {
        throw new IllegalStateException(
                "Inline mode executes investigations synchronously and must not enqueue");
    }
}
