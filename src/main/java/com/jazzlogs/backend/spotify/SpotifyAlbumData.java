package com.jazzlogs.backend.spotify;

public record SpotifyAlbumData(
    String name,
    String imageUrl,
    String spotifyUrl,
    Integer totalTracks,
    Integer releaseYear
) {
}
