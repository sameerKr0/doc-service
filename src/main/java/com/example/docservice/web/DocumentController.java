package com.example.docservice.web;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.example.docservice.service.DocumentService;
import com.example.docservice.web.ApiModels.CreateDocumentRequest;
import com.example.docservice.web.ApiModels.DocumentResponse;
import com.example.docservice.web.ApiModels.DownloadUrlResponse;
import com.example.docservice.web.ApiModels.MarkProcessedRequest;
import com.example.docservice.web.ApiModels.UploadResponse;
import jakarta.validation.Valid;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DocumentController {

    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentService service;
    private final byte[] internalToken;

    public DocumentController(DocumentService service,
                              @Value("${app.internal-api-token:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken.getBytes(UTF_8);
    }

    /** Registers a document and returns the pre-signed URL the client uploads the file to. */
    @PostMapping("/api/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse create(@Valid @RequestBody CreateDocumentRequest request) {
        return UploadResponse.from(service.createDocument(request.fileName(), request.contentType()));
    }

    @GetMapping("/api/documents/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return DocumentResponse.from(service.getDocument(id));
    }

    @GetMapping("/api/documents")
    public List<DocumentResponse> list(@RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return service.listDocuments(bounded).stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/api/documents/{id}/download-url")
    public DownloadUrlResponse downloadUrl(@PathVariable UUID id) {
        return DownloadUrlResponse.from(service.createDownloadUrl(id));
    }

    @DeleteMapping("/api/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.deleteDocument(id);
    }

    /**
     * Internal endpoint for the S3-triggered Lambda. Protected by a shared secret (kept in SSM
     * Parameter Store); in a real deployment also keep it off the public internet.
     */
    @PostMapping("/internal/documents/processed")
    public DocumentResponse markProcessed(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody MarkProcessedRequest request) {
        requireInternalToken(token);
        return DocumentResponse.from(service.markProcessed(request.s3Key()));
    }

    private void requireInternalToken(String supplied) {
        boolean ok = internalToken.length > 0
                && supplied != null
                && MessageDigest.isEqual(internalToken, supplied.getBytes(UTF_8)); // constant-time compare
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or missing internal token");
        }
    }
}
