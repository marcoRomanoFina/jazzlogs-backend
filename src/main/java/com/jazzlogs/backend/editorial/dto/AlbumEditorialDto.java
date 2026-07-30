package com.jazzlogs.backend.editorial.dto;

import java.util.List;
import java.util.UUID;

public record AlbumEditorialDto(
    UUID id,
    String title,
    String dek,
    String byline,
    Integer readMinutes,
    List<EditorialBlockDto> blocks,
    int likeCount,
    boolean likedByCurrentUser
) {
}
