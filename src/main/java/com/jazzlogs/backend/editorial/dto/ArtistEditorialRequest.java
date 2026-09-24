package com.jazzlogs.backend.editorial.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import com.jazzlogs.backend.editorial.EditorialByline;

// byline is optional — null defaults to EditorialByline.JAZZLOGS, see
// EditorialService's upsert methods.
public record ArtistEditorialRequest(
    @NotBlank String title,
    String dek,
    EditorialByline byline,
    List<BlockRequest> blocks
) {
}
