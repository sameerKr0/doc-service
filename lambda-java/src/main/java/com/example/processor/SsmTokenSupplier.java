package com.example.processor;

import java.util.function.Supplier;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

/**
 * Reads the internal API token from an SSM SecureString once per execution environment and
 * caches it. Credentials and region come from the Lambda execution environment (the function's
 * IAM role), so nothing is configured here.
 */
final class SsmTokenSupplier implements Supplier<String> {

    private final String parameterName;
    private volatile String cached;

    SsmTokenSupplier(String parameterName) {
        this.parameterName = parameterName;
    }

    @Override
    public String get() {
        String token = cached;
        if (token == null) {
            try (SsmClient ssm = SsmClient.builder()
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .build()) {
                token = ssm.getParameter(GetParameterRequest.builder()
                                .name(parameterName)
                                .withDecryption(true)
                                .build())
                        .parameter()
                        .value();
            }
            cached = token;
        }
        return token;
    }
}
