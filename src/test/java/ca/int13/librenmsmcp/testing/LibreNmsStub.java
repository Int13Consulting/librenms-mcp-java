package ca.int13.librenmsmcp.testing;

import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * A scripted LibreNMS on the JDK's built-in HTTP server. Records every request (raw path, decoded
 * query pairs, JSON body) and answers each with the next scripted response or a handler.
 */
public final class LibreNmsStub implements AutoCloseable {
    /** A request as LibreNMS received it. */
    public record Recorded(String method, String path, List<List<String>> query, JsonNode body, String token) {}

    /** A scripted answer. */
    public record Answer(int status, String contentType, byte[] body, Map<String, String> headers) {
        /** Close the connection without answering, as a crashed or unreachable server would. */
        public static final Answer DROP = new Answer(-1, "", new byte[0], Map.of());

        public static Answer json(int status, String json) {
            return new Answer(status, "application/json", json.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static Answer text(int status, String text) {
            return new Answer(status, "text/html", text.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static Answer redirect(int status, String location) {
            return new Answer(status, "text/html", new byte[0], Map.of("Location", location));
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Recorded, Answer> handler = r -> Answer.json(200, "{\"status\":\"ok\"}");

    public LibreNmsStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public Config.LibreNms config() {
        return new Config.LibreNms(url(), "test-token", true, Duration.ofSeconds(5));
    }

    public void answer(Function<Recorded, Answer> handler) {
        this.handler = handler;
    }

    /** Answer the n-th request with the n-th answer; fail any extra request with 599. */
    public void script(List<Answer> answers) {
        var next = new java.util.concurrent.atomic.AtomicInteger();
        this.handler = r -> {
            int i = next.getAndIncrement();
            return i < answers.size() ? answers.get(i) : Answer.json(599, "{\"stub\":\"unexpected request\"}");
        };
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        byte[] content = exchange.getRequestBody().readAllBytes();
        var recorded = new Recorded(
                exchange.getRequestMethod(),
                uri.getRawPath(),
                parseQuery(uri.getRawQuery()),
                content.length == 0 ? null : Json.parse(content),
                exchange.getRequestHeaders().getFirst("X-Auth-Token"));
        requests.add(recorded);
        Answer answer = handler.apply(recorded);
        if (answer == Answer.DROP) {
            exchange.close();
            return;
        }
        if (!answer.contentType().isEmpty()) {
            exchange.getResponseHeaders().add("Content-Type", answer.contentType());
        }
        answer.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
        try (var out = exchange.getResponseBody()) {
            out.write(answer.body());
        }
    }

    /** Decodes like Python's {@code parse_qsl(keep_blank_values=True)}. */
    static List<List<String>> parseQuery(String raw) {
        List<List<String>> pairs = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return pairs;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            pairs.add(List.of(
                    URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8)));
        }
        return pairs;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
