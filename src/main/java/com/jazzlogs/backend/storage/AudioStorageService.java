package com.jazzlogs.backend.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Stores uploaded series-chapter audio in the {@code jazzlogs-audio} bucket
 * (MinIO locally, real S3 in prod — same underlying MinIO instance {@link
 * ImageStorageConfig} talks to, just a different bucket). Unlike {@link
 * ImageStorageService}'s bucket, this one is private — series may end up
 * gated behind a subscription, so playback goes through a short-lived
 * {@link #presignPlaybackUrl presigned URL} instead of a permanent public
 * one, giving the caller (see {@code SeriesService.getChapterAudioUrl}) a
 * choke point to add that check later. A presigned S3 GET URL still
 * supports HTTP Range requests exactly like a public one would, so
 * seek/progressive playback isn't affected.
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

    private static final Duration PLAYBACK_URL_TTL = Duration.ofHours(1);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${audio-storage.bucket}")
    private String bucket;

    public AudioStorageService(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    /**
     * Uploads {@code file} under {@code keyPrefix} plus an extension derived
     * from its own content type — overwriting whatever was already there,
     * so re-uploading a chapter's audio is just this again, not a growing
     * pile of orphaned old files.
     *
     * @param keyPrefix the object key, without extension (e.g. {@code "series/<id>/chapters/<id>/audio"})
     * @param file      the uploaded file — only mp3/m4a/wav are accepted
     * @return the resulting object's own key, content type, and size
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

    /**
     * A temporary, signed GET URL for one stored object — valid for {@link
     * #PLAYBACK_URL_TTL}, generated fresh on every call rather than stored,
     * since a signed URL that outlives its own usefulness defeats the point
     * of not having a public bucket.
     *
     * @param objectKey the key returned by a prior {@link #upload}
     * @return a URL the caller can GET (or Range-GET) directly against storage
     */
    public String presignPlaybackUrl(String objectKey) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(PLAYBACK_URL_TTL)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
            .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    public record UploadedAudio(String objectKey, String contentType, long fileSizeBytes) {
    }
}
