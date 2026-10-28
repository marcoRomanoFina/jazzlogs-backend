package com.jazzlogs.backend.saveditem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by each saveable entity's repository (AlbumRepository,
 * TrackRepository, and PlaylistRepository once that entity exists) so
 * {@link SavedItemService} can both check existence and resolve display data
 * through a single {@code Map<SaveableEntityType, SavedItemResolver>} — same
 * shape as LikeableRepository/LikeCountable for likes, just resolving
 * display data instead of a counter.
 */
public interface SavedItemResolver {

    /**
     * @param entityId the entity to resolve
     * @return its display data, empty if entityId doesn't exist —
     *         SavedItemService treats that as "not found" on save, and
     *         "stale row, degrade gracefully" on list
     */
    Optional<Resolved> resolve(UUID entityId);

    /**
     * Default falls back to one {@link #resolve} per id — override with a
     * real findAllById-backed batch query (see AlbumRepository/TrackRepository)
     * to avoid {@code SavedItemService.list()} doing one query per row.
     *
     * @param entityIds the entities to resolve
     * @return display data by entity id, missing an entry for any id that doesn't exist
     */
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        Map<UUID, Resolved> resolved = new HashMap<>();
        for (UUID entityId : entityIds) {
            resolve(entityId).ifPresent(value -> resolved.put(entityId, value));
        }
        return resolved;
    }

    /**
     * One entity's display data for a saved-item summary.
     *
     * @param name     display name
     * @param imageUrl cover/thumbnail
     */
    record Resolved(String name, String imageUrl) {
    }
}
