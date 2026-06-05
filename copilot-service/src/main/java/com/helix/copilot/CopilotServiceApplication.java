package com.helix.copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Conversational copilot (PRD §6.6): scoped, grounded-and-cited, non-binding, audited. */
@SpringBootApplication(scanBasePackages = "com.helix")
@EntityScan(basePackages = "com.helix")
@EnableJpaRepositories(basePackages = "com.helix")
public class CopilotServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CopilotServiceApplication.class, args);
    }
}
