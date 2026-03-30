package com.wallet.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public Counter depositCounter(MeterRegistry registry) {
        return Counter.builder("wallet.deposits.total")
                .description("Total deposits")
                .register(registry);
    }

    @Bean
    public Counter withdrawalCounter(MeterRegistry registry) {
        return Counter.builder("wallet.withdrawals.total")
                .description("Total withdrawals")
                .register(registry);
    }

    @Bean
    public Counter transferCounter(MeterRegistry registry) {
        return Counter.builder("wallet.transfers.total")
                .description("Total P2P transfers")
                .register(registry);
    }

    @Bean
    public Counter reversalCounter(MeterRegistry registry) {
        return Counter.builder("wallet.reversals.total")
                .description("Total transaction reversals")
                .register(registry);
    }

    @Bean
    public Counter pinLockoutCounter(MeterRegistry registry) {
        return Counter.builder("wallet.pin_lockouts.total")
                .description("Total PIN lockout events")
                .register(registry);
    }
}
