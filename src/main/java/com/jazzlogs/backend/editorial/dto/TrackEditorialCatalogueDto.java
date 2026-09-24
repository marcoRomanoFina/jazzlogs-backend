package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialByline;

/**
 * One row in the archive's search/browse listing — now always a track
 * editorial (the only kind left), so unlike the old multi-owner-type
 * catalogue this carries no {@code type} field.
 *
 * @param id                 the track editorial's own id
 * @param trackId            the track this editorial belongs to
 * @param trackName          the track's own name
 * @param trackImageUrl      the track's cover image (borrowed from its album)
 * @param albumName          the track's album
 * @param albumId            the track's album id
 * @param title              the editorial's headline
 * @param dek                short standfirst text
 * @param byline             who wrote it
 * @param createdAt          when this editorial was created
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 */
public record TrackEditorialCatalogueDto(
    UUID id,
    UUID trackId,
    String trackName,
    String trackImageUrl,
    String albumName,
    UUID albumId,
    String title,
    String dek,
    EditorialByline byline,
    Instant createdAt,
    int likeCount,
    boolean likedByCurrentUser
) {
}
