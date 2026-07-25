package com.jazzlogs.backend.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.jazzlogs.backend.graph.GraphWriteException;

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
}
