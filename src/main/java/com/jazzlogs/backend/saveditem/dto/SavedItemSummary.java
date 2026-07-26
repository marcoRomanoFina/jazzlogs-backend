package com.jazzlogs.backend.saveditem.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.saveditem.SaveableEntityType;

// id is the underlying entity's own id (albumId/trackId/...), not a saved_items
// row id — SavedItem has no surrogate id, only the composite (user, type, entity).
// name/imageUrl/url are null when the underlying entity was deleted after being
// saved — see SavedItemService.toSummary.
public record SavedItemSummary(
    UUID id,
    SaveableEntityType entityType,
    String name,
    String imageUrl,
    String url,
    Instant savedAt
) {
}
