package com.helix.common.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real chat-completion client (active when {@code helix.llm.provider} is {@code openai},
 * {@code anthropic} or {@code azure-openai}). Uses Spring {@link RestClient} + Jackson —
 * no extra dependencies. Timeout-bounded (connect + read) and <b>fail-soft</b>: any error,
 * non-2xx status, timeout, empty or unparseable body returns a non-usable {@link LlmResult}
 * so the caller falls back to its deterministic path. An LLM outage never breaks a request.
 *
 * <p>Wire formats:
 * <ul>
 *   <li><b>openai</b> — {@code POST {base-url}/chat/completions}, {@code messages[]} body,
 *       {@code Authorization: Bearer} header.</li>
 *   <li><b>azure-openai</b> — {@code POST {base-url}/openai/deployments/{model}/chat/completions?api-version=…},
 *       {@code api-key} header, deployment taken from the resolved model.</li>
 *   <li><b>anthropic</b> — {@code POST {base-url}/v1/messages}, {@code system} + {@code messages[]} body,
 *       {@code x-api-key} + {@code anthropic-version} headers.</li>
 * </ul>
 * The API key is read from configuration and attached as a header; it is never logged.</p>
 */
public class HttpLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmClient.class);

    private final LlmProperties props;
    private final RestClient http;
    private final String provider;

    public HttpLlmClient(LlmProperties props) {
        this.props = props;
        this.provider = props.normalisedProvider();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(props.getTimeoutMs() <= 0 ? 20_000L : props.getTimeoutMs());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
            builder.baseUrl(props.getBaseUrl().trim());
        }
        this.http = builder.build();

        // Deliberately never logs the api-key — only whether one is present.
        log.info("HttpLlmClient active: provider={} baseUrlConfigured={} apiKeyPresent={} defaultModelConfigured={} timeoutMs={}",
                provider, props.getBaseUrl() != null && !props.getBaseUrl().isBlank(),
                props.hasApiKey(), props.getModel() != null && !props.getModel().isBlank(), props.getTimeoutMs());
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public LlmResult complete(LlmRequest req) {
        if (req == null) {
            return LlmResult.failed("null request");
        }
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            log.warn("LLM provider={} but no helix.llm.base-url configured — deterministic fallback", provider);
            return LlmResult.failed("no base-url configured");
        }
        String model = props.modelFor(req.capability(), req.modelOverride());
        int maxTokens = req.maxTokens() != null ? req.maxTokens() : props.getMaxTokens();
        double temperature = req.temperature() != null ? req.temperature() : props.getTemperature();
        try {
            return switch (provider) {
                case "anthropic" -> anthropic(req, model, maxTokens, temperature);
                case "azure-openai", "azure" -> azureOpenAi(req, model, maxTokens, temperature);
                case "openai" -> openAi(req, model, maxTokens, temperature);
                default -> {
                    log.warn("Unknown helix.llm.provider='{}' — deterministic fallback", provider);
                    yield LlmResult.failed("unknown provider " + provider);
                }
            };
        } catch (Exception e) {
            // Fail-soft: transport error, timeout, non-2xx or parse failure. The caller falls
            // back to its deterministic output; the request is never broken by the model.
            log.warn("LLM call failed (provider={}, capability={}): {} — deterministic fallback",
                    provider, req.capability(), e.getMessage());
            return LlmResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- providers

    private LlmResult openAi(LlmRequest req, String model, int maxTokens, double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (model != null && !model.isBlank()) {
            body.put("model", model);
        }
        body.put("messages", chatMessages(req));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);

        Map<?, ?> resp = http.post()
                .uri("/chat/completions")
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    if (props.hasApiKey()) {
                        h.setBearerAuth(props.getApiKey());
                    }
                })
                .body(body)
                .retrieve()
                .body(Map.class);
        return parseOpenAi(resp, model);
    }

    private LlmResult azureOpenAi(LlmRequest req, String model, int maxTokens, double temperature) {
        // Azure OpenAI: deployment is in the path (the resolved model), api-key header, api-version query.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", chatMessages(req));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);

        String path = "/openai/deployments/" + (model == null ? "" : model)
                + "/chat/completions?api-version=" + props.getAzureApiVersion();
        Map<?, ?> resp = http.post()
                .uri(path)
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    if (props.hasApiKey()) {
                        h.set("api-key", props.getApiKey());
                    }
                })
                .body(body)
                .retrieve()
                .body(Map.class);
        return parseOpenAi(resp, model);
    }

    private LlmResult anthropic(LlmRequest req, String model, int maxTokens, double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (model != null && !model.isBlank()) {
            body.put("model", model);
        }
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            body.put("system", req.systemPrompt());
        }
        body.put("messages", List.of(Map.of("role", "user", "content", nz(req.userPrompt()))));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);

        Map<?, ?> resp = http.post()
                .uri("/v1/messages")
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    if (props.hasApiKey()) {
                        h.set("x-api-key", props.getApiKey());
                    }
                    h.set("anthropic-version", props.getAnthropicVersion());
                })
                .body(body)
                .retrieve()
                .body(Map.class);
        return parseAnthropic(resp, model);
    }

    // ---------------------------------------------------------------- parsing

    private List<Map<String, Object>> chatMessages(LlmRequest req) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", req.systemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", nz(req.userPrompt())));
        return messages;
    }

    private LlmResult parseOpenAi(Map<?, ?> resp, String fallbackModel) {
        if (resp == null) {
            return LlmResult.failed("empty response");
        }
        String text = null;
        Object choices = resp.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> choice) {
            if (choice.get("message") instanceof Map<?, ?> message) {
                text = str(message.get("content"));
            }
            if (text == null) {
                text = str(choice.get("text"));
            }
        }
        if (text == null || text.isBlank()) {
            return LlmResult.failed("no content in response");
        }
        String usedModel = firstNonBlank(str(resp.get("model")), fallbackModel);
        return LlmResult.ok(text.strip(), usedModel,
                usage(resp, "prompt_tokens", "completion_tokens", "total_tokens"));
    }

    private LlmResult parseAnthropic(Map<?, ?> resp, String fallbackModel) {
        if (resp == null) {
            return LlmResult.failed("empty response");
        }
        StringBuilder sb = new StringBuilder();
        if (resp.get("content") instanceof List<?> blocks) {
            for (Object o : blocks) {
                if (o instanceof Map<?, ?> block && block.get("text") != null) {
                    sb.append(block.get("text"));
                }
            }
        }
        String text = sb.toString();
        if (text.isBlank()) {
            return LlmResult.failed("no content in response");
        }
        String usedModel = firstNonBlank(str(resp.get("model")), fallbackModel);
        LlmResult.TokenUsage usage = LlmResult.TokenUsage.NONE;
        if (resp.get("usage") instanceof Map<?, ?> u) {
            int in = intOf(u.get("input_tokens"));
            int out = intOf(u.get("output_tokens"));
            usage = new LlmResult.TokenUsage(in, out, in + out);
        }
        return LlmResult.ok(text.strip(), usedModel, usage);
    }

    private LlmResult.TokenUsage usage(Map<?, ?> resp, String promptKey, String completionKey, String totalKey) {
        if (resp.get("usage") instanceof Map<?, ?> u) {
            int prompt = intOf(u.get(promptKey));
            int completion = intOf(u.get(completionKey));
            int total = u.get(totalKey) != null ? intOf(u.get(totalKey)) : prompt + completion;
            return new LlmResult.TokenUsage(prompt, completion, total);
        }
        return LlmResult.TokenUsage.NONE;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
