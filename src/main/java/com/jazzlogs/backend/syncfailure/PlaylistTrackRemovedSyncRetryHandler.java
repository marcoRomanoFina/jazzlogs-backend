package com.jazzlogs.backend.syncfailure;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.graph.GraphService;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
public class PlaylistTrackRemovedSyncRetryHandler implements SyncRetryHandler {

    private final GraphService graphService;

    @Override
    public void retry(Map<String, Object> payload) {
        UUID playlistId = UUID.fromString((String) payload.get("playlistId"));
        UUID trackId = UUID.fromString((String) payload.get("trackId"));

        graphService.removePlaylistTrack(playlistId, trackId);
    }
}
