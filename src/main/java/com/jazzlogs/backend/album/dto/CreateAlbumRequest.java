package com.jazzlogs.backend.album.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;

// name, imageUrl, spotifyUrl, releaseYear and totalTracks aren't here — they
// always come from Spotify (see AlbumService.createOrUpdateAlbum), which is why
// spotifyAlbumId is required. vocalProfile/energy/moodIntensity/accessibility
// are required too — they're NOT NULL columns on albums. postedAt isn't here
// either — it's stamped with the server clock at creation (see
// AlbumService.createOrUpdateAlbum), not client-supplied.
public record CreateAlbumRequest(
    @NotNull UUID artistId,
    @NotBlank String spotifyAlbumId,
    @NotBlank String logNumber,
    @NotNull VocalProfile vocalProfile,
    @NotNull Level energy,
    @NotNull Level moodIntensity,
    @NotNull Level accessibility,
    String instagramPermalink
) {
}
