package com.helix.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money helpers around {@link BigDecimal}. Storage and arithmetic stay in
 * BigDecimal (no double accumulation drift); the wire / DTO boundary converts
 * to {@code double} via {@link #asDouble} for JSON compatibility. Standard
 * platform scale is 2 with HALF_EVEN rounding (banker's rounding — what every
 * banking accounting system uses).
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    private Money() { }

    /** Coerces to scale 2 HALF_EVEN; nulls become ZERO. */
    public static BigDecimal norm(BigDecimal v) {
        return v == null ? ZERO : v.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(double v) {
        return BigDecimal.valueOf(v).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(long v) {
        return BigDecimal.valueOf(v).setScale(SCALE, ROUNDING);
    }

    /** {@code a + b}, normalised. Either side may be null (treated as zero). */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return norm((a == null ? ZERO : a).add(b == null ? ZERO : b));
    }

    public static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return norm((a == null ? ZERO : a).subtract(b == null ? ZERO : b));
    }

    /** Clamps a value at zero (no negative balances). */
    public static BigDecimal nonNegative(BigDecimal v) {
        BigDecimal n = norm(v);
        return n.signum() < 0 ? ZERO : n;
    }

    /** Convenience for wire DTOs / Jackson — null-safe. */
    public static double asDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    public static boolean gt(BigDecimal a, BigDecimal b) {
        return norm(a).compareTo(norm(b)) > 0;
    }

    public static boolean lt(BigDecimal a, BigDecimal b) {
        return norm(a).compareTo(norm(b)) < 0;
    }
}
