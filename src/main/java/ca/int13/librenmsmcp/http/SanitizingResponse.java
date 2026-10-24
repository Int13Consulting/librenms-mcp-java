package ca.int13.librenmsmcp.http;

import ca.int13.librenmsmcp.json.Json;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Buffers what the MCP SDK writes so error bodies can be cleaned before they leave the server.
 *
 * <p>For a malformed message the SDK answers 400 with its {@code McpError} exception serialized
 * whole, stack trace included. {@link #finish()} replaces any 4xx/5xx body with a plain JSON-RPC
 * error (code and message only). Successful responses are passed through untouched.
 */
final class SanitizingResponse extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private ServletOutputStream stream;
    private PrintWriter writer;
    private int status = SC_OK;

    SanitizingResponse(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void setStatus(int sc) {
        status = sc;
        super.setStatus(sc);
    }

    @Override
    public void sendError(int sc) {
        setStatus(sc);
    }

    @Override
    public void sendError(int sc, String msg) {
        setStatus(sc);
    }

    // The final length is only known in finish().
    @Override
    public void setContentLength(int len) {}

    @Override
    public void setContentLengthLong(long len) {}

    @Override
    public ServletOutputStream getOutputStream() {
        if (stream == null) {
            stream = new ServletOutputStream() {
                @Override
                public void write(int b) {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    buffer.write(b, off, len);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    throw new UnsupportedOperationException("buffered response");
                }
            };
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() {
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), StandardCharsets.UTF_8), false);
        }
        return writer;
    }

    @Override
    public void flushBuffer() {
        if (writer != null) {
            writer.flush();
        }
    }

    /** Writes the (possibly cleaned) body to the real response. */
    void finish() throws IOException {
        flushBuffer();
        byte[] body = buffer.toByteArray();
        if (status >= 400) {
            body = Json.write(clean(body)).getBytes(StandardCharsets.UTF_8);
            super.setContentType("application/json");
        }
        HttpServletResponse real = (HttpServletResponse) getResponse();
        real.setContentLength(body.length);
        real.getOutputStream().write(body);
    }

    /** A JSON-RPC error with only {@code code} and {@code message}, whatever the SDK wrote. */
    static ObjectNode clean(byte[] body) {
        int code = -32600;
        String message = "Invalid request";
        try {
            JsonNode parsed = body.length == 0 ? null : Json.parse(body);
            JsonNode error = parsed == null
                    ? null
                    : parsed.has("jsonRpcError") ? parsed.get("jsonRpcError") : parsed.get("error");
            if (error != null && error.isObject()) {
                code = error.path("code").asInt(code);
                message =
                        error.path("message").isString() ? error.path("message").stringValue() : message;
            }
        } catch (RuntimeException e) {
            // keep the generic error
        }
        ObjectNode error = Json.object().put("code", code).put("message", message);
        ObjectNode out = Json.object().put("jsonrpc", "2.0");
        out.putNull("id");
        out.set("error", error);
        return out;
    }
}
