package com.helix.limit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Limit management: multi-level limit tree, fungibility roll-up, exposure norms, and the
 *  product-processor View / Validation / Utilisation APIs (PRD Limit Management module). */
@SpringBootApplication(scanBasePackages = "com.helix")
@EntityScan(basePackages = "com.helix")
@EnableJpaRepositories(basePackages = "com.helix")
public class LimitServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LimitServiceApplication.class, args);
    }
}
