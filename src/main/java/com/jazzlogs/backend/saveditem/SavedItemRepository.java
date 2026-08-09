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

    // Batch — AlbumService.getAlbumDetail needs "is this track saved" for
    // every track on the album at once, not one existsById per track.
    @Query("SELECT s.id.entityId FROM SavedItem s WHERE s.id.userId = :userId AND s.id.entityType = :entityType AND s.id.entityId IN :entityIds")
    List<UUID> findSavedEntityIds(
        @Param("userId") UUID userId,
        @Param("entityType") SaveableEntityType entityType,
        @Param("entityIds") List<UUID> entityIds
    );

    // Returns rows deleted (0 or 1, since the composite id is unique) — used by
    // both SavedItemService.remove and ListenService's auto-remove hook, both
    // of which need this to no-op silently when the row doesn't exist.
    @Modifying
    @Query("DELETE FROM SavedItem s WHERE s.id.userId = :userId AND s.id.entityType = :entityType AND s.id.entityId = :entityId")
    int deleteByUserIdAndEntityTypeAndEntityId(
        @Param("userId") UUID userId,
        @Param("entityType") SaveableEntityType entityType,
        @Param("entityId") UUID entityId
    );
}
