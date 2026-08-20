package com.jazzlogs.backend.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

// Pure Mockito unit test — GraphService's real Cypher/Neo4jClient internals
// are never exercised directly anywhere in this codebase (see GraphService
// itself and its other consumers' tests); GraphService is mocked here to
// return canned GraphCandidate lists, and these tests only cover
// GraphFilterService's own job: the entityType-keyed dispatch (no ifs — a
// Map<CatalogItemType, ...> lookup, see the service itself), defaulting,
// and the empty/irrelevant-filters short-circuit.
@ExtendWith(MockitoExtension.class)
class GraphFilterServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private GraphService graphService;

    private GraphFilterService graphFilterService;

    @BeforeEach
    void setUp() {
        graphFilterService = new GraphFilterService(graphService);
    }

    @Test
    void allVocabularyFiltersEmpty_shortCircuitsWithoutQuerying() {
        GraphFilterFilters filters = new GraphFilterFilters(CatalogItemType.ALBUM, null, null, null, null, null, null, null, null);

        GraphFilterResult result = graphFilterService.filter(filters, USER_ID);

        assertThat(result.candidates()).isEmpty();
        verifyNoInteractions(graphService);
    }

    // Artist has no Rhythm relation (see GraphService.findArtistCandidates)
    // — requesting only rhythms with entityType=ARTIST can never match
    // anything, so this should short-circuit exactly like the all-empty
    // case above, without ever calling GraphService.
    @Test
    void onlyIrrelevantDimensionForRequestedEntityType_shortCircuitsWithoutQuerying() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ARTIST, null, List.of(RhythmVocabulary.MEDIUM_SWING), null, null, null, null, null, null
        );

        GraphFilterResult result = graphFilterService.filter(filters, USER_ID);

        assertThat(result.candidates()).isEmpty();
        verifyNoInteractions(graphService);
    }

    @Test
    void albumEntityType_callsOnlyFindAlbumCandidates() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ALBUM, List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService, never()).findTrackCandidates(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService, never()).findArtistCandidates(any(), any(), any(), anyInt());
    }

    @Test
    void trackEntityType_callsOnlyFindTrackCandidates() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.TRACK, null, null, List.of(MoodVocabulary.MELANCHOLIC), null, null, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(any(), any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService, never()).findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService, never()).findArtistCandidates(any(), any(), any(), anyInt());
    }

    @Test
    void artistEntityType_callsOnlyFindArtistCandidates() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ARTIST, List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null
        );
        when(graphService.findArtistCandidates(any(), any(), any(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findArtistCandidates(any(), any(), any(), anyInt());
        verify(graphService, never()).findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService, never()).findTrackCandidates(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt());
    }

    @Test
    void nullExcludeFlags_defaultToTrue() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ALBUM, List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), eq(true), eq(true), anyInt());
    }

    @Test
    void explicitFalseExcludeFlags_arePassedThrough() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ALBUM, List.of(StyleVocabulary.BEBOP), null, null, null, null, false, false, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), eq(false), eq(false), anyInt());
    }

    @Test
    void excludeFlags_defaultToTrue_forTrackToo() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.TRACK, null, null, List.of(MoodVocabulary.MELANCHOLIC), null, null, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(any(), any(), any(), any(), eq(USER_ID), eq(true), eq(true), anyInt());
    }

    @Test
    void nullTopK_defaultsToDefaultTopK() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ALBUM, List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(GraphFilterService.DEFAULT_TOP_K));
    }

    @Test
    void topKAboveMax_isClampedRatherThanRejected() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ALBUM, List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, 1000
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(GraphFilterService.MAX_TOP_K));
    }

    @Test
    void negativeTopK_isFlooredAtZero() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ALBUM, List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, -5
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(0));
    }

    @Test
    void resultIsWhateverGraphServiceReturns_noJavaSideResorting() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ALBUM, List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null
        );
        GraphCandidate first = new GraphCandidate(CatalogItemType.ALBUM, UUID.randomUUID(), "First Album", List.of(match(StyleVocabulary.BEBOP)));
        GraphCandidate second = new GraphCandidate(CatalogItemType.ALBUM, UUID.randomUUID(), "Second Album", List.of(match(StyleVocabulary.BEBOP)));
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of(first, second));

        GraphFilterResult result = graphFilterService.filter(filters, USER_ID);

        // GraphService's Cypher already sorts/limits — GraphFilterService
        // must pass that ordering through untouched, not recompute it.
        assertThat(result.candidates()).containsExactly(first, second);
    }

    // ArgumentCaptor.forClass(List.class) is the standard Mockito idiom for
    // capturing a generic-typed argument — List<String>.class doesn't exist
    // in Java (type erasure), so the raw-type Class token is unavoidable
    // here and always needs an unchecked-conversion suppression.
    @SuppressWarnings("unchecked")
    @Test
    void vocabularyEnumsArePassedAsTheirCodeNames() {
        GraphFilterFilters filters = new GraphFilterFilters(
            CatalogItemType.ALBUM, List.of(StyleVocabulary.BEBOP), null, List.of(MoodVocabulary.MELANCHOLIC), null, null, null, null, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        ArgumentCaptor<List<String>> styleCodes = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> moodCodes = ArgumentCaptor.forClass(List.class);
        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(styleCodes.capture(), moodCodes.capture(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt());
        assertThat(styleCodes.getValue()).containsExactly("BEBOP");
        assertThat(moodCodes.getValue()).containsExactly("MELANCHOLIC");
    }

    private static MatchedDimension match(StyleVocabulary style) {
        return new MatchedDimension(VocabularyDimension.STYLE, style.name());
    }
}
