package com.jazzlogs.backend.editorial.dto;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.editorial.EditorialByline;

/**
 * An album's own editorial, as nested in {@code AlbumHeaderDto}.
 *
 * @param id                 the editorial's own id
 * @param title              the editorial's headline
 * @param dek                short standfirst text
 * @param byline             who wrote it
 * @param principalImageUrl  this editorial's hero image, {@code null} until uploaded
 * @param secondaryImageUrl  a second, distinct image, {@code null} until uploaded
 * @param bannerImageUrl     the banner image, {@code null} until uploaded
 * @param footerImageUrl     the footer image, {@code null} until uploaded
 * @param blocks             the editorial's body, in order
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 */
public record AlbumEditorialDto(
    UUID id,
    String title,
    String dek,
    EditorialByline byline,
    String principalImageUrl,
    String secondaryImageUrl,
    String bannerImageUrl,
    String footerImageUrl,
    List<EditorialBlockDto> blocks,
    int likeCount,
    boolean likedByCurrentUser
) {
}
