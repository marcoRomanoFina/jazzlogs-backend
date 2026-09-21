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
 * Stores uploaded series-chapter audio in the {@code jazzlogs-audio} bucket
 * (MinIO locally, real S3 in prod — same {@link S3Client} bean {@link
 * ImageStorageConfig} wires up, just a different bucket). Unlike {@link
 * ImageStorageService}, this hands back the object's own key plus its
 * content type/size, not a public URL — {@code SeriesChapter.audioObjectKey}
 * predates this service and is a plain S3 key by design, not a URL.
 */
@Service
public class AudioStorageService {

    // Content-type allowlist doubles as the extension map for the stored
    // key — anything else is rejected before it ever reaches S3.
    private static final Map<String, String> ALLOWED_AUDIO_TYPES = Map.of(
        "audio/mpeg", "mp3",
        "audio/mp4", "m4a",
        "audio/wav", "wav",
        "audio/x-wav", "wav"
    );

    private final S3Client s3Client;

    @Value("${audio-storage.bucket}")
    private String bucket;

    public AudioStorageService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Uploads {@code file} under {@code keyPrefix} plus an extension derived
     * from its own content type — overwriting whatever was already there,
     * so re-uploading a chapter's audio is just this again, not a growing
     * pile of orphaned old files.
     *
     * @param keyPrefix the object key, without extension (e.g. {@code "series/<id>/chapters/<id>/audio"})
     * @param file      the uploaded file — only mp3/m4a/wav are accepted
     * @return the resulting object's key, content type, and size
     * @throws ResponseStatusException 400 if the content type isn't an allowed audio type,
     *                                  502 if the upload to storage itself fails
     */
    public UploadedAudio upload(String keyPrefix, MultipartFile file) {
        String extension = ALLOWED_AUDIO_TYPES.get(file.getContentType());
        if (extension == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported audio type: " + file.getContentType() + " (allowed: mp3, m4a, wav)"
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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to upload audio to storage", e);
        }

        return new UploadedAudio(key, file.getContentType(), file.getSize());
    }

    public record UploadedAudio(String objectKey, String contentType, long fileSizeBytes) {
    }
}
