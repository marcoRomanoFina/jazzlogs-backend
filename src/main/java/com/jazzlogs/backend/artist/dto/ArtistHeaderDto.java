package com.jazzlogs.backend.artist.dto;

import java.util.UUID;

import com.jazzlogs.backend.editorial.dto.ArtistEditorialDto;

/**
 * The artist editorial page's header — the artist's own fields plus its
 * editorial. Nothing from Neo4j here (instruments/styles/contexts/similar
 * artists/appearances) — those live on a separate, more expensive endpoint.
 *
 * @param id              the artist's own id
 * @param name            the artist's name
 * @param spotifyArtistId Spotify's own id for this artist, {@code null} for a manually-entered one
 * @param spotifyUrl      link to their Spotify page, {@code null} under the same condition
 * @param imageUrl        their photo
 * @param editorial       the artist's own editorial, {@code null} if none written yet
 */
public record ArtistHeaderDto(
    UUID id,
    String name,
    String spotifyArtistId,
    String spotifyUrl,
    String imageUrl,
    ArtistEditorialDto editorial
) {
}
