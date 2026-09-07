package com.jazzlogs.backend.artist.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One album in an artist's "Essential Listening" section — an album
 * curated as a good entry point into that artist. The album's own artist
 * isn't necessarily the artist whose page this is (see {@code
 * GraphService#markAsEntryPoint}) — hence carrying its own artist id/name,
 * not assumed to match the caller's.
 *
 * @param id         the album's own id
 * @param name       the album's name
 * @param imageUrl   cover art
 * @param releaseYear year of original release
 * @param label      the record label
 * @param avgRating  JazzLogs' own average review rating, {@code null} if unrated
 * @param dek        the album's own editorial's short standfirst text, {@code null} if no editorial written yet
 * @param artistId   the album's own artist
 * @param artistName the album's own artist's name
 */
public record EssentialListeningAlbumDto(
    UUID id,
    String name,
    String imageUrl,
    Integer releaseYear,
    String label,
    BigDecimal avgRating,
    String dek,
    UUID artistId,
    String artistName
) {
}
