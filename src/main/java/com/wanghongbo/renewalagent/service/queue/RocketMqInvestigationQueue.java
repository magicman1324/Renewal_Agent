package com.wanghongbo.renewalagent.service.queue;

import com.wanghongbo.renewalagent.config.AppProperties;
import com.wanghongbo.renewalagent.service.AgentRuntimeService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.queue.mode", havingValue = "rocketmq")
public class RocketMqInvestigationQueue implements InvestigationQueue {

    private static final Logger log = LoggerFactory.getLogger(RocketMqInvestigationQueue.class);

    private final AppProperties properties;
    private final ObjectProvider<AgentRuntimeService> runtimeProvider;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;

    public RocketMqInvestigationQueue(
            AppProperties properties,
            ObjectProvider<AgentRuntimeService> runtimeProvider
    ) {
        this.properties = properties;
        this.runtimeProvider = runtimeProvider;
    }

    @PostConstruct
    public void start() throws Exception {
        AppProperties.RocketMq config = properties.queue().rocketmq();
        producer = new DefaultMQProducer(config.producerGroup());
        producer.setNamesrvAddr(config.namesrvAddr());
        producer.start();

        consumer = new DefaultMQPushConsumer(config.consumerGroup());
        consumer.setNamesrvAddr(config.namesrvAddr());
        consumer.subscribe(config.topic(), "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            try {
                for (var message : messages) {
                    String investigationId = new String(message.getBody(), StandardCharsets.UTF_8);
                    runtimeProvider.getObject().executeInvestigation(investigationId);
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception ex) {
                log.error("RocketMQ Agent job failed", ex);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
        log.info("RocketMQ investigation queue started");
    }

    @Override
    public void enqueue(String investigationId) {
        try {
            Message message = new Message(
                    properties.queue().rocketmq().topic(),
                    investigationId.getBytes(StandardCharsets.UTF_8)
            );
            producer.send(message);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to enqueue investigation in RocketMQ", ex);
        }
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
        if (producer != null) {
            producer.shutdown();
        }
    }
}
