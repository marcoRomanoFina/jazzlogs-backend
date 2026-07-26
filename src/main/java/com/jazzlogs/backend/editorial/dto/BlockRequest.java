package com.jazzlogs.backend.editorial.dto;

import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlockType;

public record BlockRequest(
    EditorialBlockType type,
    String title,
    String text,
    BlockContentCategory contentCategory
) {
}
