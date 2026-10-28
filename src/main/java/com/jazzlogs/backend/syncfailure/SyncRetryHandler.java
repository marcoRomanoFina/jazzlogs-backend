package com.jazzlogs.backend.syncfailure;

import java.util.Map;

// Implemented once per SyncFailureEntityType so SyncRetryWorker can dispatch
// through a Map<SyncFailureEntityType, SyncRetryHandler> instead of a switch —
// same pattern as LikeService's Map<LikeableEntityType, LikeableRepository<?>>.
public interface SyncRetryHandler {

    // Rebuilds and re-attempts the original Neo4j write from payload. Let
    // exceptions propagate as-is (GraphWriteException or a payload-parsing
    // error) — SyncRetryWorker is the one that catches and records them.
    void retry(Map<String, Object> payload);
}
