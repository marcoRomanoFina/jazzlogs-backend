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

    // Native (pgvector's <=> / cosine distance has no JPQL equivalent).
    // queryEmbedding is a pgvector text literal ("[0.1,0.2,...]", see
    // EditorialSearchTool), cast to vector in SQL rather than relying on a
    // Hibernate UserType for a bare method parameter. category is an optional
    // prefilter applied in the WHERE clause BEFORE the distance sort/LIMIT —
    // never post-filtered after ranking. Postgres can infer its type here
    // because it's also compared against a real column elsewhere in the same
    // clause, so no explicit cast is needed for it like it is for
    // queryEmbedding. embedding_metadata is cast to text explicitly — reading
    // a jsonb column through a plain interface projection getter is
    // unreliable without that, see EditorialSearchTool for how the JSON
    // itself gets parsed.
    //
    // Two methods, not one editorialIds-nullable parameter: Hibernate expands
    // a List-valued native-query parameter into "IN (?, ?, ...)" wherever it
    // appears, which doesn't mix safely with also checking that same
    // parameter with "IS NULL" in the same query (and IN () on an empty list
    // is invalid SQL besides) — see EditorialSearchTool, which only ever
    // calls the scoped variant with a non-empty list, never null or empty.
    @Query(value = """
        SELECT
            eb.id AS id,
            eb.editorial_id AS editorialId,
            eb.content_category AS contentCategory,
            eb.text AS text,
            eb.embedding_metadata::text AS embeddingMetadataJson,
            1 - (eb.embedding <=> CAST(:queryEmbedding AS vector)) AS score
        FROM editorial_blocks eb
        WHERE (:category IS NULL OR eb.content_category = :category)
        ORDER BY eb.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<SemanticSearchRow> semanticSearch(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("category") String category,
        @Param("limit") int limit
    );

    @Query(value = """
        SELECT
            eb.id AS id,
            eb.editorial_id AS editorialId,
            eb.content_category AS contentCategory,
            eb.text AS text,
            eb.embedding_metadata::text AS embeddingMetadataJson,
            1 - (eb.embedding <=> CAST(:queryEmbedding AS vector)) AS score
        FROM editorial_blocks eb
        WHERE eb.editorial_id IN (:editorialIds)
          AND (:category IS NULL OR eb.content_category = :category)
        ORDER BY eb.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<SemanticSearchRow> semanticSearchScopedToEditorials(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("editorialIds") List<UUID> editorialIds,
        @Param("category") String category,
        @Param("limit") int limit
    );

    interface SemanticSearchRow {
        UUID getId();

        UUID getEditorialId();

        String getContentCategory();

        String getText();

        String getEmbeddingMetadataJson();

        Double getScore();
    }
}
