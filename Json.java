package com.petsa.util;

import java.math.BigDecimal;
import java.util.*;

/** Small dependency-free JSON reader/writer. Numbers are read as BigDecimal. */
public final class Json {
    private Json() {}

    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        JsonException(String m) {
            super(m);
        }
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw p.err("Unexpected trailing data");
        return v;
    }

    private static final class Parser {
        final String s;
        int i = 0;

        Parser(String s) {
            this.s = s;
        }

        JsonException err(String m) {
            return new JsonException(m + " at position " + i);
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value(int depth) {
            if (depth > 20) throw err("Nesting too deep");
            if (i >= s.length()) throw err("Unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default: return number();
            }
        }

        void literal(String word) {
            if (!s.startsWith(word, i)) throw err("Invalid value");
            i += word.length();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("Expected property name");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("Expected ':'");
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw err("Unexpected end of input");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("Expected ',' or '}'");
                if (m.size() > 200) throw err("Too many properties");
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw err("Unexpected end of input");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("Expected ',' or ']'");
                if (l.size() > 10000) throw err("Too many items");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw err("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw err("Control character in string");
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw err("Unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("Bad unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err("Bad unicode escape");
                        }
                        i += 4;
                        break;
                    default: throw err("Bad escape");
                }
            }
        }

        BigDecimal number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            int digits = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) { i++; digits++; }
            if (digits == 0) throw err("Invalid value");
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int frac = 0;
                while (i < s.length() && Character.isDigit(s.charAt(i))) { i++; frac++; }
                if (frac == 0) throw err("Invalid number");
            }
            if (i - start > 30) throw err("Number too long");
            return new BigDecimal(s.substring(start, i));
        }
    }

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String str) quote(sb, str);
        else if (o instanceof BigDecimal d) sb.append(d.toPlainString());
        else if (o instanceof Number n) sb.append(n);
        else if (o instanceof Boolean b) sb.append(b);
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else quote(sb, o.toString());
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == '\u2028' || c == '\u2029')
                        sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Convenience for building response objects: obj("a", 1, "b", 2). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
