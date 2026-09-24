package com.jazzlogs.backend.semanticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
import org.springframework.web.server.ResponseStatusException;

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
// and the TRACK-only dispatch.
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
            CatalogItemType.TRACK, List.of(), BlockContentCategory.ANECDOTE, null, null, null, null, null, "a mellow late-night session"
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        assertThat(result.matches()).isEmpty();
        verifyNoInteractions(editorialBlockRepository, embeddingService);
    }

    @Test
    void nullCandidateIds_shortCircuitsTheSameWayAsEmpty() {
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, null, BlockContentCategory.ANECDOTE, null, null, null, null, null, "a mellow late-night session"
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        assertThat(result.matches()).isEmpty();
        verifyNoInteractions(editorialBlockRepository, embeddingService);
    }

    @Test
    void nonTrackEntityType_rejectsWithoutTouchingEmbeddingOrRepository() {
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.ALBUM, List.of(UUID.randomUUID()), BlockContentCategory.ANECDOTE, null, null, null, null, null, "q"
        );

        ResponseStatusException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
            ResponseStatusException.class, () -> semanticSearchService.search(request)
        );

        assertThat(ex.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(editorialBlockRepository, embeddingService);
    }

    @Test
    void trackEntityType_callsSemanticSearchTracks() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchTracks(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, List.of(UUID.randomUUID()), BlockContentCategory.MUSICAL_ANALYSIS, null, null, null, null, null, "q"
        );

        semanticSearchService.search(request);

        verify(editorialBlockRepository).semanticSearchTracks(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void embeddingIsGeneratedFromQueryText() {
        when(embeddingService.embed("groovy")).thenReturn(new float[] {0.1f, 0.2f});
        when(editorialBlockRepository.semanticSearchTracks(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, List.of(UUID.randomUUID()), BlockContentCategory.MUSICAL_ANALYSIS, null, null, null, null, null, "groovy"
        );

        semanticSearchService.search(request);

        verify(embeddingService, times(1)).embed("groovy");
    }

    @Test
    void categoryIsStampedOntoEveryScoredBlock() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        SemanticMatchRow row = matchRow(UUID.randomUUID(), "So What", "a story about the session", 0.9);
        when(editorialBlockRepository.semanticSearchTracks(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(row));
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, List.of(UUID.randomUUID()), BlockContentCategory.ANECDOTE, null, null, null, null, null, "q"
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).category()).isEqualTo(BlockContentCategory.ANECDOTE);
        assertThat(result.matches().get(0).entityType()).isEqualTo(CatalogItemType.TRACK);
        assertThat(result.matches().get(0).entityName()).isEqualTo("So What");
        assertThat(result.matches().get(0).blockText()).isEqualTo("a story about the session");
    }

    @Test
    void scalarFilters_arePassedThroughAsTheirNames() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchTracks(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, List.of(UUID.randomUUID()), BlockContentCategory.MOOD_AND_ATMOSPHERE,
            Level.HIGH, Level.LOW, Level.MEDIUM, null, null, "q"
        );

        semanticSearchService.search(request);

        verify(editorialBlockRepository).semanticSearchTracks(any(), any(), any(), eq("HIGH"), eq("LOW"), eq("MEDIUM"), any(), any(), anyInt());
    }

    @Test
    void nullScalarFilters_arePassedAsNull() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchTracks(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, List.of(UUID.randomUUID()), BlockContentCategory.PERSONAL_TAKE, null, null, null, null, null, "q"
        );

        semanticSearchService.search(request);

        verify(editorialBlockRepository).semanticSearchTracks(any(), any(), any(), isNull(), isNull(), isNull(), any(), any(), anyInt());
    }

    @Test
    void albumAndArtistScope_arePassedThroughUntouched() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchTracks(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        UUID albumId = UUID.randomUUID();
        UUID artistId = UUID.randomUUID();
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, List.of(UUID.randomUUID()), BlockContentCategory.MUSICAL_ANALYSIS, null, null, null, albumId, artistId, "q"
        );

        semanticSearchService.search(request);

        verify(editorialBlockRepository).semanticSearchTracks(any(), any(), any(), any(), any(), any(), eq(albumId), eq(artistId), anyInt());
    }

    @Test
    void resultIsWhateverRepositoryReturns_noJavaSideResorting() {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        SemanticMatchRow first = matchRow(UUID.randomUUID(), "Track A", "text", 0.2);
        SemanticMatchRow second = matchRow(UUID.randomUUID(), "Track B", "text", 0.9);
        when(editorialBlockRepository.semanticSearchTracks(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of(first, second));
        SemanticSearchRequest request = new SemanticSearchRequest(
            CatalogItemType.TRACK, List.of(UUID.randomUUID()), BlockContentCategory.RECOMMENDATION, null, null, null, null, null, "q"
        );

        SemanticSearchResult result = semanticSearchService.search(request);

        // The repository's own ORDER BY <=> already ranks these — this
        // just proves the service passes that order through unchanged.
        assertThat(result.matches()).extracting(match -> match.entityName())
            .containsExactly("Track A", "Track B");
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
