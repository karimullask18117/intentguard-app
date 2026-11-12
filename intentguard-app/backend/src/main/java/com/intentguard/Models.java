package com.intentguard;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small immutable data types shared by the engine and the API. */
public final class Models {
    private Models() {}

    public enum Decision { ALLOW, CONFIRM, BLOCK }

    public record Finding(String sev, String text) {}

    public record EvaluationResult(int score, Decision decision, List<Finding> findings) {}

    public record ParsedIntent(List<String> actionTypes, List<String> emails, List<String> domains,
                               List<String> files, List<Double> amounts) {}

    public record AuditEntry(String id, String time, String tool, String target, String source,
                             int score, Decision decision, String outcome, List<String> reasons) {}

    public record Scenario(String name, String intent, List<ActionRequest> actions) {}

    public record ActionRequest(String tool, String target, String content, Double amount, String source) {
        public static final Set<String> SOURCES =
                Set.of("user", "agent", "email", "webpage", "document", "tool_output");

        /** Builds a validated action from a parsed JSON object. */
        public static ActionRequest fromMap(Map<String, Object> m) {
            String tool = str(m, "tool", 64, true);
            String target = str(m, "target", 2000, false);
            String content = str(m, "content", 10000, false);
            String source = str(m, "source", 32, false);
            source = source.isBlank() ? "agent" : source.toLowerCase();
            if (!SOURCES.contains(source))
                throw new IllegalArgumentException("source must be one of " + new java.util.TreeSet<>(SOURCES));
            Double amount = null;
            Object a = m.get("amount");
            if (a != null && !(a instanceof String s && s.isBlank())) {
                if (a instanceof Number n) amount = n.doubleValue();
                else if (a instanceof String s) {
                    try { amount = Double.parseDouble(s.trim()); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("amount must be a number"); }
                } else throw new IllegalArgumentException("amount must be a number");
                if (amount < 0 || amount.isNaN() || amount.isInfinite())
                    throw new IllegalArgumentException("amount must be a non-negative number");
            }
            return new ActionRequest(tool, target, content, amount, source);
        }

        static String str(Map<String, Object> m, String key, int max, boolean required) {
            Object v = m.get(key);
            if (v == null) {
                if (required) throw new IllegalArgumentException(key + " is required");
                return "";
            }
            if (!(v instanceof String s)) throw new IllegalArgumentException(key + " must be a string");
            s = s.strip();
            if (required && s.isEmpty()) throw new IllegalArgumentException(key + " is required");
            if (s.length() > max) throw new IllegalArgumentException(key + " is too long (max " + max + ")");
            return s;
        }
    }
}
