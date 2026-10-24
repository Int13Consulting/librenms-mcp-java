package ca.int13.librenmsmcp.librenms;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Encodes tool arguments as LibreNMS API path segments, matching upstream {@code path_segment}.
 *
 * <p>Arguments come from the model and may be prompt-injected, so each value becomes exactly one
 * segment: every reserved character is percent-encoded, and blank values or {@code .}/{@code ..}
 * parts (backslashes count as separators) are rejected before any request is made. Values are
 * otherwise sent verbatim, surrounding spaces included.
 */
public final class PathSegments {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PathSegments() {}

    public static String join(List<?> segments) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("A LibreNMS API path needs at least one segment");
        }
        return segments.stream().map(PathSegments::encode).collect(Collectors.joining("/"));
    }

    public static String encode(Object value) {
        String text = String.valueOf(value);
        boolean dotSegment = false;
        for (String part : text.replace('\\', '/').split("/", -1)) {
            if (part.equals(".") || part.equals("..")) {
                dotSegment = true;
                break;
            }
        }
        if (text.isBlank() || dotSegment) {
            throw new InvalidPathSegmentException(text);
        }
        return quote(text);
    }

    /** Python {@code urllib.parse.quote(text, safe="")}: only A-Z a-z 0-9 {@code _.-~} stay as is. */
    static String quote(String text) {
        var out = new StringBuilder(text.length());
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '.'
                    || c == '-'
                    || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
            }
        }
        return out.toString();
    }

    /** Raised for a value that may not be used as a path segment; the message matches upstream. */
    public static final class InvalidPathSegmentException extends IllegalArgumentException {
        InvalidPathSegmentException(String value) {
            super("Invalid path segment: " + PythonText.repr(value));
        }
    }
}
