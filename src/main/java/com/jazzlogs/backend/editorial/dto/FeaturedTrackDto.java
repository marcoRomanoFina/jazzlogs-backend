package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One track in the archive's curated "Featured Tracks" — see {@code
 * Track#featured} for the flag and {@code TrackService.MAX_FEATURED_TRACKS}
 * for the cap.
 *
 * @param id                 the track editorial's own id
 * @param title              the track editorial's headline
 * @param dek                the editorial's short standfirst text
 * @param byline             who wrote it
 * @param logNumber          the track's album's catalog/release identifier
 * @param trackName          the track's own name
 * @param imageUrl           the track's cover image (borrowed from its album — see {@code Track#imageUrl})
 * @param albumName          the track's album
 * @param albumId            the track's album id
 * @param createdAt          when this editorial was created
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 */
public record FeaturedTrackDto(
    UUID id,
    String title,
    String dek,
    String byline,
    String logNumber,
    String trackName,
    String imageUrl,
    String albumName,
    UUID albumId,
    Instant createdAt,
    int likeCount,
    boolean likedByCurrentUser
) {
}
