package com.jazzlogs.backend.review;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.like.LikeableRepository;

public interface ReviewRepository extends LikeableRepository<Review> {

    // Same atomic-UPDATE pattern as EditorialRepository/NoteRepository.
    @Modifying
    @Query("UPDATE Review r SET r.likeCount = r.likeCount + 1 WHERE r.id = :id")
    void incrementLikeCount(@Param("id") UUID entityId);

    @Modifying
    @Query("UPDATE Review r SET r.likeCount = GREATEST(r.likeCount - 1, 0) WHERE r.id = :id")
    void decrementLikeCount(@Param("id") UUID entityId);

    @Query("SELECT r.likeCount FROM Review r WHERE r.id = :id")
    Optional<Integer> findLikeCount(@Param("id") UUID entityId);

    Optional<Review> findByUserIdAndAlbumId(UUID userId, UUID albumId);

    // DISTINCT + JOIN FETCH: one query for the whole album's reviews AND their
    // standout tracks, instead of one lazy standoutTracks load per review.
    @Query("SELECT DISTINCT r FROM Review r LEFT JOIN FETCH r.standoutTracks WHERE r.album.id = :albumId ORDER BY r.createdAt DESC")
    List<Review> findByAlbumIdWithStandoutTracks(@Param("albumId") UUID albumId);

    @Query("SELECT AVG(r.rating) AS avgRating, COUNT(r) AS count FROM Review r WHERE r.album.id = :albumId")
    RatingStats getRatingStats(@Param("albumId") UUID albumId);

    interface RatingStats {
        BigDecimal getAvgRating();
        long getCount();
    }
}
