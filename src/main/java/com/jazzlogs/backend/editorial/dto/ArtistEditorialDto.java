package com.jazzlogs.backend.editorial.dto;

import java.util.List;

public record ArtistEditorialDto(String dek, String byline, Integer readMinutes, List<EditorialBlockDto> blocks) {
}
