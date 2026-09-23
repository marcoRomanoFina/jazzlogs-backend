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

    /**
     * For {@code SeriesService.getCatalogue}'s voice-filtered path — admin-only,
     * includes drafts. No other filters (no {@code q}/vocab params, unlike
     * editorials' catalogue).
     */
    Page<Series> findByVoice(SeriesVoice voice, Pageable pageable);

    /** Same as {@link #findByVoice}, but for non-admins — drafts stay invisible. */
    Page<Series> findByVoiceAndStatus(SeriesVoice voice, SeriesStatus status, Pageable pageable);

    /** For {@code SeriesService.getOnboardingSeries} — looked up by its fixed, curated title. */
    Optional<Series> findByTitle(String title);

    /** For {@code SeriesService.getFeatured} — the singleton featured series, if any is currently featured. */
    Optional<Series> findByFeaturedTrue();

    /**
     * The normal-path way to clear the previous featured row before marking
     * a new one — {@code SeriesService.setFeatured} calls this before
     * {@link #markFeatured}, both as atomic UPDATEs rather than
     * read-modify-save. This alone doesn't guarantee at most one stays
     * featured under concurrent calls; {@code idx_series_only_one_featured}
     * (see V38) is what actually does — same pattern as {@code PlaylistRepository}.
     */
    @Modifying
    @Query("UPDATE Series s SET s.featured = false WHERE s.featured = true")
    void clearFeatured();

    /** See {@link #clearFeatured} — always called right after it, never on its own. */
    @Modifying
    @Query("UPDATE Series s SET s.featured = true WHERE s.id = :id")
    void markFeatured(@Param("id") UUID id);

    /** A no-op if {@code id} wasn't featured to begin with. */
    @Modifying
    @Query("UPDATE Series s SET s.featured = false WHERE s.id = :id")
    void unmarkFeatured(@Param("id") UUID id);
}
