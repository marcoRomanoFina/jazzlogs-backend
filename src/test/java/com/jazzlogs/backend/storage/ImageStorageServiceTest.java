package com.jazzlogs.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

// Real S3Client here (not mocked), against the actual local MinIO container
// (see docker-compose.yml) — this is the one place that actually exercises
// the upload -> public URL -> plain HTTP fetch path. PlaylistServiceTest
// mocks this bean instead, since it only cares that PlaylistService calls it
// correctly, not that MinIO itself works.
@SpringBootTest
class ImageStorageServiceTest {

    @Autowired
    private ImageStorageService imageStorageService;

    @Autowired
    private S3Client s3Client;

    @Test
    void upload_storesFileAndReturnsAFetchablePublicUrl() throws IOException, InterruptedException {
        String keyPrefix = "test-uploads/" + UUID.randomUUID();
        byte[] content = "not actually an image, just test bytes".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", content);

        String url = imageStorageService.upload(keyPrefix, file);

        assertThat(url).endsWith(keyPrefix + ".png");

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(content);

        s3Client.deleteObject(DeleteObjectRequest.builder().bucket("jazzlogs-images").key(keyPrefix + ".png").build());
    }

    @Test
    void upload_rejectsUnsupportedContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> imageStorageService.upload("test-uploads/rejected", file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
