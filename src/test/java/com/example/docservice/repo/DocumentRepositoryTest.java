package com.example.docservice.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.docservice.model.Document;
import com.example.docservice.model.DocumentStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class DocumentRepositoryTest {

    @Autowired
    DocumentRepository repository;

    @Test
    void findByS3Key_returnsPersistedDocument() {
        Document saved = repository.saveAndFlush(new Document("a.pdf", "application/pdf"));

        assertThat(repository.findByS3Key(saved.getS3Key()))
                .get()
                .extracting(Document::getId, Document::getStatus)
                .containsExactly(saved.getId(), DocumentStatus.PENDING);
    }

    @Test
    void findByS3Key_unknownKey_isEmpty() {
        assertThat(repository.findByS3Key("uploads/none/none.pdf")).isEmpty();
    }

    @Test
    void statusUpdate_persists() {
        Document saved = repository.saveAndFlush(new Document("a.pdf", "application/pdf"));
        saved.markProcessed();
        repository.saveAndFlush(saved);

        assertThat(repository.findById(saved.getId()))
                .get()
                .extracting(Document::getStatus)
                .isEqualTo(DocumentStatus.PROCESSED);
    }

    @Test
    void findAllByOrderByCreatedAtDesc_respectsPageSize() {
        repository.saveAndFlush(new Document("1.pdf", "application/pdf"));
        repository.saveAndFlush(new Document("2.pdf", "application/pdf"));
        repository.saveAndFlush(new Document("3.pdf", "application/pdf"));

        List<Document> page = repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 2));

        assertThat(page).hasSize(2);
    }
}
