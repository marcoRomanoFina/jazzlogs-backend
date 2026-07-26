package com.jazzlogs.backend.album.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;

// name, imageUrl, spotifyUrl, releaseYear and totalTracks aren't here — they
// always come from Spotify (see AlbumService.createAlbum), which is why
// spotifyAlbumId is required.
public record CreateAlbumRequest(
    UUID artistId,
    String spotifyAlbumId,
    String logNumber,
    VocalProfile vocalProfile,
    Level energy,
    Level moodIntensity,
    Level accessibility,
    Instant postedAt,
    String instagramPermalink
) {
}
