package com.helix.common.llm;

/**
 * DEFAULT {@link LlmClient} (provider {@code none}). Every call returns
 * {@link LlmResult#notConfigured()} — no external I/O, no dependency on any endpoint —
 * so each AI capability keeps its existing deterministic / grounded output byte-identical
 * to the platform before this feature existed. This is the non-negotiable regression
 * contract: with the default provider, nothing about a request changes.
 */
public class NoneLlmClient implements LlmClient {

    @Override
    public LlmResult complete(LlmRequest request) {
        return LlmResult.notConfigured();
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
