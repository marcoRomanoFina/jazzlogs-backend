package com.jazzlogs.backend.editorial;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Backs {@code TrackSearchService}'s ranking by meaning and the HOOK preview in {@code EditorialService}. */
public interface EditorialBlockRepository extends JpaRepository<EditorialBlock, UUID> {

    /** {@code editorialId} is a {@code TrackEditorial}'s own id. */
    List<EditorialBlock> findByTrackEditorialIdAndContentCategoryInOrderByPositionAsc(UUID editorialId, List<BlockContentCategory> categories);

    // --- closest passages (track search) ---
    //
    // For each track, the one block of its log closest in meaning to a query,
    // and how close — best track first. One passage per track on purpose: the
    // search ranks tracks, and a log that matches the query in three blocks is
    // not three results.
    //
    // QUOTE blocks are left out: a quote is someone else's words set apart on
    // the page, not the log describing the music, so matching on one says
    // little about the track. They are still part of the log when it is read.

    /**
     * Over the whole catalog, in two steps so the cost does not grow with it:
     * the HNSW index first shortlists the {@code shortlist} blocks nearest to
     * the query, and only those are then reduced to one per track. The index
     * can answer "the nearest N blocks" but not "the nearest block of every
     * track", which would mean measuring the distance to all of them.
     *
     * <p>The price is that the result is approximate — a track whose best
     * block is not in the shortlist is missed — so the shortlist has to be
     * several times the number of tracks wanted. Call {@link
     * #widenVectorIndexSearch} first, in the same transaction: by default the
     * index gives up after 40 candidates, however many are asked for.
     */
    @Query(value = """
        SELECT trackId, category, text, similarity
        FROM (
            SELECT DISTINCT ON (te.track_id)
                te.track_id AS trackId,
                nearest.content_category AS category,
                nearest.text AS text,
                1 - nearest.distance AS similarity
            FROM (
                SELECT eb.editorial_id, eb.content_category, eb.text,
                       eb.embedding <=> CAST(:queryEmbedding AS vector) AS distance
                FROM editorial_blocks eb
                WHERE eb.type <> 'QUOTE'
                ORDER BY eb.embedding <=> CAST(:queryEmbedding AS vector)
                LIMIT :shortlist
            ) nearest
            JOIN track_editorials te ON te.id = nearest.editorial_id
            ORDER BY te.track_id, nearest.distance
        ) closest
        ORDER BY similarity DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<ClosestPassageRow> findClosestPassages(
        @Param("queryEmbedding") String queryEmbedding, @Param("shortlist") int shortlist, @Param("limit") int limit
    );

    /**
     * Lets the HNSW index consider up to {@code candidates} blocks per search
     * for the rest of the current transaction (pgvector's {@code
     * hnsw.ef_search}; the setting is transaction-local and gone at commit).
     *
     * @return the value now in effect — only there because a query has to return something
     */
    @Query(value = "SELECT set_config('hnsw.ef_search', CAST(:candidates AS text), true)", nativeQuery = true)
    String widenVectorIndexSearch(@Param("candidates") int candidates);

    /**
     * Among the logs of {@code trackIds} only, exactly: every block of those
     * tracks is measured. No index involved, and none needed — the caller
     * bounds the set of tracks, so the work is bounded with it.
     *
     * @param trackIds must not be empty — {@code IN ()} is not valid SQL
     */
    @Query(value = """
        SELECT trackId, category, text, similarity
        FROM (
            SELECT DISTINCT ON (te.track_id)
                te.track_id AS trackId,
                eb.content_category AS category,
                eb.text AS text,
                1 - (eb.embedding <=> CAST(:queryEmbedding AS vector)) AS similarity
            FROM editorial_blocks eb
            JOIN track_editorials te ON te.id = eb.editorial_id
            WHERE eb.type <> 'QUOTE'
              AND te.track_id IN (:trackIds)
            ORDER BY te.track_id, eb.embedding <=> CAST(:queryEmbedding AS vector)
        ) closest
        ORDER BY similarity DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<ClosestPassageRow> findClosestPassagesAmong(
        @Param("queryEmbedding") String queryEmbedding, @Param("trackIds") Collection<UUID> trackIds, @Param("limit") int limit
    );

    /** One track's closest passage; {@code similarity} is cosine similarity, higher is closer. */
    interface ClosestPassageRow {
        UUID getTrackId();

        String getCategory();

        String getText();

        Double getSimilarity();
    }
}
