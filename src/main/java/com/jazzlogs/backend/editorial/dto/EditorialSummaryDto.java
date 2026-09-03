package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialOwnerType;
import com.jazzlogs.backend.editorial.EditorialSummary;

/**
 * Same shape as {@link EditorialSummary} (its fields map over 1:1), plus
 * {@code likedByCurrentUser}: computed per-request via {@code
 * LikeService}, since "did I like this" depends on who's asking and can't
 * live in the shared view.
 *
 * @param id                 the editorial's own id (not the owner's)
 * @param type               which owner kind this is — ALBUM, TRACK, or ARTIST
 * @param ownerId            the id of the album/track/artist this editorial belongs to
 * @param ownerName          the owner's display name
 * @param ownerImageUrl      the owner's cover/image, if it has one
 * @param title              the editorial's headline
 * @param dek                the editorial's short standfirst/subhead text
 * @param byline             who wrote it
 * @param createdAt          when the editorial was created
 * @param likeCount          denormalized total, kept in sync via atomic
 *                           increment/decrement — never a live {@code COUNT(*)}
 * @param likedByCurrentUser whether the caller liked this editorial
 * @param featurated         whether this is THE curated hero editorial
 *                           (see {@code EditorialRepository.clearFeaturated}/
 *                           {@code markFeaturated} — at most one at a time)
 * @param contextName        one hop past the owner: the artist's name for
 *                           an album, the album's name for a track, {@code
 *                           null} for an artist (nothing one hop further to show)
 * @param releaseYear        the owning album/track's release year, {@code
 *                           null} for an artist
 * @param previewText        the editorial's first block's raw text (by
 *                           position), for a lead-card snippet without
 *                           fetching the full editorial — {@code null} if
 *                           it has no blocks yet
 * @param contextId          same idea as {@code contextName} but the id —
 *                           lets the archive deep-link a track editorial
 *                           straight into its album's editorial page
 *                           instead of nowhere
 */
public record EditorialSummaryDto(
    UUID id,
    EditorialOwnerType type,
    UUID ownerId,
    String ownerName,
    String ownerImageUrl,
    String title,
    String dek,
    String byline,
    Instant createdAt,
    int likeCount,
    boolean likedByCurrentUser,
    boolean featurated,
    String contextName,
    Integer releaseYear,
    String previewText,
    UUID contextId
) {
}
