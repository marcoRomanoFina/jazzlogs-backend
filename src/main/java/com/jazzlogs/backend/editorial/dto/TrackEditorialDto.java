package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.List;

import com.jazzlogs.backend.editorial.EditorialByline;

/**
 * A track's own editorial, as nested in {@code TrackDto}. No {@code id}
 * here — {@code likeCount}/{@code likedByCurrentUser} ARE here, but
 * {@code likedByCurrentUser} only ever resolves true when the caller had a
 * real user to check against (see {@code EditorialService#toTrackEditorialDto}) —
 * every other caller gets {@code likeCount} for free (already loaded on the
 * entity) and {@code false}, not a wasted query.
 *
 * @param title              the editorial's headline
 * @param logNumber          the log number JazzLogs refers to this entry by
 * @param dek                short standfirst text
 * @param byline             who wrote it
 * @param coverImageUrl      this editorial's cover image, {@code null} until uploaded
 * @param principalImageUrl  this editorial's principal image, {@code null} until uploaded
 * @param secondaryImageUrl  this editorial's secondary image, {@code null} until uploaded
 * @param bannerImageUrl     this editorial's banner image, {@code null} until uploaded
 * @param footerImageUrl     this editorial's footer image, {@code null} until uploaded
 * @param likeCount          denormalized total, read straight off the entity
 * @param likedByCurrentUser whether the caller's own user has liked it
 * @param createdAt          when this editorial was created
 * @param blocks             the editorial's body, in order
 */
public record TrackEditorialDto(
    String title,
    String logNumber,
    String dek,
    EditorialByline byline,
    String coverImageUrl,
    String principalImageUrl,
    String secondaryImageUrl,
    String bannerImageUrl,
    String footerImageUrl,
    int likeCount,
    boolean likedByCurrentUser,
    Instant createdAt,
    List<EditorialBlockDto> blocks
) {
}
