package com.jazzlogs.backend.syncfailure;

import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Component
@AllArgsConstructor
public class Neo4jAsyncSyncExecutor {

    private final Neo4jSyncFailureRecorder syncFailureRecorder;

    @Async("neo4jSyncExecutor")
    public void sync(SyncFailureEntityType entityType, Map<String, Object> payload, Runnable graphWrite) {
        try {
            graphWrite.run();
        } catch (Exception ex) {
            log.warn("Failed to sync {} to Neo4j, payload={}", entityType, payload, ex);
            syncFailureRecorder.record(entityType, payload, ex);
        }
    }
}
