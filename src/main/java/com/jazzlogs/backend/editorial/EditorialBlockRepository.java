package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Backs {@code EditorialContentTool} and {@code SemanticSearchTool} (via {@code SemanticSearchService}). */
public interface EditorialBlockRepository extends JpaRepository<EditorialBlock, UUID> {

    /**
     * {@code editorialId} traverses {@code EditorialBlock.editorial.id} — no
     * JOIN to any subclass table ({@code album_editorials} etc.): {@code
     * editorial_id} on this table already points straight at the editorials
     * row EDITORIAL_CONTENT wants.
     */
    List<EditorialBlock> findByEditorialIdOrderByPositionAsc(UUID editorialId);

    /** Same as {@link #findByEditorialIdOrderByPositionAsc}, narrowed to specific {@code categories}. */
    List<EditorialBlock> findByEditorialIdAndContentCategoryInOrderByPositionAsc(UUID editorialId, List<BlockContentCategory> categories);

    // --- semanticSearch (agent tool) ---

    /**
     * One of {@link #semanticSearchAlbums}/{@link #semanticSearchTracks}/
     * {@link #semanticSearchArtists} — one query per entity type, since each
     * joins through a different subclass table (Album/Track also filter on
     * energy/accessibility/moodIntensity, which Artist doesn't have). {@code
     * SemanticSearchService} calls exactly one, matching {@code
     * SemanticSearchRequest.entityType}.
     *
     * <p>{@code *Ids} is always non-empty here — {@code
     * SemanticSearchService.search()} short-circuits on empty/null
     * candidateIds before reaching this.
     */
    @Query(value = """
        SELECT
            a.id AS entityId,
            a.name AS entityName,
            eb.text AS blockText,
            1 - (eb.embedding <=> CAST(:queryEmbedding AS vector)) AS similarityScore
        FROM editorial_blocks eb
        JOIN album_editorials ae ON eb.editorial_id = ae.editorial_id
        JOIN albums a ON a.id = ae.album_id
        WHERE eb.content_category = :category
          AND ae.album_id IN (:albumIds)
          AND (:energy IS NULL OR a.energy = :energy)
          AND (:accessibility IS NULL OR a.accessibility = :accessibility)
          AND (:moodIntensity IS NULL OR a.mood_intensity = :moodIntensity)
        ORDER BY eb.embedding <=> CAST(:queryEmbedding AS vector)
        """, nativeQuery = true)
    List<SemanticMatchRow> semanticSearchAlbums(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("albumIds") List<UUID> albumIds,
        @Param("category") String category,
        @Param("energy") String energy,
        @Param("accessibility") String accessibility,
        @Param("moodIntensity") String moodIntensity
    );

    /** Same shape as {@link #semanticSearchAlbums} — see its Javadoc. */
    @Query(value = """
        SELECT
            t.id AS entityId,
            t.name AS entityName,
            eb.text AS blockText,
            1 - (eb.embedding <=> CAST(:queryEmbedding AS vector)) AS similarityScore
        FROM editorial_blocks eb
        JOIN track_editorials te ON eb.editorial_id = te.editorial_id
        JOIN tracks t ON t.id = te.track_id
        WHERE eb.content_category = :category
          AND te.track_id IN (:trackIds)
          AND (:energy IS NULL OR t.energy = :energy)
          AND (:accessibility IS NULL OR t.accessibility = :accessibility)
          AND (:moodIntensity IS NULL OR t.mood_intensity = :moodIntensity)
        ORDER BY eb.embedding <=> CAST(:queryEmbedding AS vector)
        """, nativeQuery = true)
    List<SemanticMatchRow> semanticSearchTracks(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("trackIds") List<UUID> trackIds,
        @Param("category") String category,
        @Param("energy") String energy,
        @Param("accessibility") String accessibility,
        @Param("moodIntensity") String moodIntensity
    );

    /**
     * Same shape as {@link #semanticSearchAlbums} — see its Javadoc. No
     * energy/accessibility/moodIntensity: artists has none of those columns
     * (see {@code SemanticSearchRequest}'s doc on why those never apply to
     * ARTIST candidates).
     */
    @Query(value = """
        SELECT
            ar.id AS entityId,
            ar.name AS entityName,
            eb.text AS blockText,
            1 - (eb.embedding <=> CAST(:queryEmbedding AS vector)) AS similarityScore
        FROM editorial_blocks eb
        JOIN artist_editorials are ON eb.editorial_id = are.editorial_id
        JOIN artists ar ON ar.id = are.artist_id
        WHERE eb.content_category = :category
          AND are.artist_id IN (:artistIds)
        ORDER BY eb.embedding <=> CAST(:queryEmbedding AS vector)
        """, nativeQuery = true)
    List<SemanticMatchRow> semanticSearchArtists(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("artistIds") List<UUID> artistIds,
        @Param("category") String category
    );

    /** One row from {@link #semanticSearchAlbums}/{@link #semanticSearchTracks}/{@link #semanticSearchArtists}. */
    interface SemanticMatchRow {
        UUID getEntityId();

        String getEntityName();

        String getBlockText();

        Double getSimilarityScore();
    }
}
