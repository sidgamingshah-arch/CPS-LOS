package com.helix.common.llm;

/**
 * The outcome of an {@link LlmClient#complete(LlmRequest)} call.
 *
 * <p>{@link #configured} is the contract every caller checks: {@code false} means "no
 * usable model output — use your deterministic path". It is {@code false} for the default
 * {@link NoneLlmClient} ({@link #notConfigured()}) <em>and</em> for any live-provider error,
 * timeout or empty response ({@link #failed(String)}). Callers therefore treat an outage and
 * an unconfigured platform identically: they fall back to the existing deterministic output.</p>
 */
public record LlmResult(
        boolean configured,
        String text,
        String model,
        TokenUsage usage,
        String error) {

    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        public static final TokenUsage NONE = new TokenUsage(0, 0, 0);
    }

    /** No external LLM configured (default). The caller uses its deterministic path. */
    public static LlmResult notConfigured() {
        return new LlmResult(false, null, null, TokenUsage.NONE, null);
    }

    /** A configured LLM was attempted but failed (error / timeout / empty) — fail-soft to deterministic. */
    public static LlmResult failed(String error) {
        return new LlmResult(false, null, null, TokenUsage.NONE, error);
    }

    /** A usable LLM completion. */
    public static LlmResult ok(String text, String model, TokenUsage usage) {
        return new LlmResult(true, text, model, usage == null ? TokenUsage.NONE : usage, null);
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /** True only when the platform is configured AND the model returned usable text. */
    public boolean usable() {
        return configured && hasText();
    }
}
