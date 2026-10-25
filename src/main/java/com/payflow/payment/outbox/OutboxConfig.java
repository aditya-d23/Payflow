package com.payflow.payment.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    /** Tests switch scheduling off and drive the relay explicitly. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "payflow.scheduling.enabled", havingValue = "true",
            matchIfMissing = true)
    static class SchedulingConfig {
    }
}
