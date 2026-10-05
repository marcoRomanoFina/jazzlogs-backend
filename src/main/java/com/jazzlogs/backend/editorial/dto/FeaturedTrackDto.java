package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialByline;

/**
 * One track in the archive's curated "Featured Tracks" — see {@code
 * Track#featured} for the flag and {@code TrackService.MAX_FEATURED_TRACKS}
 * for the cap.
 *
 * @param id                 the track editorial's own id
 * @param title              the track editorial's headline
 * @param logNumber          the JazzLogs log number
 * @param dek                the editorial's short standfirst text
 * @param byline             who wrote it
 * @param trackId            the track this editorial belongs to
 * @param trackName          the track's own name
 * @param imageUrl           the editorial's cover image
 * @param albumName          the track's album
 * @param artistName         the track's artist
 * @param createdAt          when this editorial was created
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 */
public record FeaturedTrackDto(
    UUID id,
    String title,
    String logNumber,
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
