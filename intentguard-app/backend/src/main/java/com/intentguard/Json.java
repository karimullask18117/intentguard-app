package com.intentguard;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal, strict JSON reader/writer (no external dependencies). */
public final class Json {
    private Json() {}

    public static Object parse(String s) {
        if (s == null) throw new IllegalArgumentException("Invalid JSON: empty body");
        P p = new P(s);
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw p.err("unexpected trailing characters");
        return v;
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    // ---------- writer ----------
    private static void write(Object o, StringBuilder sb) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String s) { quote(s, sb); return; }
        if (o instanceof Boolean b) { sb.append(b); return; }
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (n instanceof Double || n instanceof Float) {
                if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d); else sb.append(d);
            } else sb.append(n);
            return;
        }
        if (o instanceof Enum<?> e) { quote(e.name().toLowerCase(), sb); return; }
        if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                write(x, sb);
            }
            sb.append(']');
            return;
        }
        if (o.getClass().isRecord()) {
            sb.append('{');
            boolean first = true;
            for (RecordComponent rc : o.getClass().getRecordComponents()) {
                if (!first) sb.append(',');
                first = false;
                quote(rc.getName(), sb);
                sb.append(':');
                try {
                    var acc = rc.getAccessor();
                    acc.setAccessible(true);
                    write(acc.invoke(o), sb);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            sb.append('}');
            return;
        }
        quote(o.toString(), sb);
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- parser ----------
    private static final class P {
        final String s;
        int i = 0;
        int depth = 0;

        P(String s) { this.s = s; }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException("Invalid JSON at position " + i + ": " + m);
        }

        void ws() {
            while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++;
        }

        char peek() {
            if (i >= s.length()) throw err("unexpected end of input");
            return s.charAt(i);
        }

        Object value() {
            ws();
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> number();
            };
        }

        Object lit(String word, Object val) {
            if (!s.startsWith(word, i)) throw err("unexpected token");
            i += word.length();
            return val;
        }

        Map<String, Object> object() {
            if (++depth > 64) throw err("nesting too deep");
            i++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { i++; depth--; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw err("expected string key");
                String k = string();
                ws();
                if (peek() != ':') throw err("expected ':'");
                i++;
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; break; }
                throw err("expected ',' or '}'");
            }
            depth--;
            return m;
        }

        List<Object> array() {
            if (++depth > 64) throw err("nesting too deep");
            i++; // [
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') { i++; depth--; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; break; }
                throw err("expected ',' or ']'");
            }
            depth--;
            return l;
        }

        String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    if (i >= s.length()) throw err("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw err("bad unicode escape");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("bad unicode escape");
                            }
                            i += 4;
                        }
                        default -> throw err("bad escape");
                    }
                } else if (c < 0x20) {
                    throw err("control character in string");
                } else sb.append(c);
            }
            return sb.toString();
        }

        Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            if (!t.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) throw err("invalid value");
            return Double.parseDouble(t);
        }
    }
}
