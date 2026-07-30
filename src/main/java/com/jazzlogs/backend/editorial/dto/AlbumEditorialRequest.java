package com.jazzlogs.backend.editorial.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public record AlbumEditorialRequest(
    @NotBlank String title,
    String dek,
    String byline,
    Integer readMinutes,
    List<BlockRequest> blocks
) {
}
