package com.example.docservice.web;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.docservice.model.Document;
import com.example.docservice.service.DocumentNotFoundException;
import com.example.docservice.service.DocumentService;
import com.example.docservice.service.DocumentService.PresignedUrl;
import com.example.docservice.service.DocumentService.UploadTicket;
import com.example.docservice.service.InvalidDocumentStateException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DocumentController.class)
@TestPropertySource(properties = "app.internal-api-token=s3cret")
class DocumentControllerTest {

    private static final Instant EXPIRES = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DocumentService service;

    @Test
    void create_returns201WithPresignedUploadUrl() throws Exception {
        Document doc = new Document("report.pdf", "application/pdf");
        when(service.createDocument("report.pdf", "application/pdf")).thenReturn(
                new UploadTicket(doc, "https://bucket.example/upload", EXPIRES,
                        Map.of("Content-Type", "application/pdf")));

        mvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"report.pdf\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(doc.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.uploadUrl").value("https://bucket.example/upload"))
                .andExpect(jsonPath("$.requiredHeaders['Content-Type']").value("application/pdf"));
    }

    @Test
    void create_withBlankFileName_returns400() throws Exception {
        mvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).createDocument("", "application/pdf");
    }

    @Test
    void create_withMalformedContentType_returns400() throws Exception {
        mvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"a.pdf\",\"contentType\":\"not a mime type\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_returnsMetadata() throws Exception {
        Document doc = new Document("report.pdf", "application/pdf");
        when(service.getDocument(doc.getId())).thenReturn(doc);

        mvc.perform(get("/api/documents/{id}", doc.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("report.pdf"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void get_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getDocument(id)).thenThrow(new DocumentNotFoundException("Document " + id + " not found"));

        mvc.perform(get("/api/documents/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void get_withMalformedId_returns400() throws Exception {
        mvc.perform(get("/api/documents/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsDocuments() throws Exception {
        when(service.listDocuments(50)).thenReturn(List.of(new Document("a.pdf", "application/pdf")));

        mvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fileName").value("a.pdf"));
    }

    @Test
    void list_clampsOversizedPageSize() throws Exception {
        when(service.listDocuments(100)).thenReturn(List.of());

        mvc.perform(get("/api/documents").param("size", "100000")).andExpect(status().isOk());

        verify(service).listDocuments(100);
    }

    @Test
    void downloadUrl_whenProcessed_returnsUrl() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.createDownloadUrl(id)).thenReturn(new PresignedUrl("https://bucket.example/get", EXPIRES));

        mvc.perform(get("/api/documents/{id}/download-url", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://bucket.example/get"));
    }

    @Test
    void downloadUrl_whenNotProcessed_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.createDownloadUrl(id)).thenThrow(new InvalidDocumentStateException("still PENDING"));

        mvc.perform(get("/api/documents/{id}/download-url", id)).andExpect(status().isConflict());
    }

    @Test
    void delete_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isNoContent());

        verify(service).deleteDocument(id);
    }

    @Test
    void internalProcessed_withoutToken_returns403() throws Exception {
        mvc.perform(post("/internal/documents/processed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"s3Key\":\"uploads/x/a.pdf\"}"))
                .andExpect(status().isForbidden());

        verify(service, never()).markProcessed("uploads/x/a.pdf");
    }

    @Test
    void internalProcessed_withWrongToken_returns403() throws Exception {
        mvc.perform(post("/internal/documents/processed")
                        .header("X-Internal-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"s3Key\":\"uploads/x/a.pdf\"}"))
                .andExpect(status().isForbidden());

        verify(service, never()).markProcessed("uploads/x/a.pdf");
    }

    @Test
    void internalProcessed_withValidToken_marksProcessed() throws Exception {
        Document doc = new Document("a.pdf", "application/pdf");
        doc.markProcessed();
        when(service.markProcessed(doc.getS3Key())).thenReturn(doc);

        mvc.perform(post("/internal/documents/processed")
                        .header("X-Internal-Token", "s3cret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"s3Key\":\"" + doc.getS3Key() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }
}
