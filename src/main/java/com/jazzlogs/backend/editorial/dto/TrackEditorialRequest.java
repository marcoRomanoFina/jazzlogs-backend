package com.jazzlogs.backend.editorial.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;


public record TrackEditorialRequest(
    @NotBlank String title,
    @NotBlank String logNumber,
    @NotBlank String dek,
    List<BlockRequest> blocks
) {
}
