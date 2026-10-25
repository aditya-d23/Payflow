package com.payflow.payment.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payflow.kafka.topics")
public record KafkaTopics(
        @DefaultValue("payflow.transfers") String transfers,
        @DefaultValue("payflow.accounts") String accounts
) {
    public String forAggregate(String aggregateType) {
        return switch (aggregateType) {
            case PayflowEvents.TRANSFER_AGGREGATE -> transfers;
            case PayflowEvents.ACCOUNT_AGGREGATE -> accounts;
            default -> throw new IllegalArgumentException(
                    "No topic for aggregate type " + aggregateType);
        };
    }

    public String deadLetter(String topic) {
        return topic + ".DLT";
    }
}
