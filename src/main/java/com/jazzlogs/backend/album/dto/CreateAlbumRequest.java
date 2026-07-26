package com.jazzlogs.backend.album.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;

// name, imageUrl, spotifyUrl, releaseYear and totalTracks aren't here — they
// always come from Spotify (see AlbumService.createAlbum), which is why
// spotifyAlbumId is required. vocalProfile/energy/moodIntensity/accessibility
// are required too — they're NOT NULL columns on albums.
public record CreateAlbumRequest(
    @NotNull UUID artistId,
    @NotBlank String spotifyAlbumId,
    String logNumber,
    @NotNull VocalProfile vocalProfile,
    @NotNull Level energy,
    @NotNull Level moodIntensity,
    @NotNull Level accessibility,
    Instant postedAt,
    String instagramPermalink
) {
}
