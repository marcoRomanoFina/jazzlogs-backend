package com.jazzlogs.backend.saveditem;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.playlist.PlaylistRepository;
import com.jazzlogs.backend.saveditem.dto.SavedItemSummary;
import com.jazzlogs.backend.track.TrackRepository;

@Service
public class SavedItemService {

    private final SavedItemRepository savedItemRepository;
    private final Map<SaveableEntityType, SavedItemResolver> resolvers;
    private final EntityManager entityManager;

    /**
     * Add a resolver param + a resolvers entry per new saveable type — no
     * switch to touch, existence-checking (save) and display-data
     * resolution (list) both dispatch off this one map.
     */
    public SavedItemService(
        SavedItemRepository savedItemRepository,
        AlbumRepository albumRepository,
        TrackRepository trackRepository,
        PlaylistRepository playlistRepository,
        EntityManager entityManager
    ) {
        this.savedItemRepository = savedItemRepository;
        this.resolvers = Map.of(
            SaveableEntityType.ALBUM, albumRepository,
            SaveableEntityType.TRACK, trackRepository,
            SaveableEntityType.PLAYLIST, playlistRepository
        );
        this.entityManager = entityManager;
    }

    /**
     * Saves an entity on the caller's behalf. Idempotent — saving something
     * already saved is a no-op, not an error.
     *
     * @param userId     who is saving it
     * @param entityType which kind of entity
     * @param entityId   that entity's own id
     * @return true if this call created the save, false if the user had already saved it
     */
    @Transactional
    public boolean save(UUID userId, SaveableEntityType entityType, UUID entityId) {
        assertEntityExists(entityType, entityId);

        SavedItemId id = new SavedItemId(userId, entityType, entityId);
        if (savedItemRepository.existsById(id)) {
            return false;
        }
        try {
            // flush() forces the INSERT to run right here instead of at
            // commit time — same reasoning as LikeService.addLike. Both
            // exception types: flush() bypasses Spring's repository-method
            // AOP exception translation, so the raw Hibernate
            // ConstraintViolationException surfaces here, not Spring's
            // DataIntegrityViolationException.
            savedItemRepository.save(new SavedItem(id));
            entityManager.flush();
            return true;
        } catch (DataIntegrityViolationException | ConstraintViolationException concurrentSave) {
            // Another request inserted the same save between our exists() check
            // and save() — the end state is identical, so this is still success.
            return false;
        }
    }

    /**
     * Unsaves an entity on the caller's behalf. Idempotent — does nothing if
     * the saved item didn't exist.
     *
     * @param userId     who is unsaving it
     * @param entityType which kind of entity
     * @param entityId   that entity's own id
     */
    @Transactional
    public void remove(UUID userId, SaveableEntityType entityType, UUID entityId) {
        savedItemRepository.deleteByUserIdAndEntityTypeAndEntityId(userId, entityType, entityId);
    }

    /**
     * @param userId     whose saved items to check
     * @param entityType what {@code entityId} is (album, track, ...)
     * @param entityId   the entity to check
     * @return whether this user has saved this entity
     */
    @Transactional(readOnly = true)
    public boolean isSaved(UUID userId, SaveableEntityType entityType, UUID entityId) {
        return savedItemRepository.existsById(new SavedItemId(userId, entityType, entityId));
    }

    @Transactional(readOnly = true)
    public Set<UUID> getSavedEntityIds(UUID userId, SaveableEntityType entityType, List<UUID> entityIds) {
        return new HashSet<>(savedItemRepository.findSavedEntityIds(userId, entityType, entityIds));
    }

    /**
     * The caller's saved items of one type, paginated, newest first.
     * entityType is required — a single page is always one entity_type, so
     * display data resolves in exactly one batch query (via
     * {@link SavedItemResolver#resolveBatch}), not one per row and not a
     * per-type grouping.
     *
     * @param userId     the caller
     * @param entityType which kind of entity to list
     * @param pageable   page request
     * @return the matching page
     */
    @Transactional(readOnly = true)
    public Page<SavedItemSummary> list(UUID userId, SaveableEntityType entityType, Pageable pageable) {
        Page<SavedItem> page = savedItemRepository.findByUserIdAndEntityType(userId, entityType, pageable);

        List<UUID> entityIds = page.getContent().stream().map(item -> item.getId().entityId()).toList();
        Map<UUID, SavedItemResolver.Resolved> resolved = resolver(entityType).resolveBatch(entityIds);

        return page.map(item -> toSummary(item, entityType, resolved));
    }

    /**
     * Maps one saved item to its display summary. The underlying entity may
     * have been deleted after being saved — a missing entry in
     * {@code resolvedById} degrades to a summary with null display fields
     * instead of failing the whole page for one stale row.
     *
     * @param savedItem    the saved item
     * @param entityType   which kind of entity it is
     * @param resolvedById display data by entity id, from {@link SavedItemResolver#resolveBatch}
     * @return the mapped summary
     */
    private SavedItemSummary toSummary(SavedItem savedItem, SaveableEntityType entityType, Map<UUID, SavedItemResolver.Resolved> resolvedById) {
        UUID entityId = savedItem.getId().entityId();
        SavedItemResolver.Resolved resolved = resolvedById.getOrDefault(entityId, new SavedItemResolver.Resolved(null, null));
        return new SavedItemSummary(entityId, entityType, resolved.name(), resolved.imageUrl(), savedItem.getCreatedAt());
    }

    /** @throws ResponseStatusException 404 if no entity of that type/id exists */
    private void assertEntityExists(SaveableEntityType entityType, UUID entityId) {
        if (resolver(entityType).resolve(entityId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, entityType + " not found: " + entityId);
        }
    }

    /**
     * @throws ResponseStatusException 501 if this entityType has no entry in {@link #resolvers} yet
     */
    private SavedItemResolver resolver(SaveableEntityType entityType) {
        SavedItemResolver resolver = resolvers.get(entityType);
        if (resolver == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, entityType + " saved items aren't wired up yet");
        }
        return resolver;
    }
}
