package com.example.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class S3EventHandlerTest {

    @Mock
    ProcessedNotifier notifier;

    @Mock
    Supplier<String> tokenSupplier;

    S3EventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new S3EventHandler(tokenSupplier, notifier);
    }

    /**
     * Builds an S3 event whose records report the given (already URL-decoded) keys. The AWS
     * library does the URL decoding in getUrlDecodedKey(); here we only check that our handler
     * relies on it.
     */
    private static S3Event eventFor(String... keys) {
        List<S3EventNotificationRecord> records = Stream.of(keys).map(key -> {
            S3EventNotificationRecord record = mock(S3EventNotificationRecord.class, RETURNS_DEEP_STUBS);
            when(record.getS3().getObject().getUrlDecodedKey()).thenReturn(key);
            return record;
        }).toList();
        return new S3Event(records);
    }

    @Test
    void marksEveryUploadedObjectProcessed() throws Exception {
        when(tokenSupplier.get()).thenReturn("tok");

        int processed = handler.handleRequest(eventFor("uploads/a/one.pdf", "uploads/b/two.pdf"), null);

        assertEquals(2, processed);
        verify(notifier).markProcessed("uploads/a/one.pdf", "tok");
        verify(notifier).markProcessed("uploads/b/two.pdf", "tok");
    }

    @Test
    void usesTheUrlDecodedKeyFromTheEvent() throws Exception {
        when(tokenSupplier.get()).thenReturn("tok");

        handler.handleRequest(eventFor("uploads/abc/my file(1).pdf"), null);

        verify(notifier).markProcessed("uploads/abc/my file(1).pdf", "tok");
    }

    @Test
    void ignoresObjectsOutsideUploadsPrefix() {
        int processed = handler.handleRequest(eventFor("other/x.pdf"), null);

        assertEquals(0, processed);
        verifyNoInteractions(notifier, tokenSupplier);
    }

    @Test
    void serviceFailurePropagatesSoLambdaRetries() throws Exception {
        when(tokenSupplier.get()).thenReturn("tok");
        doThrow(new IOException("HTTP 500")).when(notifier).markProcessed(anyString(), anyString());

        assertThrows(UncheckedIOException.class,
                () -> handler.handleRequest(eventFor("uploads/a/one.pdf"), null));
    }

    @Test
    void emptyEventIsANoop() throws Exception {
        assertEquals(0, handler.handleRequest(new S3Event(List.of()), null));

        verify(notifier, never()).markProcessed(anyString(), anyString());
    }
}
