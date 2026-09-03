package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "The Last Log" — the archive's other hero slot: the single most recently
 * published album editorial, together with every track editorial on that
 * album. Own shape, not {@link RecentAlbumEditorialDto}: this one also
 * carries {@code tracks}, which "Recently filed" never needs.
 *
 * @param id                 the album editorial's own id
 * @param title              the editorial's headline
 * @param artistName         the album's artist
 * @param dek                the editorial's short standfirst text
 * @param byline             who wrote it
 * @param releaseYear        the album's release year
 * @param postedAt           when this editorial was created
 * @param imageUrl           the album's cover image
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 * @param tracks             every track editorial on this album, ordered by track number
 */
public record LastLogDto(
    UUID id,
    String title,
    String artistName,
    String dek,
    String byline,
    Integer releaseYear,
    Instant postedAt,
    String imageUrl,
    int likeCount,
    boolean likedByCurrentUser,
    List<LastLogTrackDto> tracks
) {
}
