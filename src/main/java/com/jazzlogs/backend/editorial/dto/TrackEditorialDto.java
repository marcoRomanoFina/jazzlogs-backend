package com.jazzlogs.backend.editorial.dto;

import java.util.List;

public record TrackEditorialDto(String title, String dek, String byline, List<EditorialBlockDto> blocks) {
}
