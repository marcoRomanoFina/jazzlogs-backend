package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialByline;

/**
 * The newest published editorial, with enough information for a prominent
 * home-page card without transferring its complete body.
 *
 * @param trackId           the associated track id
 * @param trackName         the associated track's name
 * @param albumName         the associated album's name
 * @param title             the editorial headline
 * @param logNumber         the JazzLogs log number
 * @param dek               the short standfirst
 * @param byline            the editorial author
 * @param coverImageUrl     the editorial cover image
 * @param principalImageUrl the editorial principal image
 * @param createdAt         when the editorial was created
 * @param likeCount         total likes
 * @param likedByCurrentUser whether the requester has liked it
 * @param hook              text of the first block categorized as {@code HOOK}, if any
 */
public record LatestEditorialDto(
    UUID trackId,
    String trackName,
    String albumName,
    String title,
    String logNumber,
    String dek,
    EditorialByline byline,
    String coverImageUrl,
    String principalImageUrl,
    Instant createdAt,
    int likeCount,
    boolean likedByCurrentUser,
    String hook
) {
}
