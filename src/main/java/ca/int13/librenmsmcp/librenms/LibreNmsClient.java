package ca.int13.librenmsmcp.librenms;

import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.observability.Telemetry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Client for the LibreNMS v0 API, reproducing upstream {@code LibreNMSClient} behaviour:
 *
 * <ul>
 *   <li>every path part is encoded as one segment ({@link PathSegments});
 *   <li>redirects are never followed and are reported with their target;
 *   <li>non-2xx JSON answers are returned as {@code status: "error"} bodies rather than raised,
 *       unless the caller asks to raise;
 *   <li>non-JSON answers (proxy error pages) raise {@link LibreNmsHttpException}.
 * </ul>
 *
 * <p>Transport failures are retried only for GET requests made while {@link #RETRY_SAFE} is bound
 * to true. Tools opt in through their classification, because some GETs (device discovery) have
 * side effects.
 */
public final class LibreNmsClient {
    /**
     * The JDK client silently re-sends idempotent requests (GET) up to this many times when a
     * connection drops before the response, which would repeat side-effecting GETs such as device
     * discovery. One attempt only: retries are decided by {@link #RETRY_SAFE}. The JDK reads this once,
     * so {@code Main} also sets it before anything else runs.
     */
    public static final String JDK_RETRY_LIMIT_PROPERTY = "jdk.httpclient.redirects.retrylimit";

    static {
        disableJdkRetries();
    }

    /** Bound by the dispatcher to the running tool's {@code retrySafe} classification. */
    public static final ScopedValue<Boolean> RETRY_SAFE = ScopedValue.newInstance();

    static final String ENCODED_SLASH_HINT = "The request path contains an encoded slash (%2F), which some reverse "
            + "proxies reject; for Apache, set 'AllowEncodedSlashes NoDecode'.";
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final List<Duration> RETRY_BACKOFF = List.of(Duration.ofMillis(250), Duration.ofSeconds(1));
    private static final Logger log = LoggerFactory.getLogger(LibreNmsClient.class);

    /** An undecoded response body, for graph endpoints that return images. */
    public record Raw(byte[] content, String contentType) {}

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;

    public LibreNmsClient(Config.LibreNms config) {
        this(config, buildHttpClient(config));
    }

    LibreNmsClient(Config.LibreNms config, HttpClient http) {
        this.baseUrl = config.url().toString().replaceAll("/+$", "") + "/api/v0/";
        this.token = config.token();
        this.timeout = config.timeout();
        this.http = http;
    }

    public static void disableJdkRetries() {
        System.setProperty(JDK_RETRY_LIMIT_PROPERTY, "1");
    }

    // --- JSON endpoints -----------------------------------------------------------------------

    public JsonNode get(Object... segments) {
        return request("GET", Arrays.asList(segments), null, null, false);
    }

    public JsonNode get(JsonNode params, Object... segments) {
        return request("GET", Arrays.asList(segments), params, null, false);
    }

    public JsonNode post(JsonNode body, Object... segments) {
        return request("POST", Arrays.asList(segments), null, body, false);
    }

    public JsonNode put(JsonNode body, Object... segments) {
        return request("PUT", Arrays.asList(segments), null, body, false);
    }

    public JsonNode patch(JsonNode body, Object... segments) {
        return request("PATCH", Arrays.asList(segments), null, body, false);
    }

    public JsonNode delete(JsonNode params, JsonNode body, Object... segments) {
        return request("DELETE", Arrays.asList(segments), params, body, false);
    }

    /**
     * Performs a request and returns the decoded JSON body. A non-2xx JSON body is returned with
     * {@code status: "error"} (see {@link #asErrorBody}) unless {@code raiseForStatus} is set.
     */
    public JsonNode request(String method, List<?> segments, JsonNode params, JsonNode body, boolean raiseForStatus) {
        String path = PathSegments.join(segments);
        HttpResponse<byte[]> resp = send(method, path, params, body);
        int status = resp.statusCode();
        if (isRedirect(resp)) {
            throw httpError(resp, method, path, null, null);
        }
        JsonNode decoded = decode(resp.body());
        if (decoded == null) {
            throw httpError(resp, method, path, null, null);
        }
        if (status >= 200 && status < 300) {
            return decoded;
        }
        JsonNode normalized = decoded;
        if (!isLibreNmsBody(decoded)) {
            ObjectNode marked = asErrorBody(decoded, status);
            if (encodedSlashSuspect(status, false, path)) {
                // Most tools return this body rather than raising, so the hint travels in it too.
                marked.put("hint", ENCODED_SLASH_HINT);
            }
            normalized = marked;
        }
        if (raiseForStatus) {
            throw httpError(resp, method, path, decoded, normalized);
        }
        return normalized;
    }

    // --- raw endpoints ------------------------------------------------------------------------

    /** GET returning the undecoded body; any non-2xx status raises with LibreNMS's message. */
    public Raw getRaw(JsonNode params, Object... segments) {
        String path = PathSegments.join(Arrays.asList(segments));
        HttpResponse<byte[]> resp = send("GET", path, params, null);
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw httpError(resp, "GET", path, isRedirect(resp) ? null : decode(resp.body()), null);
        }
        String contentType = resp.headers().firstValue("content-type").orElse("application/octet-stream");
        return new Raw(resp.body(), contentType.split(";", 2)[0].strip());
    }

    // --- internals ----------------------------------------------------------------------------

    private HttpResponse<byte[]> send(String method, String path, JsonNode params, JsonNode body) {
        URI uri = URI.create(baseUrl + path + queryString(params));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("X-Auth-Token", token)
                .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpRequest request = builder.build();
        boolean retry = method.equals("GET") && RETRY_SAFE.orElse(false);
        for (int attempt = 0; ; attempt++) {
            long started = System.nanoTime();
            try {
                HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                Telemetry.libreNmsRequest(
                        method, (resp.statusCode() / 100) + "xx", Duration.ofNanos(System.nanoTime() - started));
                return resp;
            } catch (IOException e) {
                Telemetry.libreNmsRequest(method, "error", Duration.ofNanos(System.nanoTime() - started));
                if (!retry || attempt >= RETRY_BACKOFF.size()) {
                    throw new LibreNmsTransportException(method, path, e);
                }
                log.warn("LibreNMS {} {} failed ({}); retrying", method, path, e.toString());
                sleep(RETRY_BACKOFF.get(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LibreNmsTransportException(method, path, e);
            }
        }
    }

    /**
     * Encodes query parameters the way httpx does: booleans as {@code true}/{@code false}, null as an
     * empty value, lists as repeated keys. An empty or null object sends no query string.
     */
    static String queryString(JsonNode params) {
        if (params == null || params.isNull() || params.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : params.properties()) {
            List<JsonNode> values =
                    e.getValue().isArray() ? e.getValue().valueStream().toList() : List.of(e.getValue());
            for (JsonNode v : values) {
                pairs.add(urlEncode(e.getKey()) + "=" + urlEncode(paramValue(v)));
            }
        }
        return "?" + String.join("&", pairs);
    }

    private static String paramValue(JsonNode v) {
        if (v.isNull()) {
            return "";
        }
        if (v.isString()) {
            return v.stringValue();
        }
        return v.isContainer() ? Json.write(v) : v.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonNode decode(byte[] body) {
        try {
            return body.length == 0 ? null : Json.parse(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static boolean isRedirect(HttpResponse<?> resp) {
        return REDIRECTS.contains(resp.statusCode())
                && resp.headers().firstValue("location").isPresent();
    }

    /** LibreNMS's own answer shape: {@code status} "ok" or "error". Never rewritten. */
    static boolean isLibreNmsBody(JsonNode body) {
        if (body == null || !body.isObject()) {
            return false;
        }
        JsonNode status = body.get("status");
        return status != null && status.isString() && Set.of("ok", "error").contains(status.stringValue());
    }

    /** Upstream {@code _as_error_body}: mark a non-2xx JSON body so callers can check one field. */
    static ObjectNode asErrorBody(JsonNode body, int statusCode) {
        ObjectNode marked = Json.object();
        if (body.isObject()) {
            marked.setAll((ObjectNode) body);
            JsonNode original = body.get("status");
            marked.put("status", "error");
            if (original != null
                    && !original.isNull()
                    && !(original.isString() && original.stringValue().equals("error"))) {
                marked.set("upstream_status", original);
            }
            if (!marked.has("message")) {
                marked.put("message", "HTTP " + statusCode);
            }
            return marked;
        }
        marked.put("status", "error");
        marked.put("message", "HTTP " + statusCode);
        marked.set("body", body);
        return marked;
    }

    private static boolean encodedSlashSuspect(int status, boolean fromLibreNms, String path) {
        return (status == 400 || status == 404) && !fromLibreNms && path.contains("%2F");
    }

    private static LibreNmsHttpException httpError(
            HttpResponse<byte[]> resp, String method, String path, JsonNode body, JsonNode stored) {
        boolean fromLibreNms = isLibreNmsBody(body);
        String detail;
        if (isRedirect(resp)) {
            detail = errorDetail(resp);
        } else if (body == null) {
            detail = "non-JSON response: " + errorDetail(resp);
        } else {
            JsonNode message = body.isObject() ? body.get("message") : null;
            detail = message == null || message.isNull() ? errorDetail(resp) : pythonStr(message);
        }
        String text = "LibreNMS returned HTTP " + resp.statusCode() + " for " + method + " " + path + ": " + detail;
        if (encodedSlashSuspect(resp.statusCode(), fromLibreNms, path)) {
            text += " (" + ENCODED_SLASH_HINT + ")";
        }
        return new LibreNmsHttpException(text, resp.statusCode(), fromLibreNms, stored != null ? stored : body);
    }

    private static String errorDetail(HttpResponse<byte[]> resp) {
        if (isRedirect(resp)) {
            return "redirected to " + resp.headers().firstValue("location").orElse("<unknown>");
        }
        String text = new String(resp.body(), StandardCharsets.UTF_8).strip();
        if (text.codePointCount(0, text.length()) > 200) {
            text = text.substring(0, text.offsetByCodePoints(0, 200));
        }
        return text.isEmpty() ? "<empty body>" : text;
    }

    /** Python {@code str()} of a decoded JSON value, for messages built from LibreNMS answers. */
    private static String pythonStr(JsonNode value) {
        if (value.isString()) {
            return value.stringValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "True" : "False";
        }
        return value.toString();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static HttpClient buildHttpClient(Config.LibreNms config) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(config.timeout())
                .version(HttpClient.Version.HTTP_1_1);
        if (!config.verifySsl()) {
            log.warn("LIBRENMS_VERIFY_SSL=false: TLS certificates and host names of {} are not verified", config.url());
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            builder.sslContext(trustAll());
        }
        return builder.build();
    }

    private static SSLContext trustAll() {
        try {
            var context = SSLContext.getInstance("TLS");
            context.init(
                    null,
                    new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                    },
                    new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("cannot create TLS context", e);
        }
    }
}
