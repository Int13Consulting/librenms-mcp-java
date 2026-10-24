package ca.int13.librenmsmcp.librenms;

/**
 * LibreNMS could not be reached (connection refused, timeout, TLS failure). The message names the
 * request but never the token.
 */
public final class LibreNmsTransportException extends RuntimeException {
    LibreNmsTransportException(String method, String path, Exception cause) {
        super("Could not reach LibreNMS for " + method + " " + path + ": " + describe(cause), cause);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
