package com.jazzlogs.backend.editorial.dto;

import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlockType;

/**
 * One paragraph/quote/lead within an editorial's body.
 *
 * @param position       0-based order within the editorial
 * @param type           LEAD/PARA/QUOTE — how it renders
 * @param subhead        optional heading above this block; {@code null} for most
 * @param text           the block's own text — also what gets embedded (see {@code EmbeddingService})
 * @param contentCategory what kind of content this is (historical context, musical analysis, ...) —
 *                         used for semantic search filtering, not rendering
 */
public record EditorialBlockDto(
    int position,
    EditorialBlockType type,
    String subhead,
    String text,
    BlockContentCategory contentCategory
) {
}
