package com.jazzlogs.backend.spotify;

/** A track's own embedded album — enough to resolve/create a minimal {@code Album}, no separate {@code fetchAlbum} call. */
public record SpotifyTrackAlbumData(
    String spotifyAlbumId,
    String name,
    String imageUrl,
    String spotifyUrl,
    Integer releaseYear
) {
}
