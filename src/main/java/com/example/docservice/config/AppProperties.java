package com.example.docservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code app.*} properties in application.yml.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Aws aws, String internalApiToken) {

    public record Aws(String region, S3 s3) {
    }

    /**
     * @param bucket           target bucket for uploads
     * @param endpointOverride optional custom endpoint (LocalStack); blank for real AWS
     * @param presignTtl       lifetime of generated pre-signed URLs
     */
    public record S3(String bucket, String endpointOverride, Duration presignTtl) {
    }
}
