package com.jazzlogs.backend.syncfailure;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.graph.GraphService;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
public class ReviewHighlightedSyncRetryHandler implements SyncRetryHandler {

    private final GraphService graphService;

    @Override
    @SuppressWarnings("unchecked")
    public void retry(Map<String, Object> payload) {
        UUID userId = UUID.fromString((String) payload.get("userId"));
        UUID albumId = UUID.fromString((String) payload.get("albumId"));
        List<UUID> trackIds = ((List<String>) payload.get("trackIds")).stream()
            .map(UUID::fromString)
            .toList();

        graphService.setHighlightedTracks(userId, albumId, trackIds);
    }
}
