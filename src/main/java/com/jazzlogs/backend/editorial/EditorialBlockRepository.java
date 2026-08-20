package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EditorialBlockRepository extends JpaRepository<EditorialBlock, UUID> {

    // editorialId traverses EditorialBlock.editorial.id — no JOIN to any
    // subclass table (album_editorials etc.): editorial_id on this table
    // already points straight at the editorials row EDITORIAL_CONTENT wants.
    List<EditorialBlock> findByEditorialIdOrderByPositionAsc(UUID editorialId);

    List<EditorialBlock> findByEditorialIdAndContentCategoryInOrderByPositionAsc(UUID editorialId, List<BlockContentCategory> categories);

    // --- semanticSearch (agent tool) ---
    //
    // One method per entity type, same reasoning as GraphService's per-type
    // Neo4j finders: editorial_blocks has no direct entity_type/entity_id
    // column (it only knows editorial_id), and resolving it means joining
    // through a different subclass table per type — Album/Track
    // additionally filter on energy/accessibility/mood_intensity (columns
    // that live on albums/tracks, not editorial_blocks), Artist has neither.
    // A single UNION'd query would need to fake those columns for Artist
    // rows and re-derive the join target per row; three explicit queries
    // are simpler and match this project's editorial_summaries view, which
    // resolves the same Album/Track/Artist ownership the same way (see
    // V13__editorial_summaries_context_id.sql). SemanticSearchRequest.entityType
    // is singular and required, so a given call only ever invokes exactly
    // one of these — SemanticSearchService picks which one via a
    // Map<CatalogItemType, ...> lookup, not an if/switch chain.
    //
    // Native, not JPQL: pgvector's <=> (cosine distance) has no JPQL
    // equivalent. queryEmbedding is a pgvector text literal
    // ("[0.1,0.2,...]"), produced via `new PGvector(vector).getValue()` —
    // see SemanticSearchService — and CAST to vector here rather than bound
    // through PgVectorType, since native-query parameters aren't run
    // through Hibernate's UserType machinery. similarityScore is
    // 1 - cosine_distance (higher = more similar), matching the old,
    // now-deleted EDITORIAL_SEARCH tool's convention.
    //
    // *Ids is always non-empty when these are actually called —
    // SemanticSearchService.search() short-circuits on an empty/null
    // candidateIds before ever reaching the entityType dispatch below (an
    // empty "IN ()" is invalid SQL besides), so there's no null/empty-list
    // branch to handle here.

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

    // No energy/accessibility/moodIntensity — artists has none of those
    // columns (see SemanticSearchRequest's doc on why those never apply to
    // ARTIST candidates).
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

    interface SemanticMatchRow {
        UUID getEntityId();

        String getEntityName();

        String getBlockText();

        Double getSimilarityScore();
    }
}
