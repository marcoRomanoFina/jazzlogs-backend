package com.jazzlogs.backend.artist.dto;

import jakarta.validation.constraints.NotBlank;

// name and spotifyUrl aren't here — they always come from Spotify (see
// ArtistService.createArtist), which is why spotifyArtistId is required.
public record CreateArtistRequest(@NotBlank String spotifyArtistId) {
}
