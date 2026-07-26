package com.jazzlogs.backend.saveditem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Implemented by each saveable entity's repository (AlbumRepository,
// TrackRepository, and PlaylistRepository once that entity exists) so
// SavedItemService can both check existence and resolve display data through a
// single Map<SaveableEntityType, SavedItemResolver> — same shape as
// LikeableRepository/LikeCountable for likes, just resolving display data
// instead of a counter.
public interface SavedItemResolver {

    // Empty if entityId doesn't exist — SavedItemService treats that as
    // "not found" on save, and "stale row, degrade gracefully" on list.
    Optional<Resolved> resolve(UUID entityId);

    // Default falls back to one resolve() per id — override with a real
    // findAllById-backed batch query (see AlbumRepository/TrackRepository) to
    // avoid SavedItemService.list() doing one query per row.
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        Map<UUID, Resolved> resolved = new HashMap<>();
        for (UUID entityId : entityIds) {
            resolve(entityId).ifPresent(value -> resolved.put(entityId, value));
        }
        return resolved;
    }

    record Resolved(String name, String imageUrl, String url) {
    }
}
