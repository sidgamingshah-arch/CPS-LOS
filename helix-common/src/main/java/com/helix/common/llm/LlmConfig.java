package com.helix.common.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires exactly one {@link LlmClient} for every service (picked up via helix-common's
 * {@code com.helix} component scan). The active bean is selected by {@code helix.llm.provider}:
 *
 * <ul>
 *   <li>{@code none} (DEFAULT, incl. property absent) → {@link NoneLlmClient}: no external
 *       call, deterministic fallback everywhere — the byte-identical regression contract.</li>
 *   <li>{@code openai} / {@code anthropic} / {@code azure-openai} → {@link HttpLlmClient}:
 *       a real, timeout-bounded, fail-soft chat call.</li>
 * </ul>
 *
 * The {@code none} bean is declared first with {@code matchIfMissing=true}; the HTTP bean is
 * {@code @ConditionalOnMissingBean}, so it only registers when the provider is <em>not</em>
 * {@code none} (i.e. the {@code none} bean did not match).
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "helix.llm.provider", havingValue = "none", matchIfMissing = true)
    public LlmClient noneLlmClient() {
        return new NoneLlmClient();
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient httpLlmClient(LlmProperties properties) {
        return new HttpLlmClient(properties);
    }
}
