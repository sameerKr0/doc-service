package com.example.docservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.docservice.config.AppProperties;
import com.example.docservice.model.Document;
import com.example.docservice.model.DocumentStatus;
import com.example.docservice.repo.DocumentRepository;
import com.example.docservice.service.DocumentService.PresignedUrl;
import com.example.docservice.service.DocumentService.UploadTicket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Repository and S3Client are Mockito mocks. The presigner is a real one with dummy static
 * credentials: signing is a pure local computation, so the tests assert on the actual URL shape
 * without any network access or AWS account.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final String BUCKET = "test-bucket";

    @Mock
    DocumentRepository repository;

    @Mock
    S3Client s3Client;

    private S3Presigner presigner;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIAEXAMPLEEXAMPLE", "exampleSecretKey")))
                .build();
        AppProperties props = new AppProperties(
                new AppProperties.Aws("ap-south-1", new AppProperties.S3(BUCKET, "", Duration.ofMinutes(15))),
                "token");
        service = new DocumentService(repository, s3Client, presigner, props);
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void createDocument_savesPendingMetadataAndReturnsSignedPutUrl() {
        when(repository.save(any(Document.class))).thenAnswer(returnsFirstArg());

        UploadTicket ticket = service.createDocument("report.pdf", "application/pdf");

        Document doc = ticket.document();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(doc.getS3Key()).isEqualTo("uploads/" + doc.getId() + "/report.pdf");
        assertThat(ticket.uploadUrl())
                .startsWith("https://test-bucket.s3.ap-south-1.amazonaws.com/uploads/" + doc.getId())
                .contains("X-Amz-Signature=")
                .contains("X-Amz-Expires=900")
                .contains("content-type");
        assertThat(ticket.requiredHeaders()).containsEntry("Content-Type", "application/pdf");
        assertThat(ticket.expiresAt()).isAfter(doc.getCreatedAt());
        verify(repository).save(doc);
    }

    @Test
    void createDocument_sanitizesFileNameSoKeyCannotEscapeItsPrefix() {
        when(repository.save(any(Document.class))).thenAnswer(returnsFirstArg());

        UploadTicket ticket = service.createDocument("../../etc/pass wd.pdf", "application/pdf");

        assertThat(ticket.document().getFileName()).isEqualTo("pass_wd.pdf");
        assertThat(ticket.document().getS3Key()).doesNotContain("..").endsWith("/pass_wd.pdf");
    }

    @Test
    void sanitizeFileName_handlesEdgeCases() {
        assertThat(DocumentService.sanitizeFileName(null)).isEqualTo("file");
        assertThat(DocumentService.sanitizeFileName("   ")).isEqualTo("file");
        assertThat(DocumentService.sanitizeFileName("a/b/")).isEqualTo("file");
        assertThat(DocumentService.sanitizeFileName("..hidden")).isEqualTo("hidden");
        assertThat(DocumentService.sanitizeFileName("C:\\temp\\cv final.docx")).isEqualTo("cv_final.docx");
        assertThat(DocumentService.sanitizeFileName("x".repeat(300))).hasSize(100);
    }

    @Test
    void getDocument_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDocument(id)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void createDownloadUrl_whenStillPending_isRejected() {
        Document doc = new Document("a.pdf", "application/pdf");
        when(repository.findById(doc.getId())).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.createDownloadUrl(doc.getId()))
                .isInstanceOf(InvalidDocumentStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void createDownloadUrl_whenProcessed_returnsSignedGetUrl() {
        Document doc = new Document("a.pdf", "application/pdf");
        doc.markProcessed();
        when(repository.findById(doc.getId())).thenReturn(Optional.of(doc));

        PresignedUrl url = service.createDownloadUrl(doc.getId());

        assertThat(url.url())
                .contains("test-bucket")
                .contains("X-Amz-Signature=")
                .contains("response-content-disposition");
        assertThat(url.expiresAt()).isNotNull();
    }

    @Test
    void markProcessed_movesPendingToProcessed() {
        Document doc = new Document("a.pdf", "application/pdf");
        when(repository.findByS3Key(doc.getS3Key())).thenReturn(Optional.of(doc));
        when(repository.save(doc)).thenReturn(doc);

        Document result = service.markProcessed(doc.getS3Key());

        assertThat(result.getStatus()).isEqualTo(DocumentStatus.PROCESSED);
        verify(repository).save(doc);
    }

    @Test
    void markProcessed_isIdempotentForDuplicateEvents() {
        Document doc = new Document("a.pdf", "application/pdf");
        when(repository.findByS3Key(doc.getS3Key())).thenReturn(Optional.of(doc));
        when(repository.save(doc)).thenReturn(doc);

        service.markProcessed(doc.getS3Key());
        service.markProcessed(doc.getS3Key()); // S3 may deliver the same event twice

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSED);
        verify(repository, times(1)).save(doc);
    }

    @Test
    void markProcessed_unknownKey_throwsNotFound() {
        when(repository.findByS3Key("uploads/nope/x.pdf")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markProcessed("uploads/nope/x.pdf"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void deleteDocument_removesS3ObjectThenMetadata() {
        Document doc = new Document("a.pdf", "application/pdf");
        when(repository.findById(doc.getId())).thenReturn(Optional.of(doc));

        service.deleteDocument(doc.getId());

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(doc.getS3Key());
        verify(repository).delete(doc);
    }

    @Test
    void deleteDocument_unknownId_touchesNothing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDocument(id)).isInstanceOf(DocumentNotFoundException.class);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(repository, never()).delete(any(Document.class));
    }
}
