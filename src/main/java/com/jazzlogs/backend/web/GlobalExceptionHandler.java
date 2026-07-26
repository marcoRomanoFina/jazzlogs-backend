package com.jazzlogs.backend.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "invalid_request", "message", ex.getMessage()));
    }
}
