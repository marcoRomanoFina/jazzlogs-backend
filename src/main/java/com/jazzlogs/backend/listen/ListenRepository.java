package com.jazzlogs.backend.listen;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListenRepository extends JpaRepository<Listen, ListenId> {

    /**
     * Idempotent — returns true if this call created the row, false if the user
     * had already listened to this entity (no-op, not an error). Same
     * exists-check-then-save-with-race-catch pattern as LikeService.addLike.
     */
    default boolean insertIfNotExists(UUID userId, ListenableEntityType entityType, UUID entityId) {
        ListenId id = new ListenId(userId, entityType, entityId);
        if (existsById(id)) {
            return false;
        }
        try {
            save(new Listen(id));
            return true;
        } catch (DataIntegrityViolationException concurrentListen) {
            return false;
        }
    }

    // Batch existence check — e.g. SeriesService's DONE/CURRENT/LOCKED
    // computation, one query for a whole series' chapters, not one existsById
    // per chapter.
    @Query("SELECT l.id.entityId FROM Listen l WHERE l.id.userId = :userId AND l.id.entityType = :entityType AND l.id.entityId IN :entityIds")
    List<UUID> findListenedEntityIds(
        @Param("userId") UUID userId,
        @Param("entityType") ListenableEntityType entityType,
        @Param("entityIds") List<UUID> entityIds
    );

    // Total plays across a set of entities, all users — e.g. SeriesService's
    // on-demand "totalListenings", same criterio as Album's avg rating.
    @Query("SELECT COUNT(l) FROM Listen l WHERE l.id.entityType = :entityType AND l.id.entityId IN :entityIds")
    long countByEntityTypeAndEntityIdIn(
        @Param("entityType") ListenableEntityType entityType,
        @Param("entityIds") List<UUID> entityIds
    );

    // Idempotent — returns rows deleted (0 or 1) — used by unmarkPlaylistListened.
    @Modifying
    @Query("DELETE FROM Listen l WHERE l.id.userId = :userId AND l.id.entityType = :entityType AND l.id.entityId = :entityId")
    int deleteByUserIdAndEntityTypeAndEntityId(
        @Param("userId") UUID userId,
        @Param("entityType") ListenableEntityType entityType,
        @Param("entityId") UUID entityId
    );
}
