package com.jazzlogs.backend.semanticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.EditorialBlockRepository.SemanticMatchRow;
import com.jazzlogs.backend.embedding.EmbeddingService;

// Pure Mockito unit test — EditorialBlockRepository's real native/pgvector
// query is never exercised directly (same testing boundary this codebase
// already applies to GraphService: only the consumer service is unit
// tested, with the repository mocked to return canned rows). These tests
// cover SemanticSearchService's own job: the empty-candidateIds fast path
// and the entityType-keyed dispatch (no ifs — a
// Map<CatalogItemType, ...> lookup, see the service itself).
@ExtendWith(MockitoExtension.class)
class SemanticSearchServiceTest {

    @Mock
    private EditorialBlockRepository editorialBlockRepository;

    @Mock
    private EmbeddingService embeddingService;

    private SemanticSearchService semanticSearchService;

    @BeforeEach
    void setUp() {
        semanticSearchService = new SemanticSearchService(editorialBlockRepository, embeddingService);
    }

    @Test
    void emptyCandidateIds_shortCircuitsWithoutTouchingEmbeddingOrRepository() {
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, List.of(), BlockContentCategory.ANECDOTE, null, null, null, "a mellow late-night session"
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        assertThat(result.matches()).isEmpty();
        verifyNoInteractions(editorialBlockRepository, embeddingService);
    }

    @Test
    void nullCandidateIds_shortCircuitsTheSameWayAsEmpty() {
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, null, BlockContentCategory.ANECDOTE, null, null, null, "a mellow late-night session"
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        assertThat(result.matches()).isEmpty();
        verifyNoInteractions(editorialBlockRepository, embeddingService);
    }

    @Test
    void albumEntityType_callsOnlySemanticSearchAlbums() {
        UUID albumId = UUID.randomUUID();
        when(embeddingService.embed("groovy")).thenReturn(new float[] {0.1f, 0.2f});
        when(editorialBlockRepository.semanticSearchAlbums(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, List.of(albumId), BlockContentCategory.ANECDOTE, null, null, null, "groovy"
        );

        semanticSearchService.search(request);

        verify(editorialBlockRepository).semanticSearchAlbums(any(), eq(List.of(albumId)), any(), any(), any(), any());
        verify(editorialBlockRepository, never()).semanticSearchTracks(any(), any(), any(), any(), any(), any());
        verify(editorialBlockRepository, never()).semanticSearchArtists(any(), any(), any());
    }

    @Test
    void trackEntityType_callsOnlySemanticSearchTracks() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchTracks(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, List.of(UUID.randomUUID()), BlockContentCategory.MUSICAL_ANALYSIS, null, null, null, "q"
        );

        semanticSearchService.search(request);

        verify(editorialBlockRepository).semanticSearchTracks(any(), any(), any(), any(), any(), any());
        verify(editorialBlockRepository, never()).semanticSearchAlbums(any(), any(), any(), any(), any(), any());
        verify(editorialBlockRepository, never()).semanticSearchArtists(any(), any(), any());
    }

    @Test
    void artistEntityType_callsOnlySemanticSearchArtists_scalarFiltersNeverReachIt() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchArtists(any(), any(), any())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ARTIST, List.of(UUID.randomUUID()), BlockContentCategory.MOOD_AND_ATMOSPHERE,
            Level.HIGH, Level.LOW, Level.MEDIUM, "q"
        );

        semanticSearchService.search(request);

        // semanticSearchArtists has no energy/accessibility/moodIntensity
        // parameters at all — there's no code path for those values to
        // reach an Artist candidate, which is the whole point.
        verify(editorialBlockRepository).semanticSearchArtists(any(), any(), any());
        verify(editorialBlockRepository, never()).semanticSearchAlbums(any(), any(), any(), any(), any(), any());
        verify(editorialBlockRepository, never()).semanticSearchTracks(any(), any(), any(), any(), any(), any());
    }

    @Test
    void embeddingIsGeneratedFromQueryText() {
        when(embeddingService.embed("groovy")).thenReturn(new float[] {0.1f, 0.2f});
        when(editorialBlockRepository.semanticSearchAlbums(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, List.of(UUID.randomUUID()), BlockContentCategory.MUSICAL_ANALYSIS, null, null, null, "groovy"
        );

        semanticSearchService.search(request);

        verify(embeddingService, times(1)).embed("groovy");
    }

    @Test
    void categoryIsStampedOntoEveryScoredBlock() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        SemanticMatchRow row = matchRow(UUID.randomUUID(), "Kind of Blue", "a story about the session", 0.9);
        when(editorialBlockRepository.semanticSearchAlbums(any(), any(), any(), any(), any(), any())).thenReturn(List.of(row));
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, List.of(UUID.randomUUID()), BlockContentCategory.ANECDOTE, null, null, null, "q"
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).category()).isEqualTo(BlockContentCategory.ANECDOTE);
        assertThat(result.matches().get(0).entityType()).isEqualTo(CatalogItemType.ALBUM);
        assertThat(result.matches().get(0).entityName()).isEqualTo("Kind of Blue");
        assertThat(result.matches().get(0).blockText()).isEqualTo("a story about the session");
    }

    @Test
    void scalarFilters_arePassedThroughAsTheirNames() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchAlbums(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, List.of(UUID.randomUUID()), BlockContentCategory.MOOD_AND_ATMOSPHERE,
            Level.HIGH, Level.LOW, Level.MEDIUM, "q"
        );

        semanticSearchService.search(request);

        verify(editorialBlockRepository).semanticSearchAlbums(any(), any(), any(), eq("HIGH"), eq("LOW"), eq("MEDIUM"));
    }

    @Test
    void nullScalarFilters_arePassedAsNull() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchAlbums(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, List.of(UUID.randomUUID()), BlockContentCategory.PERSONAL_TAKE, null, null, null, "q"
        );

        semanticSearchService.search(request);

        verify(editorialBlockRepository).semanticSearchAlbums(any(), any(), any(), isNull(), isNull(), isNull());
    }

    @Test
    void resultIsWhateverRepositoryReturns_noJavaSideResorting() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        SemanticMatchRow first = matchRow(UUID.randomUUID(), "Album A", "text", 0.2);
        SemanticMatchRow second = matchRow(UUID.randomUUID(), "Album B", "text", 0.9);
        when(editorialBlockRepository.semanticSearchAlbums(any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(first, second));
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, List.of(UUID.randomUUID()), BlockContentCategory.RECOMMENDATION, null, null, null, "q"
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        // The repository's own ORDER BY <=> already ranks these — this
        // just proves the service passes that order through unchanged.
        assertThat(result.matches()).extracting(match -> match.entityName())
            .containsExactly("Album A", "Album B");
    }

    private static SemanticMatchRow matchRow(UUID entityId, String entityName, String blockText, double similarityScore) {
        SemanticMatchRow row = mock(SemanticMatchRow.class);
        when(row.getEntityId()).thenReturn(entityId);
        when(row.getEntityName()).thenReturn(entityName);
        when(row.getBlockText()).thenReturn(blockText);
        when(row.getSimilarityScore()).thenReturn(similarityScore);
        return row;
    }
}
