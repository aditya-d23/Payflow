package com.payflow.payment.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableConfigurationProperties(KafkaTopics.class)
public class KafkaConfig {

    /**
     * A record that still fails after the retries goes to {@code <topic>.DLT}
     * on the same partition, for inspection and replay, instead of blocking
     * the partition forever.
     */
    @Bean
    @ConditionalOnProperty(name = "payflow.kafka.enabled", havingValue = "true")
    DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate, KafkaTopics topics) {
        return new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate, (record, exception) ->
                        new TopicPartition(topics.deadLetter(record.topic()), record.partition())),
                new FixedBackOff(500L, 2L));
    }

    /** Local and test convenience; production topics are provisioned separately. */
    @Configuration
    @ConditionalOnProperty(name = {"payflow.kafka.enabled", "payflow.kafka.create-topics"},
            havingValue = "true")
    static class Topics {

        @Bean
        KafkaAdmin.NewTopics payflowTopics(KafkaTopics topics) {
            return new KafkaAdmin.NewTopics(
                    topic(topics.transfers()),
                    topic(topics.deadLetter(topics.transfers())),
                    topic(topics.accounts()),
                    topic(topics.deadLetter(topics.accounts())));
        }

        private static NewTopic topic(String name) {
            return TopicBuilder.name(name).partitions(3).replicas(1).build();
        }
    }
}
