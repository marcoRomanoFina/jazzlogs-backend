package com.jazzlogs.backend.editorial.dto;

import java.util.List;
import java.util.UUID;

/**
 * An album's own editorial, as nested in {@code AlbumHeaderDto}.
 *
 * @param id                 the editorial's own id
 * @param title              the editorial's headline
 * @param dek                short standfirst text
 * @param byline             who wrote it
 * @param blocks             the editorial's body, in order
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 */
public record AlbumEditorialDto(
    UUID id,
    String title,
    String dek,
    String byline,
    List<EditorialBlockDto> blocks,
    int likeCount,
    boolean likedByCurrentUser
) {
}
