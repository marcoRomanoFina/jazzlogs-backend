package com.jazzlogs.backend.editorial.dto;

import java.util.UUID;

/**
 * One track editorial within {@link LastLogDto#tracks}.
 *
 * @param id                 the track editorial's own id — not requested by
 *                           name, kept in so the front can address a like
 *                           call at this specific track editorial
 * @param trackNumber        the track's position on the album; {@code null}
 *                           if Neo4j has no placement recorded for it (see
 *                           {@code GraphService#getTrackPlacements})
 * @param title              the track editorial's headline
 * @param dek                the editorial's short standfirst text
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 */
public record LastLogTrackDto(
    UUID id,
    Integer trackNumber,
    String title,
    String dek,
    int likeCount,
    boolean likedByCurrentUser
) {
}
