package com.jazzlogs.backend.editorial;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.like.LikeableRepository;

// Repository on the JOINED-inheritance base class — every AlbumEditorial/
// TrackEditorial/ArtistEditorial row has a corresponding row in `editorials`,
// so existsById here is enough to validate a LIKE's entityId regardless of
// which concrete editorial subtype it points to.
public interface EditorialRepository extends LikeableRepository<Editorial> {

    // Atomic UPDATE, not read-modify-save — two concurrent likes must not race
    // and lose an increment.
    @Modifying
    @Query("UPDATE Editorial e SET e.likeCount = e.likeCount + 1 WHERE e.id = :id")
    void incrementLikeCount(@Param("id") UUID entityId);

    @Modifying
    @Query("UPDATE Editorial e SET e.likeCount = GREATEST(e.likeCount - 1, 0) WHERE e.id = :id")
    void decrementLikeCount(@Param("id") UUID entityId);

    @Query("SELECT e.likeCount FROM Editorial e WHERE e.id = :id")
    Optional<Integer> findLikeCount(@Param("id") UUID entityId);
}
