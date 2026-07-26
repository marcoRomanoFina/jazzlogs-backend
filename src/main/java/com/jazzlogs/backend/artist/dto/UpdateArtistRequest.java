package com.jazzlogs.backend.artist.dto;

/**
 * Partial update — every field is optional; only non-null fields are applied.
 */
public record UpdateArtistRequest(String name, String spotifyArtistId, String spotifyUrl) {
}
