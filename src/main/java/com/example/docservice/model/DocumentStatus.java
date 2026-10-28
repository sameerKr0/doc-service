package com.example.docservice.model;

public enum DocumentStatus {
    /** Metadata exists; the file has not been uploaded/processed yet. */
    PENDING,
    /** The S3-triggered Lambda has seen the upload and marked the document processed. */
    PROCESSED
}
