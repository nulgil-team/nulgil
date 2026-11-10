package kr.routine.bridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Output-only JSON encoder for the public, immutable presentation records. */
public final class JsonWriter {
    private JsonWriter() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting is too deep");
        if (value == null) { out.append("null"); return; }
        if (value instanceof String s) { quote(out, s); return; }
        if (value instanceof Character c) { quote(out, c.toString()); return; }
        if (value instanceof Enum<?> e) { quote(out, e.name()); return; }
        if (value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Number n) {
            if (!(n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long
                    || n instanceof BigInteger || n instanceof BigDecimal || n instanceof Float || n instanceof Double))
                throw new IllegalArgumentException("Unsupported JSON number");
            if (n instanceof Double d && !Double.isFinite(d) || n instanceof Float f && !Float.isFinite(f))
                throw new IllegalArgumentException("Non-finite JSON number");
            out.append(n); return;
        }
        if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                append(out, list.get(i), depth + 1);
            }
            out.append(']'); return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append('{'); boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON keys must be strings");
                if (!first) out.append(','); first = false;
                quote(out, key); out.append(':'); append(out, entry.getValue(), depth + 1);
            }
            out.append('}'); return;
        }
        if (value.getClass().isRecord()) {
            out.append('{'); boolean first = true;
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                if (!first) out.append(','); first = false;
                quote(out, component.getName()); out.append(':');
                try { append(out, component.getAccessor().invoke(value), depth + 1); }
                catch (IllegalAccessException | InvocationTargetException failure) {
                    throw new IllegalArgumentException("Cannot encode presentation record", failure);
                }
            }
            out.append('}'); return;
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        // Escape both paired and lone UTF-16 surrogates, without lossy UTF-8 replacement.
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4)
                            out.append("0123456789abcdef".charAt((c >> shift) & 15));
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
