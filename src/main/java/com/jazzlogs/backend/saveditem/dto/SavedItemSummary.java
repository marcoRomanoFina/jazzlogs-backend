package com.jazzlogs.backend.saveditem.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.saveditem.SaveableEntityType;

/**
 * One entry in the caller's saved-items list.
 *
 * @param id         the underlying entity's own id (albumId/trackId/...),
 *                   not a saved_items row id — SavedItem has no surrogate
 *                   id, only the composite (user, type, entity). The
 *                   frontend derives the link to the entity from this and
 *                   {@code entityType} — no separate url field
 * @param entityType which kind of entity
 * @param name       display name, null if the underlying entity was deleted after being saved
 * @param imageUrl   cover/thumbnail, null under the same condition
 * @param savedAt    when this was saved
 */
public record SavedItemSummary(
    UUID id,
    SaveableEntityType entityType,
    String name,
    String imageUrl,
    Instant savedAt
) {
}
