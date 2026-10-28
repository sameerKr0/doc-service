package com.example.docservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for a document stored in S3. The binary content never touches this service;
 * clients upload/download directly against S3 using pre-signed URLs.
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Optimistic lock; also lets Spring Data tell a new entity from a detached one. */
    @Version
    private Long version;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "s3_key", nullable = false, unique = true, updatable = false)
    private String s3Key;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DocumentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
        // for JPA
    }

    /**
     * @param fileName    already-sanitized file name (see DocumentService#sanitizeFileName)
     * @param contentType MIME type the client will upload with
     */
    public Document(String fileName, String contentType) {
        this.id = UUID.randomUUID();
        this.fileName = fileName;
        this.contentType = contentType;
        this.s3Key = "uploads/" + id + "/" + fileName;
        this.status = DocumentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessed() {
        this.status = DocumentStatus.PROCESSED;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getS3Key() {
        return s3Key;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
