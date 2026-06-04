package com.helix.limit.service;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Currency conversion to the platform base currency. In production these rates come
 * from a CURRENCY master / bank internal feed (EOD); here a small static table models it.
 */
@Component
public class FxService {

    public static final String BASE = "INR";

    private static final Map<String, Double> TO_BASE = Map.of(
            "INR", 1.0, "AED", 22.8, "USD", 83.0, "EUR", 90.0, "GBP", 105.0, "SGD", 61.0);

    public double rate(String currency) {
        return TO_BASE.getOrDefault(currency == null ? BASE : currency.toUpperCase(), 1.0);
    }

    public double toBase(double amount, String currency) {
        return Math.round(amount * rate(currency) * 100.0) / 100.0;
    }
}
