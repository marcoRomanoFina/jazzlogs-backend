package com.jazzlogs.backend.series;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeriesChapterRepository extends JpaRepository<SeriesChapter, UUID> {

    // LEFT JOIN FETCH (not inner) — track is null for INTRO/OUTRO chapters.
    // Fetches album/artist too since SeriesChapterDetailDto denormalizes track info.
    @Query("""
        SELECT c FROM SeriesChapter c
        LEFT JOIN FETCH c.track t
        LEFT JOIN FETCH t.album a
        LEFT JOIN FETCH a.artist
        WHERE c.series.id = :seriesId
        ORDER BY c.position
        """)
    List<SeriesChapter> findBySeriesIdOrderByPosition(@Param("seriesId") UUID seriesId);

    long countBySeriesId(UUID seriesId);
}
