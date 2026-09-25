package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialByline;

/**
 * One row in a byline's most recent editorials — same lean card shape as
 * {@code FeaturedTrackDto}, no blocks/images beyond the editorial's cover.
 *
 * @param id        the track editorial's own id
 * @param title     the editorial's headline
 * @param dek       short standfirst text
 * @param byline    who wrote it — always the byline that was queried for
 * @param trackId    the track this editorial belongs to
 * @param trackName  the track's own name
 * @param imageUrl   the editorial's cover image
 * @param albumName  the track's album
 * @param artistName the track's artist
 * @param createdAt          when this editorial was created
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 */
public record EditorialTrackSummaryDto(
    UUID id,
    String title,
    String dek,
    EditorialByline byline,
    UUID trackId,
    String trackName,
    String imageUrl,
    String albumName,
    String artistName,
    Instant createdAt,
    int likeCount,
    boolean likedByCurrentUser
) {
}
