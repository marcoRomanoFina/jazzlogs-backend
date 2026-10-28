package com.jazzlogs.backend.playlist.dto;

import java.util.UUID;

// Minimal create response — POST /playlists doesn't need the full
// PlaylistDetailDto (nothing to show yet for a brand-new, trackless
// playlist), just enough for the caller to know what id to use next.
public record PlaylistIdDto(UUID id) {
}
