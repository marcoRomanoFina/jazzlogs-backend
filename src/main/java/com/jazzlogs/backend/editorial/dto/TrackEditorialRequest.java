package com.jazzlogs.backend.editorial.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import com.jazzlogs.backend.editorial.EditorialByline;

// byline is optional — null defaults to EditorialByline.JAZZLOGS, see
// EditorialService's upsert methods.
public record TrackEditorialRequest(
    @NotBlank String title,
    @NotBlank String logNumber,
    @NotBlank String dek,
    EditorialByline byline,
    List<BlockRequest> blocks
) {
}
