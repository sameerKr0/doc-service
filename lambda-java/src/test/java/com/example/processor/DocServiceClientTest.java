package com.example.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the real HTTP client against a throwaway in-process server (no mocks, no network). */
class DocServiceClientTest {

    private HttpServer server;
    private DocServiceClient client;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> tokenHeader = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tokenHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
        // trailing slash on purpose: the client must not produce "//internal/..."
        client = new DocServiceClient("http://127.0.0.1:" + server.getAddress().getPort() + "/",
                Duration.ofSeconds(5));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsKeyAndTokenToInternalEndpoint() throws Exception {
        client.markProcessed("uploads/a/one.pdf", "tok");

        assertEquals("/internal/documents/processed", path.get());
        assertEquals("{\"s3Key\":\"uploads/a/one.pdf\"}", body.get());
        assertEquals("tok", tokenHeader.get());
    }

    @Test
    void nonSuccessStatusRaisesIoException() {
        responseStatus.set(500);

        assertThrows(IOException.class, () -> client.markProcessed("uploads/a/one.pdf", "tok"));
    }

    @Test
    void forbiddenIsAlsoAFailure() {
        responseStatus.set(403);

        assertThrows(IOException.class, () -> client.markProcessed("uploads/a/one.pdf", "bad"));
    }

    @Test
    void jsonBodyEscapesQuotesBackslashesAndControlCharacters() {
        assertEquals("{\"s3Key\":\"uploads/x/a\\\"b\\\\c\\u000ad.pdf\"}",
                DocServiceClient.jsonBody("uploads/x/a\"b\\c\nd.pdf"));
    }
}
