package com.example.processor;

import java.io.IOException;

/** Tells the Document Service that the object stored under {@code key} has been uploaded. */
interface ProcessedNotifier {

    /**
     * @throws IOException if the service is unreachable or answers with a non-2xx status
     */
    void markProcessed(String key, String token) throws IOException, InterruptedException;
}
