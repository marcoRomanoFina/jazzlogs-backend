package com.jazzlogs.backend.syncfailure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.graph.GraphService;

import lombok.AllArgsConstructor;

// LISTENED covers album, track, and playlist listens (see ListenService) — the
// payload's targetType says which one this particular failure was.
@Component
@AllArgsConstructor
public class ListenedSyncRetryHandler implements SyncRetryHandler {

    private final GraphService graphService;

    @Override
    public void retry(Map<String, Object> payload) {
        String targetType = (String) payload.get("targetType");
        UUID userId = UUID.fromString((String) payload.get("userId"));
        UUID targetId = UUID.fromString((String) payload.get("targetId"));
        Instant listenedAt = Instant.parse((String) payload.get("listenedAt"));

        switch (targetType) {
            case "ALBUM" -> graphService.markAlbumListened(userId, targetId, listenedAt);
            case "TRACK" -> graphService.markTrackListened(userId, targetId, listenedAt);
            case "PLAYLIST" -> graphService.markPlaylistListened(userId, targetId, listenedAt);
            default -> throw new IllegalStateException("Unknown LISTENED targetType in payload: " + targetType);
        }
    }
}
