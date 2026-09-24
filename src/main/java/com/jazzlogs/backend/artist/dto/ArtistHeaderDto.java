package com.jazzlogs.backend.artist.dto;

import java.util.UUID;

/**
 * The artist page's header. Nothing from Neo4j here (instruments/styles/
 * contexts/similar artists/appearances) — those live on a separate, more
 * expensive endpoint.
 *
 * @param id              the artist's own id
 * @param name            the artist's name
 * @param spotifyArtistId Spotify's own id for this artist, {@code null} for a manually-entered one
 * @param spotifyUrl      link to their Spotify page, {@code null} under the same condition
 * @param imageUrl        their photo
 */
public record ArtistHeaderDto(
    UUID id,
    String name,
    String spotifyArtistId,
    String spotifyUrl,
    String imageUrl
) {
}
