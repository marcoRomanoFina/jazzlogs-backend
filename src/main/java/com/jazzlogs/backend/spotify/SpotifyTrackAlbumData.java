package com.jazzlogs.backend.spotify;

import java.util.List;

/**
 * A track's own embedded album — enough to resolve/create a minimal {@code
 * Album} (including every one of its credited artists), no separate {@code
 * fetchAlbum} call.
 */
public record SpotifyTrackAlbumData(
    String spotifyAlbumId,
    String name,
    String imageUrl,
    String spotifyUrl,
    Integer releaseYear,
    List<SpotifyTrackArtistData> artists
) {
}
