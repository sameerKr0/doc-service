package com.example.docservice.service;

import com.example.docservice.config.AppProperties;
import com.example.docservice.model.Document;
import com.example.docservice.model.DocumentStatus;
import com.example.docservice.repo.DocumentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int MAX_FILE_NAME_LENGTH = 100;

    /** Result of registering a document: its metadata plus the URL the client must PUT the file to. */
    public record UploadTicket(Document document, String uploadUrl, Instant expiresAt,
                               Map<String, String> requiredHeaders) {
    }

    public record PresignedUrl(String url, Instant expiresAt) {
    }

    private final DocumentRepository repository;
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final AppProperties props;

    public DocumentService(DocumentRepository repository, S3Client s3Client, S3Presigner presigner,
                           AppProperties props) {
        this.repository = repository;
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.props = props;
    }

    /**
     * Registers a document (status PENDING) and returns a pre-signed PUT URL for the upload.
     * Presigning happens inside the transaction so a failure leaves no orphan metadata row.
     */
    @Transactional
    public UploadTicket createDocument(String fileName, String contentType) {
        Document document = repository.save(new Document(sanitizeFileName(fileName), contentType));

        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(bucket())
                .key(document.getS3Key())
                .contentType(contentType)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl())
                .putObjectRequest(putObject)
                .build());

        log.info("Registered document {} (key={})", document.getId(), document.getS3Key());
        // Content-Type is part of the signature, so the client must send exactly this header.
        return new UploadTicket(document, presigned.url().toString(), presigned.expiration(),
                Map.of("Content-Type", contentType));
    }

    @Transactional(readOnly = true)
    public Document getDocument(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException("Document " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Document> listDocuments(int size) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, size));
    }

    /** Pre-signed GET URL; only issued once the document has been processed. */
    @Transactional(readOnly = true)
    public PresignedUrl createDownloadUrl(UUID id) {
        Document document = getDocument(id);
        if (document.getStatus() != DocumentStatus.PROCESSED) {
            throw new InvalidDocumentStateException(
                    "Document " + id + " is " + document.getStatus() + "; download requires PROCESSED");
        }
        GetObjectRequest getObject = GetObjectRequest.builder()
                .bucket(bucket())
                .key(document.getS3Key())
                // fileName is restricted to [A-Za-z0-9._-] so quoting it here is safe
                .responseContentDisposition("attachment; filename=\"" + document.getFileName() + "\"")
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl())
                .getObjectRequest(getObject)
                .build());
        return new PresignedUrl(presigned.url().toString(), presigned.expiration());
    }

    /**
     * Called by the S3-triggered Lambda. Idempotent: S3 notifications are delivered at-least-once.
     */
    @Transactional
    public Document markProcessed(String s3Key) {
        Document document = repository.findByS3Key(s3Key)
                .orElseThrow(() -> new DocumentNotFoundException("No document for key " + s3Key));
        if (document.getStatus() == DocumentStatus.PROCESSED) {
            log.info("Document {} already PROCESSED; ignoring duplicate event", document.getId());
            return document;
        }
        document.markProcessed();
        log.info("Document {} marked PROCESSED", document.getId());
        return repository.save(document);
    }

    /**
     * Deletes the S3 object first, then the row. S3 deletes are idempotent, so if the row delete
     * fails the client can simply retry the request.
     */
    @Transactional
    public void deleteDocument(UUID id) {
        Document document = getDocument(id);
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket())
                .key(document.getS3Key())
                .build());
        repository.delete(document);
        log.info("Deleted document {}", id);
    }

    /** Keeps only a safe file name: no path segments, no leading dots, [A-Za-z0-9._-] only. */
    static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw.strip();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            name = name.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return name.isBlank() ? "file" : name;
    }

    private String bucket() {
        return props.aws().s3().bucket();
    }

    private Duration ttl() {
        return props.aws().s3().presignTtl();
    }
}
