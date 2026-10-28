package com.example.docservice.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * AWS clients. No credentials are configured here on purpose: the SDK's default provider chain
 * resolves them (task/instance role, env vars, SSO profile), so no keys live in code or config.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AwsConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(AppProperties props) {
        var builder = S3Client.builder().region(Region.of(props.aws().region()));
        String endpoint = props.aws().s3().endpointOverride();
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(AppProperties props) {
        var builder = S3Presigner.builder().region(Region.of(props.aws().region()));
        String endpoint = props.aws().s3().endpointOverride();
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }
}
