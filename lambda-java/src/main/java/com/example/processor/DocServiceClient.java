package com.example.processor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/** Calls {@code POST /internal/documents/processed} on the Document Service. */
final class DocServiceClient implements ProcessedNotifier {

    private static final String PATH = "/internal/documents/processed";

    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;

    DocServiceClient(String baseUrl, Duration timeout) {
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + PATH);
        this.timeout = timeout;
    }

    @Override
    public void markProcessed(String key, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", token)
                .POST(BodyPublishers.ofString(jsonBody(key)))
                .build();
        HttpResponse<Void> response = http.send(request, BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Document service answered HTTP " + response.statusCode());
        }
    }

    /** Tiny hand-rolled JSON body so the function needs no JSON library. */
    static String jsonBody(String key) {
        StringBuilder sb = new StringBuilder("{\"s3Key\":\"");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"}").toString();
    }
}
