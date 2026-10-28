package com.example.docservice.repo;

import com.example.docservice.model.Document;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByS3Key(String s3Key);

    List<Document> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
