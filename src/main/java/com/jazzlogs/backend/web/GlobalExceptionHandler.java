package com.jazzlogs.backend.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.jazzlogs.backend.agent.limit.MessageLimitExceededException;
import com.jazzlogs.backend.graph.GraphWriteException;
import com.jazzlogs.backend.spotify.SpotifyLookupException;
import com.openai.errors.OpenAIException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GraphWriteException.class)
    public ResponseEntity<Map<String, String>> handleGraphWriteException(GraphWriteException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of(
                "error", "graph_unavailable",
                "message", "Could not complete the request because the graph database is unavailable."
            ));
    }

    @ExceptionHandler(SpotifyLookupException.class)
    public ResponseEntity<Map<String, String>> handleSpotifyLookupException(SpotifyLookupException ex) {
        HttpStatus status = ex.isNotFound() ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
        String error = ex.isNotFound() ? "spotify_album_not_found" : "spotify_unavailable";

        return ResponseEntity.status(status)
            .body(Map.of("error", error, "message", ex.getMessage()));
    }

    @ExceptionHandler(OpenAIException.class)
    public ResponseEntity<Map<String, String>> handleOpenAiException(OpenAIException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of(
                "error", "embedding_generation_failed",
                "message", "Could not save editorial content because the embedding service failed: " + ex.getMessage()
            ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
            .body(Map.of("error", "file_too_large", "message", "Uploaded file exceeds the size limit."));
    }

    /**
     * {@code resetsAt} lets the frontend say when the user can write again;
     * {@code error} tells apart their own limit from the whole demo's.
     */
    @ExceptionHandler(MessageLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleMessageLimitExceededException(MessageLimitExceededException ex) {
        String error = ex.getScope() == MessageLimitExceededException.Scope.USER
            ? "message_limit_reached"
            : "daily_demo_limit_reached";

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
            .body(Map.of("error", error, "message", ex.getMessage(), "resetsAt", ex.getResetsAt().toString()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "invalid_request", "message", ex.getMessage()));
    }
}
