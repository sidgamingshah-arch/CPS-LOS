package com.helix.origination;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Application intake, document classification and financial spreading (PRD Stages 3-4). */
@SpringBootApplication(scanBasePackages = "com.helix")
@EntityScan(basePackages = "com.helix")
@EnableJpaRepositories(basePackages = "com.helix")
public class OriginationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OriginationServiceApplication.class, args);
    }
}
