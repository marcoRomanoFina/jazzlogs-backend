package com.jazzlogs.backend.editorial.dto;

import java.util.UUID;

/**
 * "Recently filed"'s own shape — always an album editorial, so unlike
 * {@link CatalogueEditorialDto} this names things directly ({@code
 * albumName}/{@code artistName}) instead of generically ({@code
 * ownerName}/{@code contextName}), since there's no other owner type to
 * stay generic for.
 *
 * @param id                  the editorial's own id
 * @param albumId             the album this editorial is about
 * @param artistId            the album's artist
 * @param imageUrl            the album's cover image
 * @param title               the editorial's headline
 * @param albumName           the album's name
 * @param artistName          the artist's name
 * @param dek                 the editorial's short standfirst text
 * @param byline              who wrote it
 * @param logNumber           the album's catalog/release identifier
 * @param likeCount           denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser  computed separately via {@code LikeService}
 */
public record RecentAlbumEditorialDto(
    UUID id,
    UUID albumId,
    UUID artistId,
    String imageUrl,
    String title,
    String albumName,
    String artistName,
    String dek,
    String byline,
    String logNumber,
    int likeCount,
    boolean likedByCurrentUser
) {
}
