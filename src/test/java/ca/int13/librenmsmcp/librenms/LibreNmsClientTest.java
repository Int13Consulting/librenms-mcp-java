package ca.int13.librenmsmcp.librenms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.int13.librenmsmcp.json.Json;
import ca.int13.librenmsmcp.testing.LibreNmsStub;
import ca.int13.librenmsmcp.testing.LibreNmsStub.Answer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Error normalization and request shape; cases follow upstream {@code tests/test_client_errors.py}. */
class LibreNmsClientTest {
    private LibreNmsStub stub;
    private LibreNmsClient client;

    @BeforeEach
    void start() {
        stub = new LibreNmsStub();
        client = new LibreNmsClient(stub.config());
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void sendsTokenAndEncodedPath() {
        client.get("devices", "core1/discover");
        LibreNmsStub.Recorded r = stub.requests().getFirst();
        assertThat(r.path()).isEqualTo("/api/v0/devices/core1%2Fdiscover");
        assertThat(r.token()).isEqualTo("test-token");
    }

    @Test
    void libreNmsErrorBodyPassesThroughUnchanged() {
        stub.answer(r -> Answer.json(404, "{\"status\":\"error\",\"message\":\"Device foo not found\"}"));
        assertThat(Json.write(client.get("devices", "foo")))
                .isEqualTo("{\"status\":\"error\",\"message\":\"Device foo not found\"}");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            401 | {"message":"Unauthenticated."}                 | {"message":"Unauthenticated.","status":"error"}
            503 | ["upstream","down"]                            | {"status":"error","message":"HTTP 503","body":["upstream","down"]}
            503 | {"status":503,"message":"Unavailable"}          | {"status":"error","message":"Unavailable","upstream_status":503}
            502 | {"detail":"bad gateway"}                       | {"detail":"bad gateway","status":"error","message":"HTTP 502"}
            404 | {"status":"ok","message":"No results","count":0} | {"status":"ok","message":"No results","count":0}
            """)
    void nonSuccessJsonIsMarkedAsError(int status, String body, String expected) {
        stub.answer(r -> Answer.json(status, body));
        assertThat(Json.write(client.get("devices"))).isEqualTo(expected);
    }

    @Test
    void redirectsAreNotFollowed() {
        stub.answer(r -> Answer.redirect(302, "https://sso.example/login"));
        assertThatThrownBy(() -> client.get("devices"))
                .isInstanceOf(LibreNmsHttpException.class)
                .hasMessage("LibreNMS returned HTTP 302 for GET devices: redirected to https://sso.example/login");
        assertThat(stub.requests()).hasSize(1);
    }

    @Test
    void nonJsonBodyRaisesWithStatusAndEncodedSlashHint() {
        stub.answer(r -> Answer.text(404, "<html>Not Found</html>"));
        assertThatThrownBy(() -> client.get("devices", "sw1", "ports", "Te2/7"))
                .isInstanceOf(LibreNmsHttpException.class)
                .hasMessage("LibreNMS returned HTTP 404 for GET devices/sw1/ports/Te2%2F7: non-JSON response: "
                        + "<html>Not Found</html> (" + LibreNmsClient.ENCODED_SLASH_HINT + ")");
    }

    @Test
    void emptySuccessBodyIsNotJson() {
        stub.answer(r -> Answer.text(200, ""));
        assertThatThrownBy(() -> client.get("system"))
                .hasMessage("LibreNMS returned HTTP 200 for GET system: non-JSON response: <empty body>");
    }

    @Test
    void gatewayJson404OnEncodedSlashCarriesHintInBody() {
        stub.answer(r -> Answer.json(404, "{\"message\":\"no Route matched\"}"));
        assertThat(client.get("devices", "a/b").get("hint").stringValue()).isEqualTo(LibreNmsClient.ENCODED_SLASH_HINT);
    }

    @Test
    void raiseForStatusKeepsNormalizedBody() {
        stub.answer(r -> Answer.json(401, "{\"message\":\"Unauthenticated.\"}"));
        assertThatThrownBy(() -> client.request("GET", List.of("devices"), null, null, true))
                .isInstanceOfSatisfying(LibreNmsHttpException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(401);
                    assertThat(e.fromLibreNms()).isFalse();
                    assertThat(Json.write(e.body()))
                            .isEqualTo("{\"message\":\"Unauthenticated.\",\"status\":\"error\"}");
                })
                .hasMessage("LibreNMS returned HTTP 401 for GET devices: Unauthenticated.");
    }

    @Test
    void queryParametersEncodeLikeHttpx() {
        client.get(
                Json.parse(
                        "{\"type\":\"hostname\",\"query\":\"core 1/a\",\"flag\":true,\"n\":2,\"none\":null,\"multi\":[\"a\",\"b\"]}"),
                "devices");
        assertThat(stub.requests().getFirst().query())
                .containsExactly(
                        List.of("type", "hostname"),
                        List.of("query", "core 1/a"),
                        List.of("flag", "true"),
                        List.of("n", "2"),
                        List.of("none", ""),
                        List.of("multi", "a"),
                        List.of("multi", "b"));
    }

    @Test
    void emptyQueryObjectSendsNoQueryString() {
        client.get(Json.object(), "devices");
        assertThat(stub.requests().getFirst().query()).isEmpty();
    }

    @Test
    void rawGetReturnsBytesAndRaisesWithLibreNmsMessage() {
        stub.answer(r -> new Answer(200, "image/png; charset=binary", new byte[] {1, 2, 3}, java.util.Map.of()));
        LibreNmsClient.Raw raw = client.getRaw(null, "devices", "core1", "device_processor");
        assertThat(raw.content()).containsExactly(1, 2, 3);
        assertThat(raw.contentType()).isEqualTo("image/png");

        stub.answer(r -> Answer.json(404, "{\"status\":\"error\",\"message\":\"Device core1 not found\"}"));
        assertThatThrownBy(() -> client.getRaw(null, "devices", "core1", "x"))
                .hasMessage("LibreNMS returned HTTP 404 for GET devices/core1/x: Device core1 not found");
    }

    @Test
    void transportFailuresAreRetriedOnlyForRetrySafeGets() {
        stub.answer(r -> Answer.DROP);
        assertThatThrownBy(() -> client.get("devices")).isInstanceOf(LibreNmsTransportException.class);
        assertThat(stub.requests()).as("no retry outside a retry-safe tool").hasSize(1);

        stub.reset();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        stub.answer(r -> calls.incrementAndGet() < 3 ? Answer.DROP : Answer.json(200, "{\"status\":\"ok\"}"));
        var result = ScopedValue.where(LibreNmsClient.RETRY_SAFE, true).call(() -> client.get("devices"));
        assertThat(result.get("status").stringValue()).isEqualTo("ok");
        assertThat(stub.requests()).hasSize(3);

        stub.reset();
        stub.answer(r -> Answer.DROP);
        assertThatThrownBy(() -> ScopedValue.where(LibreNmsClient.RETRY_SAFE, true)
                        .call(() -> client.post(Json.object(), "devices")))
                .isInstanceOf(LibreNmsTransportException.class);
        assertThat(stub.requests()).as("never retry a POST").hasSize(1);
    }

    @Test
    void dotSegmentsAreRejectedWithoutARequest() {
        assertThatThrownBy(() -> client.get("devices", "../rules/5"))
                .isInstanceOf(PathSegments.InvalidPathSegmentException.class)
                .hasMessage("Invalid path segment: '../rules/5'");
        assertThat(stub.requests()).isEmpty();
    }
}
