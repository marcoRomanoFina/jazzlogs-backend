package com.jazzlogs.backend.review;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    //
    // LEFT JOIN FETCH standoutTracks: updateReview clears/replaces this
    // collection in the same transaction that loaded it — fetching it here
    // avoids a second lazy-load round trip when that happens.
    @Query("SELECT r FROM Review r LEFT JOIN FETCH r.standoutTracks WHERE r.user.id = :userId AND r.album.id = :albumId")
    Optional<Review> findByUserIdAndAlbumId(@Param("userId") UUID userId, @Param("albumId") UUID albumId);

    // No JOIN FETCH here on purpose — a fetch join on a collection can't be
    // combined with Pageable (Hibernate would paginate in-memory instead of
    // in the DB). standoutTracks gets batched separately, see
    // findStandoutTracksForReviews below — same "one query for the whole
    // page, not one lazy load per review" principle as everywhere else.
    //
    // "Mine first" (currentUserId's own review, if any, always leads),
    // everyone else newest first — same idea as
    // NoteRepository.findByTrackIdOrderByMineFirst.
    @Query("SELECT r FROM Review r WHERE r.album.id = :albumId "
        + "ORDER BY CASE WHEN r.user.id = :currentUserId THEN 0 ELSE 1 END, r.createdAt DESC")
    Page<Review> findByAlbumIdOrderByMineFirst(
        @Param("albumId") UUID albumId, @Param("currentUserId") UUID currentUserId, Pageable pageable
    );

    @Query("SELECT r.id AS reviewId, t.id AS trackId, t.name AS trackName FROM Review r JOIN r.standoutTracks t WHERE r.id IN :reviewIds")
    List<StandoutTrackRow> findStandoutTracksForReviews(@Param("reviewIds") List<UUID> reviewIds);

    interface StandoutTrackRow {
        UUID getReviewId();

        UUID getTrackId();

        String getTrackName();
    }

    @Query("SELECT AVG(r.rating) AS avgRating, COUNT(r) AS count FROM Review r WHERE r.album.id = :albumId")
    RatingStats getRatingStats(@Param("albumId") UUID albumId);

    interface RatingStats {
        BigDecimal getAvgRating();
        long getCount();
    }
}
