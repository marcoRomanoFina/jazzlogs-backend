package com.jazzlogs.backend.editorial.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public record TrackEditorialRequest(@NotBlank String title, List<BlockRequest> blocks) {
}
