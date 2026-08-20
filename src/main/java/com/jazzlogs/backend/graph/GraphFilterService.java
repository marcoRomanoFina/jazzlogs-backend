package com.jazzlogs.backend.graph;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.jazzlogs.backend.chat.CatalogItemType;

import lombok.AllArgsConstructor;

/**
 * Structural (graph-topology) prefilter for the agent's graphFilter tool —
 * standalone from the semantic search stage that may follow it (see
 * SemanticSearchService). No shared state between calls: every filter()
 * call is self-contained.
 *
 * userId is a plain method parameter, not a GraphFilterFilters field — it's
 * the authenticated user's id, not something the model should be able to
 * set. See GraphFilterTool for how it's threaded in from the agent loop.
 */
@Service
@AllArgsConstructor
public class GraphFilterService {

    // Not exposed to the model, same reasoning as every other internal cap
    // in this project (see e.g. ResolveJazzlogsEntityTool's MAX_CANDIDATES):
    // the model can ask for topK, but the server always has the final word.
    static final int DEFAULT_TOP_K = 15;
    static final int MAX_TOP_K = 30;

    private final GraphService graphService;

    public GraphFilterResult filter(GraphFilterFilters filters, UUID userId) {
        List<String> styleCodes = codesOf(filters.styles());
        List<String> rhythmCodes = codesOf(filters.rhythms());
        List<String> moodCodes = codesOf(filters.moods());
        List<String> contextCodes = codesOf(filters.contexts());
        List<String> instrumentCodes = codesOf(filters.instruments());

        boolean excludeListened = filters.excludeListened() == null || filters.excludeListened();
        boolean excludeAlreadyRated = filters.excludeAlreadyRated() == null || filters.excludeAlreadyRated();
        int topK = clampTopK(filters.topK());

        // Which vocabulary dimensions actually apply to a given entityType
        // — used only to short-circuit a guaranteed-empty call before
        // touching Neo4j at all (GraphService's Cypher already excludes
        // zero-match rows, this just skips the round-trip in Java too).
        Map<CatalogItemType, List<String>> relevantCodesByType = Map.of(
            CatalogItemType.ALBUM, concat(styleCodes, moodCodes, contextCodes),
            CatalogItemType.TRACK, concat(moodCodes, contextCodes, rhythmCodes, instrumentCodes),
            CatalogItemType.ARTIST, concat(styleCodes, contextCodes, instrumentCodes)
        );

        // Which finder to call, keyed by entityType instead of an if/switch
        // chain — same shape as ResolveJazzlogsEntityTool.resolversByType.
        // Each finder already does its own ORDER BY ... DESC LIMIT $limit
        // in Cypher, so whichever one gets picked IS the final, ranked
        // result — no merge/re-sort/re-clamp needed here.
        Map<CatalogItemType, Supplier<List<GraphCandidate>>> finders = Map.of(
            CatalogItemType.ALBUM, () -> graphService.findAlbumCandidates(
                styleCodes, moodCodes, contextCodes, userId, excludeListened, excludeAlreadyRated, topK
            ),
            CatalogItemType.TRACK, () -> graphService.findTrackCandidates(
                moodCodes, contextCodes, rhythmCodes, instrumentCodes, userId, excludeListened, excludeAlreadyRated, topK
            ),
            CatalogItemType.ARTIST, () -> graphService.findArtistCandidates(styleCodes, contextCodes, instrumentCodes, topK)
        );

        List<GraphCandidate> candidates = relevantCodesByType.get(filters.entityType()).isEmpty()
            ? List.of()
            : finders.get(filters.entityType()).get();

        return new GraphFilterResult(candidates);
    }

    // Never trusts the model's number past MAX_TOP_K — clamped, not
    // rejected, per spec: asking for too much just gets you the max instead
    // of an error. Floored at 0 too, since a negative LIMIT would blow up
    // the Cypher query itself.
    private int clampTopK(Integer requested) {
        return requested == null ? DEFAULT_TOP_K : Math.min(Math.max(requested, 0), MAX_TOP_K);
    }

    private static <E extends Enum<E>> List<String> codesOf(List<E> values) {
        return values == null ? List.of() : values.stream().map(Enum::name).toList();
    }

    @SafeVarargs
    private static List<String> concat(List<String>... codeLists) {
        return Arrays.stream(codeLists).flatMap(List::stream).toList();
    }
}
