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

    // Explicit JPQL, not a derived findByUserIdAndAlbumId — Review also has a
    // convenience getUserId() (delegating to user.getId()), and Spring Data's
    // property-path resolver picks that plain method up as if "userId" were
    // its own mapped attribute, generating invalid JPQL ("Could not resolve
    // attribute 'userId' of Review") instead of drilling into the user
    // association. Spelling out user.id sidesteps the ambiguity entirely.
    @Query("SELECT r FROM Review r WHERE r.user.id = :userId AND r.album.id = :albumId")
    Optional<Review> findByUserIdAndAlbumId(@Param("userId") UUID userId, @Param("albumId") UUID albumId);

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
