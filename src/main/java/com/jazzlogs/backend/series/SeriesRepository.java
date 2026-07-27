package com.jazzlogs.backend.series;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.like.LikeableRepository;

public interface SeriesRepository extends LikeableRepository<Series> {

    @Modifying
    @Query("UPDATE Series s SET s.likeCount = s.likeCount + 1 WHERE s.id = :id")
    void incrementLikeCount(@Param("id") UUID entityId);

    @Modifying
    @Query("UPDATE Series s SET s.likeCount = GREATEST(s.likeCount - 1, 0) WHERE s.id = :id")
    void decrementLikeCount(@Param("id") UUID entityId);

    @Query("SELECT s.likeCount FROM Series s WHERE s.id = :id")
    Optional<Integer> findLikeCount(@Param("id") UUID entityId);

    Page<Series> findByStatus(SeriesStatus status, Pageable pageable);
}
