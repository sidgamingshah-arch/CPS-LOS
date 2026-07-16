package com.helix.common.llm;

/**
 * SPI for an external LLM chat completion. There is always exactly one bean on the
 * classpath: {@link NoneLlmClient} by default (provider {@code none}), or
 * {@link HttpLlmClient} when a real provider is configured. Callers depend only on this
 * interface and branch on {@link LlmResult#usable()} — so wiring in a real model never
 * changes their grounding, citation, human-gate or refusal behaviour.
 *
 * <p>Implementations MUST be fail-soft: {@code complete} never throws and never blocks
 * beyond the configured timeout; on any error it returns a non-{@code usable} result so
 * the caller falls back to its deterministic path.</p>
 */
public interface LlmClient {

    /** Perform a grounded completion. Never throws; returns a non-usable result on any failure. */
    LlmResult complete(LlmRequest request);

    /** True when a real external provider is wired ({@link HttpLlmClient}); false for {@link NoneLlmClient}. */
    default boolean enabled() {
        return false;
    }
}
