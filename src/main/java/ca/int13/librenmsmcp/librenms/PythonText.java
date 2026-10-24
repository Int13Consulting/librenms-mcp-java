package ca.int13.librenmsmcp.librenms;

import tools.jackson.databind.JsonNode;

/**
 * Python's {@code repr()} of a string, for error messages that must match upstream text exactly
 * (for example {@code Invalid path segment: '../x'}).
 */
public final class PythonText {
    private PythonText() {}

    /** Python {@code str()} of a decoded JSON value: strings bare, everything else as {@link #repr(JsonNode)}. */
    public static String str(JsonNode value) {
        return value != null && value.isString() ? value.stringValue() : repr(value);
    }

    /** Python {@code repr()} of a decoded JSON value ({@code {'a': 1, 'b': [True, None]}}). */
    public static String repr(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "None";
        }
        if (value.isString()) {
            return repr(value.stringValue());
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "True" : "False";
        }
        if (value.isIntegralNumber()) {
            return value.bigIntegerValue().toString();
        }
        if (value.isNumber()) {
            return floatRepr(value.doubleValue());
        }
        if (value.isArray()) {
            var parts = new java.util.ArrayList<String>();
            value.forEach(v -> parts.add(repr(v)));
            return "[" + String.join(", ", parts) + "]";
        }
        var parts = new java.util.ArrayList<String>();
        for (var e : value.properties()) {
            parts.add(repr(e.getKey()) + ": " + repr(e.getValue()));
        }
        return "{" + String.join(", ", parts) + "}";
    }

    /** Python's shortest round-trip float repr: {@code 1.0}, {@code 0.1}, {@code 1e-05}, {@code 1e+16}. */
    static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0) {
            return (1 / d < 0) ? "-0.0" : "0.0";
        }
        var bd = new java.math.BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = bd.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - bd.scale(); // decimal exponent of the first digit
        String sign = d < 0 ? "-" : "";
        if (exponent < -4 || exponent >= 16) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
        }
        String plain = bd.abs().toPlainString();
        return sign + (plain.contains(".") ? plain : plain + ".0");
    }

    /** Python {@code str.casefold()}, close enough for matching names (handles ß, final sigma). */
    public static String casefold(String s) {
        return s.toUpperCase(java.util.Locale.ROOT).toLowerCase(java.util.Locale.ROOT);
    }

    public static String repr(String value) {
        char quote = value.indexOf('\'') >= 0 && value.indexOf('"') < 0 ? '"' : '\'';
        var out = new StringBuilder(value.length() + 2).append(quote);
        value.codePoints().forEach(cp -> {
            switch (cp) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (cp == quote) {
                        out.append('\\').append((char) cp);
                    } else if (printable(cp)) {
                        out.appendCodePoint(cp);
                    } else if (cp < 0x100) {
                        out.append(String.format("\\x%02x", cp));
                    } else if (cp < 0x10000) {
                        out.append(String.format("\\u%04x", cp));
                    } else {
                        out.append(String.format("\\U%08x", cp));
                    }
                }
            }
        });
        return out.append(quote).toString();
    }

    /** Approximates {@code str.isprintable()}: everything but controls, separators (except space) and unassigned. */
    private static boolean printable(int cp) {
        if (cp == ' ') {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.SURROGATE,
                    Character.PRIVATE_USE,
                    Character.UNASSIGNED,
                    Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR,
                    Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }
}
