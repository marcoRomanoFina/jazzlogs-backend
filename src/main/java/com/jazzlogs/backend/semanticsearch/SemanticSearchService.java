package com.jazzlogs.backend.semanticsearch;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.pgvector.PGvector;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.EditorialBlockRepository.SemanticMatchRow;
import com.jazzlogs.backend.embedding.EmbeddingService;

import lombok.AllArgsConstructor;

/**
 * Semantic (pgvector) search over editorial_blocks — the second, independent
 * half of a two-tool pipeline with graphFilter (Neo4j structural prefilter).
 * No shared state between them: the candidate set is always passed in
 * explicitly (typically copied from a graphFilter result, but this works
 * standalone too — see SemanticSearchTool). Never fuses similarityScore
 * with graphFilter's matchedDimensions; that's the LLM's job in its final
 * synthesis, not this service's.
 */
@Service
@AllArgsConstructor
public class SemanticSearchService {

    private final EditorialBlockRepository editorialBlockRepository;
    private final EmbeddingService embeddingService;

    /** Fast-empty path: an empty/null candidateIds is a valid "nothing to search", not an error — skips embedding the query entirely. */
    public SemanticSearchResult search(SemanticSearchRequest request) {
        List<UUID> candidateIds = request.candidateIds();

        return candidateIds == null || candidateIds.isEmpty()
            ? new SemanticSearchResult(List.of())
            : new SemanticSearchResult(searchNonEmpty(request));
    }

    /** Embeds {@code queryText} once, then ranks {@code request.entityType()}'s candidates by cosine similarity to it. */
    private List<ScoredBlock> searchNonEmpty(SemanticSearchRequest request) {
        List<UUID> candidateIds = request.candidateIds();
        String queryEmbedding = new PGvector(embeddingService.embed(request.queryText())).getValue();
        String category = request.category().name();
        String energy = nameOrNull(request.energy());
        String accessibility = nameOrNull(request.accessibility());
        String moodIntensity = nameOrNull(request.moodIntensity());

        // Which repository query to run, keyed by entityType instead of an
        // if/switch chain — same shape as
        // ResolveJazzlogsEntityTool.resolversByType and
        // GraphFilterService.finders. Each branch's query already does its
        // own ORDER BY <=> in SQL, so whichever one gets picked IS the
        // final, ranked result — no merge/re-sort needed here.
        Map<CatalogItemType, Supplier<List<SemanticMatchRow>>> finders = Map.of(
            CatalogItemType.ALBUM, () -> editorialBlockRepository.semanticSearchAlbums(
                queryEmbedding, candidateIds, category, energy, accessibility, moodIntensity
            ),
            CatalogItemType.TRACK, () -> editorialBlockRepository.semanticSearchTracks(
                queryEmbedding, candidateIds, category, energy, accessibility, moodIntensity
            ),
            CatalogItemType.ARTIST, () -> editorialBlockRepository.semanticSearchArtists(
                queryEmbedding, candidateIds, category
            )
        );

        return toScoredBlocks(request.entityType(), request.category(), finders.get(request.entityType()).get());
    }

    private static List<ScoredBlock> toScoredBlocks(CatalogItemType entityType, BlockContentCategory category, List<SemanticMatchRow> rows) {
        return rows.stream()
            .map(row -> new ScoredBlock(
                entityType,
                row.getEntityId(),
                category,
                row.getSimilarityScore(),
                row.getBlockText(),
                row.getEntityName()
            ))
            .toList();
    }

    private static String nameOrNull(Level level) {
        return level == null ? null : level.name();
    }
}
