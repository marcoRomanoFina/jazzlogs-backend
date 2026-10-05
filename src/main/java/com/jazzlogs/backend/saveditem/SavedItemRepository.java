package com.jazzlogs.backend.saveditem;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedItemRepository extends JpaRepository<SavedItem, SavedItemId> {

    @Query("SELECT s FROM SavedItem s WHERE s.id.userId = :userId AND s.id.entityType = :entityType ORDER BY s.createdAt DESC")
    Page<SavedItem> findByUserIdAndEntityType(
        @Param("userId") UUID userId,
        @Param("entityType") SaveableEntityType entityType,
        Pageable pageable
    );

    // Batch — checks "is this saved" for a whole list of entities at once,
    // not one existsById per entity.
    @Query("SELECT s.id.entityId FROM SavedItem s WHERE s.id.userId = :userId AND s.id.entityType = :entityType AND s.id.entityId IN :entityIds")
    List<UUID> findSavedEntityIds(
        @Param("userId") UUID userId,
        @Param("entityType") SaveableEntityType entityType,
        @Param("entityIds") List<UUID> entityIds
    );

    /**
     * Deletes a user's saved item, if it exists. Used by both
     * {@link SavedItemService#remove} and {@code ListenService}'s
     * auto-remove hook, both of which need this to no-op silently when the
     * row doesn't exist.
     *
     * @param userId     the user
     * @param entityType which kind of entity
     * @param entityId   that entity's own id
     * @return rows deleted (0 or 1, since the composite id is unique)
     */
    @Modifying
    @Query("DELETE FROM SavedItem s WHERE s.id.userId = :userId AND s.id.entityType = :entityType AND s.id.entityId = :entityId")
    int deleteByUserIdAndEntityTypeAndEntityId(
        @Param("userId") UUID userId,
        @Param("entityType") SaveableEntityType entityType,
        @Param("entityId") UUID entityId
    );

    /** For {@code SavedItemService.deleteAllFor} — hard-deleting the entity itself, not one user's save of it. */
    @Modifying
    @Query("DELETE FROM SavedItem s WHERE s.id.entityType = :entityType AND s.id.entityId = :entityId")
    void deleteByEntityTypeAndEntityId(
        @Param("entityType") SaveableEntityType entityType,
        @Param("entityId") UUID entityId
    );
}
