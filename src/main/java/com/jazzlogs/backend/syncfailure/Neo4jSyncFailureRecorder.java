package com.jazzlogs.backend.syncfailure;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class Neo4jSyncFailureRecorder {

    private final SyncFailureRepository syncFailureRepository;

    @Transactional
    public void record(SyncFailureEntityType entityType, Map<String, Object> payload, Exception cause) {
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        syncFailureRepository.save(new SyncFailure(entityType, payload, error));
    }
}
