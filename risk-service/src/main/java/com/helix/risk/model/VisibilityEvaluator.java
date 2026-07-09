package com.helix.risk.model;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a question's {@code visibleWhen} guard against the current scalar
 * answers. A small, safe expression grammar (single binary clause) — never
 * arbitrary code:
 * <ul>
 *   <li>{@code answered(q)} — q has any value</li>
 *   <li>{@code q == 'x'} · {@code q != 'x'}</li>
 *   <li>{@code q in ['a','b']}</li>
 *   <li>{@code q >= n} · {@code <=} · {@code >} · {@code <} (numeric)</li>
 * </ul>
 * No expression (null/blank) → visible. Unparseable → visible (fail-open: better
 * to show a question than to silently drop it from scoring/constraints).
 */
public final class VisibilityEvaluator {

    private VisibilityEvaluator() { }

    private static final Pattern ANSWERED = Pattern.compile("^answered\\(\\s*([\\w.]+)\\s*\\)$");
    private static final Pattern EQ = Pattern.compile("^([\\w.]+)\\s*(==|!=)\\s*'([^']*)'$");
    private static final Pattern IN = Pattern.compile("^([\\w.]+)\\s+in\\s+\\[(.*)]$");
    private static final Pattern CMP = Pattern.compile("^([\\w.]+)\\s*(>=|<=|>|<)\\s*(-?[0-9.]+)$");

    public static boolean visible(String visibleWhen, Map<String, String> answers) {
        if (visibleWhen == null || visibleWhen.isBlank()) return true;
        String e = visibleWhen.trim();
        try {
            Matcher m;
            if ((m = ANSWERED.matcher(e)).matches()) {
                String v = answers.get(m.group(1));
                return v != null && !v.isBlank();
            }
            if ((m = EQ.matcher(e)).matches()) {
                String v = answers.getOrDefault(m.group(1), "");
                boolean eq = v.equalsIgnoreCase(m.group(3));
                return "==".equals(m.group(2)) ? eq : !eq;
            }
            if ((m = IN.matcher(e)).matches()) {
                String v = answers.getOrDefault(m.group(1), "");
                return Arrays.stream(m.group(2).split(","))
                        .map(s -> s.trim().replaceAll("^'|'$", ""))
                        .anyMatch(opt -> opt.equalsIgnoreCase(v));
            }
            if ((m = CMP.matcher(e)).matches()) {
                String v = answers.get(m.group(1));
                if (v == null || v.isBlank()) return false;
                double lhs = Double.parseDouble(v);
                double rhs = Double.parseDouble(m.group(3));
                return switch (m.group(2)) {
                    case ">=" -> lhs >= rhs;
                    case "<=" -> lhs <= rhs;
                    case ">" -> lhs > rhs;
                    case "<" -> lhs < rhs;
                    default -> true;
                };
            }
        } catch (Exception ignored) {
            // fall through to visible
        }
        return true;
    }
}
