package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Backs {@code EditorialContentTool} and {@code SemanticSearchTool} (via {@code SemanticSearchService}). */
public interface EditorialBlockRepository extends JpaRepository<EditorialBlock, UUID> {

    /** {@code editorialId} is a {@code TrackEditorial}'s own id — what EDITORIAL_CONTENT takes as input. */
    List<EditorialBlock> findByTrackEditorialIdOrderByPositionAsc(UUID editorialId);

    /** Same as {@link #findByTrackEditorialIdOrderByPositionAsc}, narrowed to specific {@code categories}. */
    List<EditorialBlock> findByTrackEditorialIdAndContentCategoryInOrderByPositionAsc(UUID editorialId, List<BlockContentCategory> categories);

    // --- semanticSearch (agent tool) ---

    /**
     * {@code trackIds} is always non-empty here — {@code
     * SemanticSearchService.search()} short-circuits on empty/null
     * candidateIds before reaching this. {@code LIMIT} isn't redundant with
     * "one block per category" — nothing enforces that invariant (no DB
     * constraint, {@code EditorialService.upsertBlocks} doesn't dedupe), so
     * a single editorial can legitimately contribute more than one match.
     */
    @Query(value = """
        SELECT
            t.id AS entityId,
            t.name AS entityName,
            eb.text AS blockText,
            1 - (eb.embedding <=> CAST(:queryEmbedding AS vector)) AS similarityScore
        FROM editorial_blocks eb
        JOIN track_editorials te ON eb.editorial_id = te.id
        JOIN tracks t ON t.id = te.track_id
        WHERE eb.content_category = :category
          AND te.track_id IN (:trackIds)
          AND (:energy IS NULL OR t.energy = :energy)
          AND (:accessibility IS NULL OR t.accessibility = :accessibility)
          AND (:moodIntensity IS NULL OR t.mood_intensity = :moodIntensity)
        ORDER BY eb.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<SemanticMatchRow> semanticSearchTracks(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("trackIds") List<UUID> trackIds,
        @Param("category") String category,
        @Param("energy") String energy,
        @Param("accessibility") String accessibility,
        @Param("moodIntensity") String moodIntensity,
        @Param("limit") int limit
    );

    /** One row from {@link #semanticSearchTracks}. */
    interface SemanticMatchRow {
        UUID getEntityId();

        String getEntityName();

        String getBlockText();

        Double getSimilarityScore();
    }
}
