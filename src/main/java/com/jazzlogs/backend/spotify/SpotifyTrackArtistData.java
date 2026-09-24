package com.jazzlogs.backend.spotify;

/**
 * A track's own primary embedded artist — enough to resolve/create a minimal
 * {@code Artist}, no separate {@code fetchArtist} call. No image: Spotify's
 * embedded artist objects (on tracks/albums) never carry one.
 */
public record SpotifyTrackArtistData(
    String spotifyArtistId,
    String name,
    String spotifyUrl
) {
}
