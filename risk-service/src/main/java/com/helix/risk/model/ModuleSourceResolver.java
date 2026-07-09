package com.helix.risk.model;

import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.entity.Rating;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves a model parameter that is SOURCED FROM ANOTHER CPS MODULE/SCREEN —
 * a {@code MODULE} question whose {@code ref} names a system datapoint
 * ({@code namespace:key}) the platform already produces upstream:
 * <ul>
 *   <li>{@code RATIO:<KEY>} — spreading-derived ratio (NET_LEVERAGE, DSCR, CURRENT_RATIO, …)</li>
 *   <li>{@code TREND:<KEY>} — spreading trend (REVENUE_GROWTH, EBITDA_GROWTH, DEBT_GROWTH)</li>
 *   <li>{@code FINANCIAL:<KEY>} — a spread line (REVENUE, EBITDA, TOTAL_DEBT, …)</li>
 *   <li>{@code RATING:GRADE|MODEL_GRADE|PD|LGD|EAD} — the authoritative rating</li>
 *   <li>{@code DEAL:SEGMENT|JURISDICTION|FACILITY_TYPE|AMOUNT|TENOR|SECURED|COLLATERAL_VALUE|COLLATERAL_COVERAGE}</li>
 * </ul>
 * Returns the value as a string (numbers rounded), or {@code null} when the
 * datapoint isn't available yet — the engine then leaves the parameter unanswered
 * rather than inventing a value. Extend the switch to bind more modules.
 */
@Component
public class ModuleSourceResolver {

    public String resolve(String ref, CreditInputsDto in, Rating rating) {
        if (ref == null || !ref.contains(":")) return null;
        String[] parts = ref.split(":", 2);
        String ns = parts[0].trim().toUpperCase(Locale.ROOT);
        String key = parts[1].trim();
        switch (ns) {
            case "RATIO": {
                double v = in.ratio(key.toUpperCase(Locale.ROOT));
                return v == 0.0 ? null : num(v);
            }
            case "TREND": {
                if (in.trends() == null) return null;
                Double v = in.trends().get(key.toUpperCase(Locale.ROOT));
                return v == null ? null : num(v);
            }
            case "FINANCIAL": {
                double v = in.financial(key.toUpperCase(Locale.ROOT));
                return v == 0.0 ? null : num(v);
            }
            case "RATING": {
                if (rating == null) return null;
                return switch (key.toUpperCase(Locale.ROOT)) {
                    case "GRADE" -> rating.getFinalGrade();
                    case "MODEL_GRADE" -> rating.getModelGrade();
                    case "PD" -> num(rating.getPd());
                    case "LGD" -> num(rating.getLgd());
                    case "EAD" -> num(rating.getEad());
                    default -> null;
                };
            }
            case "DEAL": {
                return switch (key.toUpperCase(Locale.ROOT)) {
                    case "SEGMENT" -> in.segment();
                    case "JURISDICTION" -> in.jurisdiction();
                    case "FACILITY_TYPE" -> in.facilityType();
                    case "AMOUNT" -> num(in.requestedAmount());
                    case "TENOR" -> String.valueOf(in.tenorMonths());
                    case "SECURED" -> String.valueOf(in.secured());
                    case "COLLATERAL_VALUE" -> num(in.collateralValue());
                    case "COLLATERAL_COVERAGE" -> in.requestedAmount() > 0
                            ? num(in.collateralValue() / in.requestedAmount()) : null;
                    default -> null;
                };
            }
            default:
                return null;
        }
    }

    private static String num(double v) {
        double r = Math.round(v * 10000.0) / 10000.0;
        return r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
