package com.jazzlogs.backend.artist.dto;

import java.util.UUID;

/**
 * One row in an artist's "similar artists" list ({@code SIMILAR_TO} in
 * Neo4j) — just enough for a similar-artist card, no editorial, no tags.
 *
 * @param id       the similar artist's id
 * @param name     the similar artist's name
 * @param imageUrl the similar artist's image
 * @param reason   the curated reason this artist was picked as similar, from the {@code SIMILAR_TO} edge
 */
public record SimilarArtistDto(
    UUID id,
    String name,
    String imageUrl,
    String reason
) {
}
