package com.helix.counterparty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Counterparty onboarding, KYC/KYB/CDD and UBO graph (PRD Stage 1). */
@SpringBootApplication(scanBasePackages = "com.helix")
@EntityScan(basePackages = "com.helix")
@EnableJpaRepositories(basePackages = "com.helix")
public class CounterpartyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CounterpartyServiceApplication.class, args);
    }
}
