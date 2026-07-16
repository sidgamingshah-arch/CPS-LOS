package com.helix.common.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Centralised, separately-maintained configuration for an <b>optional</b> external LLM
 * endpoint, all under {@code helix.llm.*}. This is the single place a bank points Helix
 * at its own model; every AI capability service resolves its {@link LlmClient} from it.
 *
 * <p><b>The single most important property is {@link #provider}.</b> Default {@code none}
 * reproduces the platform's original behaviour <em>exactly</em>: {@link NoneLlmClient} is
 * wired, every {@code complete(...)} returns {@link LlmResult#notConfigured()}, and each
 * capability falls back to its existing deterministic / grounded path — byte-identical to
 * today. Only when {@code provider} is {@code openai}, {@code anthropic} or
 * {@code azure-openai} does {@link HttpLlmClient} make a real, timeout-bounded, fail-soft
 * chat call.</p>
 *
 * <p>Scalar keys bind from environment variables via Spring relaxed binding
 * ({@code HELIX_LLM_PROVIDER}, {@code HELIX_LLM_BASE_URL}, {@code HELIX_LLM_API_KEY},
 * {@code HELIX_LLM_MODEL}, {@code HELIX_LLM_TIMEOUT_MS}, {@code HELIX_LLM_MAX_TOKENS},
 * {@code HELIX_LLM_TEMPERATURE}). Per-capability overrides ({@link #capabilityModels})
 * are best set in {@code application.yml} / properties, e.g.
 * {@code helix.llm.capability-models.copilot}.</p>
 *
 * <p><b>Governance note.</b> A configured LLM only ever drafts <em>advisory,
 * human-gated</em> text/extractions at the platform boundary. It never writes an
 * authoritative figure, grade, price or decision — the deterministic figure path and the
 * human-confirm gates are unchanged whether the provider is {@code none} or a live model.</p>
 */
@ConfigurationProperties(prefix = "helix.llm")
public class LlmProperties {

    /** {@code none} (default, no external call) | {@code openai} | {@code anthropic} | {@code azure-openai}. */
    private String provider = "none";

    /** Base URL of the chat endpoint. OpenAI-compatible callers POST {@code {base-url}/chat/completions}. */
    private String baseUrl;

    /** API key. Sent as {@code Authorization: Bearer} (openai), {@code api-key} (azure) or {@code x-api-key} (anthropic). Never logged. */
    private String apiKey;

    /** Default model / deployment name used when a capability has no per-capability override. */
    private String model;

    /** Connect + read timeout in milliseconds. An outage or slow model NEVER blocks a request beyond this. */
    private long timeoutMs = 20_000L;

    /** Upper bound on generated tokens per call. */
    private int maxTokens = 1024;

    /** Sampling temperature. Kept low by default — these are grounded, factual drafts. */
    private double temperature = 0.2;

    /** {@code anthropic-version} header value for the Anthropic Messages API (an API version, not a model). */
    private String anthropicVersion = "2023-06-01";

    /** {@code api-version} query parameter for the Azure OpenAI REST API (an API version, not a model). */
    private String azureApiVersion = "2024-02-15-preview";

    /**
     * Per-capability model overrides. Keys are capability names used by the callers, e.g.
     * {@code copilot}, {@code doc-extract}, {@code commentary}, {@code screening-rationale},
     * {@code translation}. A missing key falls back to {@link #model}.
     */
    private Map<String, String> capabilityModels = new LinkedHashMap<>();

    /** True when no external provider is configured (the default) — callers stay fully deterministic. */
    public boolean isNone() {
        return provider == null || provider.isBlank() || "none".equalsIgnoreCase(provider.trim());
    }

    /** Resolve the model for a capability: explicit override → per-capability map → default model. */
    public String modelFor(String capability, String override) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        if (capability != null && capabilityModels != null) {
            String m = capabilityModels.get(capability);
            if (m != null && !m.isBlank()) {
                return m.trim();
            }
        }
        return model;
    }

    public String normalisedProvider() {
        return provider == null ? "none" : provider.trim().toLowerCase(Locale.ROOT);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public String getAnthropicVersion() { return anthropicVersion; }
    public void setAnthropicVersion(String anthropicVersion) { this.anthropicVersion = anthropicVersion; }
    public String getAzureApiVersion() { return azureApiVersion; }
    public void setAzureApiVersion(String azureApiVersion) { this.azureApiVersion = azureApiVersion; }
    public Map<String, String> getCapabilityModels() { return capabilityModels; }
    public void setCapabilityModels(Map<String, String> capabilityModels) {
        this.capabilityModels = capabilityModels == null ? new LinkedHashMap<>() : capabilityModels;
    }
}
