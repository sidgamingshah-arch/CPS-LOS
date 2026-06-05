package com.helix.copilot.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Read-only retrieval the copilot grounds its answers in. Every answer cites the
 * service + endpoint that supplied a fact; nothing is fabricated. All reads are
 * GETs — the copilot cannot mutate state (PRD §6.6).
 */
@Component
public class CopilotUpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(CopilotUpstreamClient.class);

    private final RestClient counterparty;
    private final RestClient origination;
    private final RestClient risk;
    private final RestClient decision;
    private final RestClient portfolio;

    public CopilotUpstreamClient(
            @Value("${helix.counterparty-service.base-url}") String counterpartyUrl,
            @Value("${helix.origination-service.base-url}") String originationUrl,
            @Value("${helix.risk-service.base-url}") String riskUrl,
            @Value("${helix.decision-service.base-url}") String decisionUrl,
            @Value("${helix.portfolio-service.base-url}") String portfolioUrl) {
        this.counterparty = RestClient.builder().baseUrl(counterpartyUrl).build();
        this.origination = RestClient.builder().baseUrl(originationUrl).build();
        this.risk = RestClient.builder().baseUrl(riskUrl).build();
        this.decision = RestClient.builder().baseUrl(decisionUrl).build();
        this.portfolio = RestClient.builder().baseUrl(portfolioUrl).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> obj(RestClient c, String uri, Object... vars) {
        try {
            return c.get().uri(uri, vars).retrieve().body(Map.class);
        } catch (Exception e) {
            log.debug("copilot read failed {}: {}", uri, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> arr(RestClient c, String uri, Object... vars) {
        try {
            List<?> raw = c.get().uri(uri, vars).retrieve().body(List.class);
            return raw == null ? List.of() : (List<Map<String, Object>>) raw;
        } catch (Exception e) {
            log.debug("copilot read failed {}: {}", uri, e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> creditInputs(String ref) { return obj(origination, "/api/applications/{r}/credit-inputs", ref); }
    public Map<String, Object> analysis(String ref) { return obj(origination, "/api/applications/{r}/analysis", ref); }
    public Map<String, Object> riskSummary(String ref) { return obj(risk, "/api/risk/{r}", ref); }
    public Map<String, Object> capitalExplain(String ref) { return obj(risk, "/api/risk/{r}/capital/explain", ref); }
    public Map<String, Object> decisionLatest(String ref) { return obj(decision, "/api/decisions/{r}", ref); }
    public List<Map<String, Object>> covenants(String ref) { return arr(decision, "/api/decisions/{r}/covenants", ref); }
    public Map<String, Object> ecl(String ref) { return obj(portfolio, "/api/portfolio/exposures/{r}/ecl/latest", ref); }
    public Map<String, Object> portfolioSummary() { return obj(portfolio, "/api/portfolio/summary"); }
    public Map<String, Object> concentration(String j) { return obj(portfolio, "/api/portfolio/concentration?jurisdiction={j}", j); }
    public List<Map<String, Object>> signals(String ref) { return arr(portfolio, "/api/portfolio/exposures/{r}/ews", ref); }
    public Map<String, Object> counterparty(long id) { return obj(counterparty, "/api/counterparties/{id}", id); }
    public List<Map<String, Object>> screening(long id) { return arr(counterparty, "/api/counterparties/{id}/screening", id); }
    public List<Map<String, Object>> ubo(long id) { return arr(counterparty, "/api/counterparties/{id}/ubo", id); }
}
