package com.jazzlogs.backend.spotify;

public record SpotifyTrackData(
    String spotifyTrackId,
    String name,
    Integer durationMs,
    String spotifyUrl,
    Integer trackNumber,
    String imageUrl
) {
}
