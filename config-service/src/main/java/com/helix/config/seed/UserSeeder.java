package com.helix.config.seed;

import com.helix.common.auth.PasswordHasher;
import com.helix.config.entity.AppUser;
import com.helix.config.repo.AppUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds a login for every demo actor — one user per {@code ACTOR_ROLE} key, so the
 * RBAC personas can actually authenticate. All demo users share one password
 * ({@value #DEMO_PASSWORD}); idempotent, so re-runs leave existing users alone.
 *
 * <p>In production these would be provisioned from the bank's IdP; here they're a
 * convenience so the governed-AI demo can log in as each persona and prove the
 * verified-actor path end to end.</p>
 */
@Component
@Order(20)
public class UserSeeder implements CommandLineRunner {

    /** Shared demo password — surfaced in the login screen hint. Not for production. */
    public static final String DEMO_PASSWORD = "Helix@2026";

    private final AppUserRepository users;

    public UserSeeder(AppUserRepository users) {
        this.users = users;
    }

    private record Seed(String username, String displayName) { }

    @Override
    public void run(String... args) {
        List<Seed> seeds = List.of(
                new Seed("rm.user", "Relationship Manager"),
                new Seed("rm.head", "Relationship Head"),
                new Seed("analyst.user", "Credit Analyst"),
                new Seed("credit.ops", "Credit Operations"),
                new Seed("credit.officer", "Credit Officer"),
                new Seed("credit.committee", "Credit Committee"),
                new Seed("compliance.officer", "Compliance Officer"),
                new Seed("portfolio.manager", "Portfolio Manager"),
                new Seed("cro", "Chief Risk Officer"),
                new Seed("treasury.ops", "Treasury Operations"),
                new Seed("finance.ops", "Finance Operations"),
                new Seed("ops.checker", "Operations Control"),
                new Seed("cad.maker", "Credit Administration"),
                new Seed("loan.ops", "Loan Servicing Ops"),
                new Seed("loan.checker", "Loan Servicing Control"),
                new Seed("lie.engineer", "Lender's Independent Engineer"),
                new Seed("collections.ops", "Collections Officer"),
                new Seed("collections.head", "Collections Head"),
                new Seed("legal.counsel", "Legal Counsel"),
                new Seed("config.admin", "Configuration Admin"),
                new Seed("config.checker", "Configuration Checker"),
                new Seed("demo.user", "Demo Super User"));

        String hash = PasswordHasher.hash(DEMO_PASSWORD);
        for (Seed s : seeds) {
            if (users.findByUsername(s.username()).isPresent()) continue;
            AppUser u = new AppUser();
            u.setUsername(s.username());
            u.setDisplayName(s.displayName());
            u.setActorKey(s.username());
            u.setPasswordHash(hash);
            u.setActive(true);
            users.save(u);
        }
    }
}
