package com.jazzlogs.backend.syncfailure;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.graph.GraphService;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
public class PlaylistTracksReorderedSyncRetryHandler implements SyncRetryHandler {

    private final GraphService graphService;

    @Override
    @SuppressWarnings("unchecked")
    public void retry(Map<String, Object> payload) {
        UUID playlistId = UUID.fromString((String) payload.get("playlistId"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("positions");

        Map<UUID, Integer> positions = rows.stream()
            .collect(Collectors.toMap(
                row -> UUID.fromString((String) row.get("trackId")),
                row -> Integer.parseInt((String) row.get("position"))
            ));

        graphService.reorderPlaylistTracks(playlistId, positions);
    }
}
