package com.jazzlogs.backend.graph;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;

/**
 * Structural (graph-topology) prefilter for the agent's graphFilter tool —
 * standalone from the semantic search stage that may follow it (see {@code
 * SemanticSearchService}). No shared state between calls: every {@link
 * #filter} call is self-contained. {@code userId} is a plain method
 * parameter, not a {@link GraphFilterFilters} field, since it's the
 * authenticated user's id, not something the model should set (see {@code
 * GraphFilterTool} for how it's threaded in from the agent loop).
 */
@Service
@AllArgsConstructor
public class GraphFilterService {

    /**
     * Not exposed to the model, same reasoning as every other internal cap
     * in this project (see e.g. {@code ResolveJazzlogsEntityTool.MAX_CANDIDATES}):
     * the model can ask for topK, but the server always has the final word.
     */
    static final int DEFAULT_TOP_K = 15;
    static final int MAX_TOP_K = 30;

    private final GraphService graphService;

    /**
     * Ranks TRACK candidates by graph-topology overlap with the given
     * vocabulary filters, optionally narrowed to one album's/artist's own
     * tracks. Short-circuits to an empty result if the call carries neither
     * a vocabulary filter nor an album/artist scope — that combination
     * can't match anything.
     *
     * @param userId the authenticated user, used to exclude already
     *               listened/rated items by default — never the model's
     *               to set, hence a separate parameter rather than a
     *               {@link GraphFilterFilters} field
     */
    public GraphFilterResult filter(GraphFilterFilters filters, UUID userId) {
        List<String> styleCodes = codesOf(filters.styles());
        List<String> rhythmCodes = codesOf(filters.rhythms());
        List<String> moodCodes = codesOf(filters.moods());
        List<String> contextCodes = codesOf(filters.contexts());
        List<String> instrumentCodes = codesOf(filters.instruments());

        boolean excludeListened = filters.excludeListened() == null || filters.excludeListened();
        boolean excludeAlreadyRated = filters.excludeAlreadyRated() == null || filters.excludeAlreadyRated();
        int topK = clampTopK(filters.topK());

        boolean hasVocabFilter = !concat(styleCodes, moodCodes, contextCodes, rhythmCodes, instrumentCodes).isEmpty();
        boolean hasScope = filters.albumId() != null || filters.artistId() != null;
        if (!hasVocabFilter && !hasScope) {
            return new GraphFilterResult(List.of());
        }

        List<GraphCandidate> candidates = graphService.findTrackCandidates(
            styleCodes, moodCodes, contextCodes, rhythmCodes, instrumentCodes,
            filters.albumId(), filters.artistId(), userId, excludeListened, excludeAlreadyRated, topK
        );

        return new GraphFilterResult(candidates);
    }

    /**
     * Never trusts the model's number past {@code MAX_TOP_K} — clamped, not
     * rejected, per spec: asking for too much just gets you the max instead
     * of an error. Floored at 0 too, since a negative LIMIT would blow up
     * the Cypher query itself.
     */
    private int clampTopK(Integer requested) {
        return requested == null ? DEFAULT_TOP_K : Math.min(Math.max(requested, 0), MAX_TOP_K);
    }

    /** {@code GraphFilterFilters}' enum lists as the raw code strings Cypher binds — null (field omitted) becomes empty, not NPE. */
    private static <E extends Enum<E>> List<String> codesOf(List<E> values) {
        return values == null ? List.of() : values.stream().map(Enum::name).toList();
    }

    /** Flattens several code lists into one — used to build {@code relevantCodesByType}'s per-entityType union. */
    @SafeVarargs
    private static List<String> concat(List<String>... codeLists) {
        return Arrays.stream(codeLists).flatMap(List::stream).toList();
    }
}
