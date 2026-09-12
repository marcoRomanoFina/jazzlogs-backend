package com.jazzlogs.backend.storage;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The S3-compatible client used to talk to MinIO (see {@code
 * docker-compose.yml}) — the same client/API works unchanged against real
 * S3 in prod, just different endpoint/credentials.
 */
@Configuration
public class ImageStorageConfig {

    @Bean
    public S3Client s3Client(
        @Value("${image-storage.endpoint}") String endpoint,
        @Value("${image-storage.region}") String region,
        @Value("${image-storage.access-key}") String accessKey,
        @Value("${image-storage.secret-key}") String secretKey
    ) {
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            // Path-style (http://host:port/bucket/key), not virtual-hosted-style
            // (http://bucket.host:port/key) — MinIO's default setup (and a bare
            // host:port like localhost:9000) doesn't resolve the latter.
            .forcePathStyle(true)
            .build();
    }
}
