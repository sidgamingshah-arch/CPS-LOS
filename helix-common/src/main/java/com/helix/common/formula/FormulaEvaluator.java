package com.helix.common.formula;

import java.util.Map;

/**
 * A tiny, safe arithmetic evaluator for configurable financial formulas —
 * {@code "REVENUE - COGS"}, {@code "INVENTORY / COGS * 365"},
 * {@code "(EBITDA - CAPEX) / DEBT_SERVICE"}. Supports {@code + - * /}, parentheses,
 * unary minus, decimal literals, and variable references (a canonical line / driver
 * key resolved from the supplied map; an unknown or null reference is 0). Division
 * by ~0 yields 0 rather than NaN/Infinity, matching the platform's {@code safeDiv}
 * convention. No identifiers beyond the variable map are reachable — this is NOT a
 * general expression language, so it can be driven by maker-checker config safely.
 */
public final class FormulaEvaluator {

    private FormulaEvaluator() { }

    public static double eval(String expr, Map<String, Double> vars) {
        if (expr == null || expr.isBlank()) return 0.0;
        return new Parser(expr, vars).parse();
    }

    /** Convenience: evaluate and round to 3 dp (the platform ratio convention). */
    public static double evalRounded(String expr, Map<String, Double> vars) {
        double v = eval(expr, vars);
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class Parser {
        private final String s;
        private final Map<String, Double> vars;
        private int pos;

        Parser(String s, Map<String, Double> vars) {
            this.s = s;
            this.vars = vars;
        }

        double parse() {
            double v = expr();
            skipWs();
            if (pos < s.length()) {
                throw new IllegalArgumentException("Unexpected '" + s.charAt(pos) + "' in formula: " + s);
            }
            return v;
        }

        // expr := term (('+'|'-') term)*
        private double expr() {
            double v = term();
            while (true) {
                skipWs();
                if (peek() == '+') { pos++; v += term(); }
                else if (peek() == '-') { pos++; v -= term(); }
                else break;
            }
            return v;
        }

        // term := factor (('*'|'/') factor)*
        private double term() {
            double v = factor();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '*') { pos++; v *= factor(); }
                else if (c == '/') {
                    pos++;
                    double d = factor();
                    v = Math.abs(d) < 1e-9 ? 0.0 : v / d;   // safe division
                } else break;
            }
            return v;
        }

        // factor := '-' factor | '(' expr ')' | number | identifier
        private double factor() {
            skipWs();
            char c = peek();
            if (c == '-') { pos++; return -factor(); }
            if (c == '+') { pos++; return factor(); }
            if (c == '(') {
                pos++;
                double v = expr();
                skipWs();
                if (peek() != ')') throw new IllegalArgumentException("Missing ')' in formula: " + s);
                pos++;
                return v;
            }
            if (Character.isDigit(c) || c == '.') return number();
            if (Character.isLetter(c) || c == '_') return identifier();
            throw new IllegalArgumentException("Unexpected '" + c + "' in formula: " + s);
        }

        private double number() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            return Double.parseDouble(s.substring(start, pos));
        }

        private double identifier() {
            int start = pos;
            while (pos < s.length()
                    && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
            }
            String id = s.substring(start, pos);
            Double v = vars.get(id);
            return v == null ? 0.0 : v;
        }

        private char peek() { return pos < s.length() ? s.charAt(pos) : '\0'; }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
