package com.example.docservice.web;

import com.example.docservice.model.Document;
import com.example.docservice.model.DocumentStatus;
import com.example.docservice.service.DocumentService.PresignedUrl;
import com.example.docservice.service.DocumentService.UploadTicket;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Request/response DTOs for the REST API. */
public final class ApiModels {

    private ApiModels() {
    }

    public record CreateDocumentRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 127)
            @Pattern(regexp = "^[\\w.+-]+/[\\w.+-]+$", message = "must look like type/subtype") String contentType) {
    }

    public record MarkProcessedRequest(@NotBlank @Size(max = 1024) String s3Key) {
    }

    public record DocumentResponse(UUID id, String fileName, String contentType, DocumentStatus status,
                                   Instant createdAt, Instant updatedAt) {
        public static DocumentResponse from(Document d) {
            return new DocumentResponse(d.getId(), d.getFileName(), d.getContentType(), d.getStatus(),
                    d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record UploadResponse(UUID id, DocumentStatus status, String uploadUrl,
                                 Map<String, String> requiredHeaders, Instant expiresAt) {
        public static UploadResponse from(UploadTicket t) {
            return new UploadResponse(t.document().getId(), t.document().getStatus(), t.uploadUrl(),
                    t.requiredHeaders(), t.expiresAt());
        }
    }

    public record DownloadUrlResponse(String url, Instant expiresAt) {
        public static DownloadUrlResponse from(PresignedUrl p) {
            return new DownloadUrlResponse(p.url(), p.expiresAt());
        }
    }
}
