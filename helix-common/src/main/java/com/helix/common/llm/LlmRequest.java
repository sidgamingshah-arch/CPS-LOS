package com.helix.common.llm;

/**
 * A single grounded chat completion request. The {@code systemPrompt} carries the
 * capability's governance instruction (advisory, do not invent figures, …); the
 * {@code userPrompt} carries the deterministically-retrieved facts the model must
 * summarise. {@code modelOverride} / {@code maxTokens} / {@code temperature} are
 * optional per-call overrides; when null the {@link LlmProperties} defaults apply.
 */
public record LlmRequest(
        String capability,
        String systemPrompt,
        String userPrompt,
        String modelOverride,
        Integer maxTokens,
        Double temperature) {

    /** Convenience factory for the common case (defaults for model / tokens / temperature). */
    public static LlmRequest of(String capability, String systemPrompt, String userPrompt) {
        return new LlmRequest(capability, systemPrompt, userPrompt, null, null, null);
    }
}
