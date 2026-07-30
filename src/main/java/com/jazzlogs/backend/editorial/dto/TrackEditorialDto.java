package com.jazzlogs.backend.editorial.dto;

import java.util.List;

public record TrackEditorialDto(String title, List<EditorialBlockDto> blocks) {
}
