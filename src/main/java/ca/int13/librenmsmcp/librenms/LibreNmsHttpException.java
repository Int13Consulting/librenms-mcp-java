package ca.int13.librenmsmcp.librenms;

import tools.jackson.databind.JsonNode;

/**
 * A LibreNMS response the caller cannot use: a redirect, a non-JSON body, or an error status where
 * the caller asked to raise on one. The message matches upstream's {@code LibreNMSHTTPError}.
 *
 * <p>{@code fromLibreNms} is true only for LibreNMS's own body shape ({@code status} "ok"/"error"),
 * which tells a LibreNMS "not found" apart from a proxy or gateway rejecting the request.
 */
public final class LibreNmsHttpException extends RuntimeException {
    private final int statusCode;
    private final boolean fromLibreNms;
    private final transient JsonNode body;

    LibreNmsHttpException(String message, int statusCode, boolean fromLibreNms, JsonNode body) {
        super(message);
        this.statusCode = statusCode;
        this.fromLibreNms = fromLibreNms;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean fromLibreNms() {
        return fromLibreNms;
    }

    /** The decoded body (normalized for JSON endpoints), or null when there was none. */
    public JsonNode body() {
        return body;
    }
}
