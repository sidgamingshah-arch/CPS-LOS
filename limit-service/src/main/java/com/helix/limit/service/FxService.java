package com.helix.limit.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Currency conversion to the platform base currency. In production rates come from
 * a CURRENCY master / bank internal feed (EOD); here a small in-memory table models
 * it. {@link #refreshRate} is the seam EOD calls to feed today's rates — used by the
 * limit revaluation pass.
 */
@Component
public class FxService {

    public static final String BASE = "INR";

    private static final Map<String, Double> SEED = Map.of(
            "INR", 1.0, "AED", 22.8, "USD", 83.0, "EUR", 90.0, "GBP", 105.0, "SGD", 61.0);

    private final Map<String, Double> rates = new ConcurrentHashMap<>(SEED);
    private volatile Instant lastRefreshedAt = Instant.now();
    private volatile String lastRefreshedBy = "seed";

    public double rate(String currency) {
        return rates.getOrDefault(currency == null ? BASE : currency.toUpperCase(), 1.0);
    }

    public double toBase(double amount, String currency) {
        return Math.round(amount * rate(currency) * 100.0) / 100.0;
    }

    /** Feeds today's rate (e.g. EOD market-data update). Returns the previous rate. */
    public double refreshRate(String currency, double rate, String actor) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency required");
        }
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        String key = currency.toUpperCase();
        if (BASE.equals(key) && rate != 1.0) {
            throw new IllegalArgumentException("base currency rate is fixed at 1.0");
        }
        double prev = rates.getOrDefault(key, 0.0);
        rates.put(key, rate);
        lastRefreshedAt = Instant.now();
        lastRefreshedBy = actor == null ? "system" : actor;
        return prev;
    }

    /** Snapshot of current rates (order-preserved for readable audit). */
    public Map<String, Double> currentRates() {
        Map<String, Double> out = new LinkedHashMap<>();
        rates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public Instant lastRefreshedAt() {
        return lastRefreshedAt;
    }

    public String lastRefreshedBy() {
        return lastRefreshedBy;
    }
}
