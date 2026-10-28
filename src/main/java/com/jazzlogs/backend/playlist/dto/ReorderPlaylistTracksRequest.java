package com.jazzlogs.backend.playlist.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

// trackIds must contain exactly the tracks already in the playlist (same set,
// no more, no less) — position is the array index. See PlaylistService.reorderTracks.
public record ReorderPlaylistTracksRequest(
    @NotNull List<UUID> trackIds
) {
}
