package com.example.processor;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * S3 ObjectCreated -> mark the matching document PROCESSED.
 *
 * <p>The function needs no S3 permissions: the event already carries the key and size. Its only
 * AWS access is reading one SSM SecureString. Any failure throws, so Lambda's async retry (2
 * attempts) kicks in and the CloudWatch Errors alarm fires if the problem persists. The service
 * endpoint is idempotent, so retries and duplicate S3 notifications are safe.
 */
public class S3EventHandler implements RequestHandler<S3Event, Integer> {

    static final String KEY_PREFIX = "uploads/";
    private static final System.Logger LOG = System.getLogger(S3EventHandler.class.getName());

    private final Supplier<String> tokenSupplier;
    private final ProcessedNotifier notifier;

    /** Used by the Lambda runtime. */
    public S3EventHandler() {
        this(new SsmTokenSupplier(requireEnv("TOKEN_PARAMETER_NAME")),
                new DocServiceClient(requireEnv("DOC_SERVICE_URL"),
                        Duration.ofSeconds(Long.parseLong(
                                System.getenv().getOrDefault("HTTP_TIMEOUT_SECONDS", "10")))));
    }

    /** Used by tests. */
    S3EventHandler(Supplier<String> tokenSupplier, ProcessedNotifier notifier) {
        this.tokenSupplier = tokenSupplier;
        this.notifier = notifier;
    }

    @Override
    public Integer handleRequest(S3Event event, Context context) {
        List<S3EventNotificationRecord> records = event.getRecords();
        int processed = 0;
        if (records == null) {
            return processed;
        }
        for (S3EventNotificationRecord record : records) {
            var object = record.getS3().getObject();
            // S3 URL-encodes keys in notifications ("+" for spaces, %XX for specials)
            String key = object.getUrlDecodedKey();

            if (!key.startsWith(KEY_PREFIX)) {
                LOG.log(System.Logger.Level.WARNING, "Ignoring object outside " + KEY_PREFIX + ": " + key);
                continue;
            }
            try {
                notifier.markProcessed(key, tokenSupplier.get());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to mark " + key + " PROCESSED", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while marking " + key + " PROCESSED", e);
            }
            LOG.log(System.Logger.Level.INFO,
                    "Marked " + key + " (" + object.getSizeAsLong() + " bytes) PROCESSED");
            processed++;
        }
        return processed;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value;
    }
}
