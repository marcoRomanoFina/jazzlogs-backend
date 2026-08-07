package com.jazzlogs.backend.editorial.dto;

import java.util.List;

public record ArtistEditorialDto(String title, String dek, String byline, List<EditorialBlockDto> blocks) {
}
