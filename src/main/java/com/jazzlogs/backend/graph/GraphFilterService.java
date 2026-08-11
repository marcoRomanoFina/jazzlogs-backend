package com.jazzlogs.backend.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import com.jazzlogs.backend.chat.CatalogItemType;

import lombok.AllArgsConstructor;

/**
 * Structural (graph-topology) prefilter for the agent's graphFilter tool —
 * standalone from the semantic search stage that may follow it (not
 * implemented yet; GraphFilterResult/GraphCandidate are already shaped for
 * that tool to consume as its candidate set). No shared state between calls:
 * every filter() call is self-contained.
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

    private static final List<CatalogItemType> DEFAULT_ENTITY_TYPES =
        List.of(CatalogItemType.ALBUM, CatalogItemType.TRACK, CatalogItemType.ARTIST);

    // Same reasoning/pattern as AgentOrchestrator.VIRTUAL_THREADS: the
    // per-label finders are independent, read-only Neo4j round-trips (no
    // shared state, nothing to serialize), so there's no reason to pay their
    // latency sequentially — dispatch whichever ones apply concurrently and
    // join before ranking.
    private static final Executor VIRTUAL_THREADS = Executors.newVirtualThreadPerTaskExecutor();

    private final GraphService graphService;

    public GraphFilterResult filter(GraphFilterFilters filters, UUID userId) {
        List<String> styleCodes = codesOf(filters.styles());
        List<String> rhythmCodes = codesOf(filters.rhythms());
        List<String> moodCodes = codesOf(filters.moods());
        List<String> contextCodes = codesOf(filters.contexts());
        List<String> instrumentCodes = codesOf(filters.instruments());

        List<CatalogItemType> entityTypes = filters.entityTypes() == null || filters.entityTypes().isEmpty()
            ? DEFAULT_ENTITY_TYPES
            : filters.entityTypes();
        boolean excludeListened = filters.excludeListened() == null || filters.excludeListened();
        boolean excludeAlreadyRated = filters.excludeAlreadyRated() == null || filters.excludeAlreadyRated();
        int topK = clampTopK(filters.topK());

        // Only dispatch a label's query if at least one dimension THAT
        // LABEL ACTUALLY CONNECTS TO has codes — e.g. requesting only
        // rhythms with entityTypes=[ARTIST] would guarantee every Artist
        // scores 0 (Artist has no Rhythm relation), so there's no point
        // paying that round-trip just to get told nothing matched. This
        // also subsumes the simpler "every dimension is empty" case: if
        // every list is empty, no label ever qualifies below and futures
        // stays empty, so no request-level rule call is needed for that
        // either — it's a plain consequence of this same check.
        List<CompletableFuture<List<GraphCandidate>>> futures = new ArrayList<>();
        if (entityTypes.contains(CatalogItemType.ALBUM) && anyNonEmpty(styleCodes, moodCodes, contextCodes)) {
            futures.add(CompletableFuture.supplyAsync(() -> graphService.findAlbumCandidates(
                styleCodes, moodCodes, contextCodes, userId, excludeListened, excludeAlreadyRated, topK
            ), VIRTUAL_THREADS));
        }
        if (entityTypes.contains(CatalogItemType.TRACK) && anyNonEmpty(moodCodes, contextCodes, rhythmCodes, instrumentCodes)) {
            futures.add(CompletableFuture.supplyAsync(() -> graphService.findTrackCandidates(
                moodCodes, contextCodes, rhythmCodes, instrumentCodes, userId, excludeListened, excludeAlreadyRated, topK
            ), VIRTUAL_THREADS));
        }
        if (entityTypes.contains(CatalogItemType.ARTIST) && anyNonEmpty(styleCodes, contextCodes, instrumentCodes)) {
            futures.add(CompletableFuture.supplyAsync(
                () -> graphService.findArtistCandidates(styleCodes, contextCodes, instrumentCodes, topK), VIRTUAL_THREADS
            ));
        }
        if (futures.isEmpty()) {
            return new GraphFilterResult(List.of());
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<GraphCandidate> candidates = futures.stream().flatMap(future -> future.join().stream()).toList();

        List<GraphCandidate> ranked = candidates.stream()
            .sorted(Comparator.comparingInt((GraphCandidate candidate) -> candidate.matchedDimensions().size()).reversed())
            .limit(topK)
            .toList();

        return new GraphFilterResult(ranked);
    }

    // Never trusts the model's number past MAX_TOP_K — clamped, not
    // rejected, per spec: asking for too much just gets you the max instead
    // of an error. Floored at 0 too, since a negative LIMIT would blow up
    // the Cypher query itself.
    private int clampTopK(Integer requested) {
        if (requested == null) {
            return DEFAULT_TOP_K;
        }
        return Math.min(Math.max(requested, 0), MAX_TOP_K);
    }

    private static <E extends Enum<E>> List<String> codesOf(List<E> values) {
        return values == null ? List.of() : values.stream().map(Enum::name).toList();
    }

    @SafeVarargs
    private static boolean anyNonEmpty(List<String>... codeLists) {
        for (List<String> codes : codeLists) {
            if (!codes.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
