package com.jazzlogs.backend.like;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface LikeRepository extends JpaRepository<Like, LikeId> {

    @Query("""
        SELECT l.id.entityId
        FROM Like l
        WHERE l.id.userId = :userId AND l.id.entityType = :entityType AND l.id.entityId IN :entityIds
        """)
    List<UUID> findLikedEntityIds(
        @Param("userId") UUID userId,
        @Param("entityType") LikeableEntityType entityType,
        @Param("entityIds") List<UUID> entityIds
    );

    // Returns rows deleted (0 or 1, since the composite id is unique) so callers
    // know whether to decrement the entity's counter.
    @Modifying
    @Query("DELETE FROM Like l WHERE l.id.userId = :userId AND l.id.entityType = :entityType AND l.id.entityId = :entityId")
    int deleteByUserIdAndEntityTypeAndEntityId(
        @Param("userId") UUID userId,
        @Param("entityType") LikeableEntityType entityType,
        @Param("entityId") UUID entityId
    );
}
