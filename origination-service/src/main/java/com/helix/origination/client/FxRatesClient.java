package com.helix.origination.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only view of the platform FX rate table that limit-service's
 * {@code FxService} owns (base = INR). This is the SINGLE source of truth for
 * both currency levels:
 *
 * <ul>
 *   <li><b>Level 2 — system currency</b>: limit-service converts every limit /
 *       exposure amount into the INR base for aggregation (already in place).</li>
 *   <li><b>Level 1 — financial-analysis currency</b>: origination calls this to
 *       restate a borrower's multi-currency financials into one presentation
 *       currency so cross-period trends and cross-facility figures are
 *       comparable.</li>
 * </ul>
 *
 * <p>Rates are stored as base-per-unit-foreign on an INR base, so a cross-rate
 * {@code from -> to} is {@code rate(from) / rate(to)}. Degrades gracefully:
 * returns {@code null} when the table is unreachable or a currency is unknown,
 * and the caller turns that into a 400 currency-consistency error rather than
 * silently assuming 1.0.</p>
 */
@Component
public class FxRatesClient {

    private static final Logger log = LoggerFactory.getLogger(FxRatesClient.class);

    private final RestClient limit;
    private final RestClient config;

    public FxRatesClient(@Value("${helix.limit-service.base-url:http://localhost:8088}") String limitUrl,
                         @Value("${helix.config-service.base-url:http://localhost:8081}") String configUrl) {
        this.limit = RestClient.builder().baseUrl(limitUrl).build();
        this.config = RestClient.builder().baseUrl(configUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FxView(String base, Map<String, Double> rates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterRow(String recordKey, Map<String, Object> payload, String status) {
    }

    /** Where a resolved rate came from — surfaced for transparency / audit. */
    public enum Source { SUPPLIED, DATED_MASTER, CURRENT_SPOT, SAME_CURRENCY, NONE }

    /** Current rate table (base + per-currency rate). Null when unreachable. */
    public FxView fxView() {
        try {
            return limit.get().uri("/api/limits/eod/fx").retrieve().body(FxView.class);
        } catch (Exception e) {
            log.warn("limit-service /eod/fx unavailable ({})", e.getMessage());
            return null;
        }
    }

    /**
     * Cross-rate to convert an amount in {@code from} into {@code to}:
     * {@code amount * crossRate(from, to)}. Returns 1.0 when the currencies
     * match, and {@code null} when either currency is unknown or the table is
     * unreachable (the caller must treat null as "no rate available").
     */
    public Double crossRate(String from, String to) {
        if (from == null || to == null) return null;
        String f = from.toUpperCase(Locale.ROOT);
        String t = to.toUpperCase(Locale.ROOT);
        if (f.equals(t)) return 1.0;
        FxView view = fxView();
        if (view == null || view.rates() == null) return null;
        Double rFrom = view.rates().get(f);
        Double rTo = view.rates().get(t);
        if (rFrom == null || rTo == null || rTo == 0.0) return null;
        return rFrom / rTo;
    }

    /**
     * Cross-rate to convert {@code from -> to} AS AT a period-end date, read from
     * the dated {@code FX_RATE} master (the historical authority for Level-1
     * analysis). Picks, for each leg, the rate with the greatest {@code asOf}
     * that is on or before the requested date — i.e. the rate prevailing at the
     * period-end. Returns {@code null} when either leg has no dated point on/before
     * the date, so the caller can fall back to the current spot rate.
     */
    public Double crossRateAsOf(String from, String to, LocalDate asOf) {
        if (from == null || to == null || asOf == null) return null;
        String f = from.toUpperCase(Locale.ROOT);
        String t = to.toUpperCase(Locale.ROOT);
        if (f.equals(t)) return 1.0;
        List<MasterRow> rows = fxRateMasters();
        if (rows == null) return null;
        Double rFrom = rateToInrAsOf(rows, f, asOf);
        Double rTo = rateToInrAsOf(rows, t, asOf);
        if (rFrom == null || rTo == null || rTo == 0.0) return null;
        return rFrom / rTo;
    }

    /** INR-base rate for a currency at a date (latest dated point on/before asOf). INR itself = 1.0. */
    private Double rateToInrAsOf(List<MasterRow> rows, String currency, LocalDate asOf) {
        if ("INR".equals(currency)) return 1.0;
        Double best = null;
        LocalDate bestDate = null;
        for (MasterRow r : rows) {
            if (r.payload() == null) continue;
            Object ccy = r.payload().get("currency");
            if (ccy == null || !currency.equalsIgnoreCase(String.valueOf(ccy))) continue;
            LocalDate d = parseDate(r.payload().get("asOf"));
            if (d == null || d.isAfter(asOf)) continue;          // only points up to the period-end
            if (bestDate == null || d.isAfter(bestDate)) {        // the most recent such point
                Object rate = r.payload().get("rateToInr");
                if (rate instanceof Number n) { best = n.doubleValue(); bestDate = d; }
            }
        }
        return best;
    }

    private List<MasterRow> fxRateMasters() {
        try {
            MasterRow[] arr = config.get().uri("/api/masters/FX_RATE").retrieve().body(MasterRow[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("config-service FX_RATE master unavailable ({})", e.getMessage());
            return null;
        }
    }

    private static LocalDate parseDate(Object v) {
        if (v == null) return null;
        try { return LocalDate.parse(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
