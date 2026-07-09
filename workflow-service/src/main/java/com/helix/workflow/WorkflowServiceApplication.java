package com.helix.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Workflow tracker — the runtime engine that consumes the {@code WORKFLOW_DEFINITION}
 * rule packs config-service has been seeding since day one. Materialises each
 * application's stage list, guards transitions against the pack's
 * {@code humanGate}/{@code autonomy} contract, and tracks SLA breaches.
 *
 * <p>This is a strangler overlay: it sits beside the existing domain state machines
 * (disbursement / CAD / collections / amendment) rather than replacing them. They
 * keep their logic; they REPORT into the tracker at their existing transition
 * points so a deal's lifecycle position is observable in one place.</p>
 */
@SpringBootApplication(scanBasePackages = "com.helix")
@EntityScan(basePackages = "com.helix")
@EnableJpaRepositories(basePackages = "com.helix")
@EnableScheduling
public class WorkflowServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }
}
