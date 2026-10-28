package com.jazzlogs.backend.syncfailure;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.graph.GraphService;

import lombok.AllArgsConstructor;

// createUserNode is a plain MERGE — safe to retry as many times as needed,
// same idempotency UserService relies on for the original attempt.
@Component
@AllArgsConstructor
public class UserCreatedSyncRetryHandler implements SyncRetryHandler {

    private final GraphService graphService;

    @Override
    public void retry(Map<String, Object> payload) {
        UUID userId = UUID.fromString((String) payload.get("userId"));
        graphService.createUserNode(userId);
    }
}
