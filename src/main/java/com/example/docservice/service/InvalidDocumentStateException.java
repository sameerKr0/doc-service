package com.example.docservice.service;

public class InvalidDocumentStateException extends RuntimeException {

    public InvalidDocumentStateException(String message) {
        super(message);
    }
}
