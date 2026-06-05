package com.helix.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** DoA approval workflow and covenant management (PRD Stages 8-9). */
@SpringBootApplication(scanBasePackages = "com.helix")
@EntityScan(basePackages = "com.helix")
@EnableJpaRepositories(basePackages = "com.helix")
public class DecisionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DecisionServiceApplication.class, args);
    }
}
