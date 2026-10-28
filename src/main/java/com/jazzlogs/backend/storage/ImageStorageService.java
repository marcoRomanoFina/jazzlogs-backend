package com.jazzlogs.backend.storage;

import java.io.IOException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Stores user-uploaded images (e.g. a playlist cover) in the {@code
 * jazzlogs-images} bucket (MinIO locally, real S3 in prod — see {@link
 * ImageStorageConfig}) and hands back a plain, permanent public URL — the
 * bucket is public-read (see {@code docker-compose.yml}'s createbuckets
 * service), so no presigned-URL logic is needed here.
 */
@Service
public class ImageStorageService {

    // Content-type allowlist doubles as the extension map for the stored
    // key — anything else is rejected before it ever reaches S3.
    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
        "image/jpeg", "jpg",
        "image/png", "png",
        "image/webp", "webp"
    );

    private final S3Client s3Client;

    @Value("${image-storage.bucket}")
    private String bucket;

    @Value("${image-storage.public-url-base}")
    private String publicUrlBase;

    public ImageStorageService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Uploads {@code file} under {@code keyPrefix} plus an extension derived
     * from its own content type — overwriting whatever was already there,
     * so re-uploading a cover is just this again, not a growing pile of
     * orphaned old files.
     *
     * @param keyPrefix the object key, without extension (e.g. {@code "playlists/<id>/cover"})
     * @param file       the uploaded file — only jpeg/png/webp are accepted
     * @return the resulting object's public URL
     * @throws ResponseStatusException 400 if the content type isn't an allowed image type,
     *                                  502 if the upload to storage itself fails
     */
    public String upload(String keyPrefix, MultipartFile file) {
        String extension = ALLOWED_IMAGE_TYPES.get(file.getContentType());
        if (extension == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported image type: " + file.getContentType() + " (allowed: jpeg, png, webp)"
            );
        }

        String key = keyPrefix + "." + extension;
        try {
            s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(file.getContentType()).build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file", e);
        } catch (S3Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to upload image to storage", e);
        }

        return publicUrlBase + "/" + key;
    }
}
