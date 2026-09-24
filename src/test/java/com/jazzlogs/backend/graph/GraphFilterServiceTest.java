package com.jazzlogs.backend.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

// Pure Mockito unit test — GraphService's real Cypher/Neo4jClient internals
// are never exercised directly anywhere in this codebase (see GraphService
// itself and its other consumers' tests); GraphService is mocked here to
// return canned GraphCandidate lists, and these tests only cover
// GraphFilterService's own job: always searching TRACK, the
// no-vocab-and-no-scope short-circuit, defaulting, and passing the
// album/artist scope through untouched.
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
    void noVocabularyAndNoScope_shortCircuitsWithoutQuerying() {
        GraphFilterFilters filters = new GraphFilterFilters(null, null, null, null, null, null, null, null, null, null);

        GraphFilterResult result = graphFilterService.filter(filters, USER_ID);

        assertThat(result.candidates()).isEmpty();
        verifyNoInteractions(graphService);
    }

    @Test
    void vocabularyFilter_callsFindTrackCandidates() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            any(), any(), any(), any(), any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt()
        );
    }

    // A pure album/artist scope with no vocabulary filter at all must still
    // reach GraphService — "something from this album" has no style/mood to
    // filter on, only the scope.
    @Test
    void scopeAloneWithNoVocabulary_stillCallsFindTrackCandidates() {
        UUID albumId = UUID.randomUUID();
        GraphFilterFilters filters = new GraphFilterFilters(
            null, null, null, null, null, albumId, null, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            any(), any(), any(), any(), any(), eq(albumId), isNull(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt()
        );
    }

    @Test
    void albumAndArtistScope_arePassedThroughUntouched() {
        UUID albumId = UUID.randomUUID();
        UUID artistId = UUID.randomUUID();
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(StyleVocabulary.BEBOP), null, null, null, null, albumId, artistId, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            any(), any(), any(), any(), any(), eq(albumId), eq(artistId), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt()
        );
    }

    @Test
    void nullExcludeFlags_defaultToTrue() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            any(), any(), any(), any(), any(), any(), any(), eq(USER_ID), eq(true), eq(true), anyInt()
        );
    }

    @Test
    void explicitFalseExcludeFlags_arePassedThrough() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, false, false, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            any(), any(), any(), any(), any(), any(), any(), eq(USER_ID), eq(false), eq(false), anyInt()
        );
    }

    @Test
    void nullTopK_defaultsToDefaultTopK() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            any(), any(), any(), any(), any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(GraphFilterService.DEFAULT_TOP_K)
        );
    }

    @Test
    void topKAboveMax_isClampedRatherThanRejected() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null, null, 1000
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            any(), any(), any(), any(), any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(GraphFilterService.MAX_TOP_K)
        );
    }

    @Test
    void negativeTopK_isFlooredAtZero() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null, null, -5
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            any(), any(), any(), any(), any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(0)
        );
    }

    @Test
    void resultIsWhateverGraphServiceReturns_noJavaSideResorting() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null, null, null
        );
        GraphCandidate first = new GraphCandidate(CatalogItemType.TRACK, UUID.randomUUID(), "First Track", List.of(match(StyleVocabulary.BEBOP)));
        GraphCandidate second = new GraphCandidate(CatalogItemType.TRACK, UUID.randomUUID(), "Second Track", List.of(match(StyleVocabulary.BEBOP)));
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
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
            List.of(StyleVocabulary.BEBOP), null, List.of(MoodVocabulary.MELANCHOLIC), null, null, null, null, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        ArgumentCaptor<List<String>> styleCodes = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> moodCodes = ArgumentCaptor.forClass(List.class);
        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(
            styleCodes.capture(), moodCodes.capture(), any(), any(), any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt()
        );
        assertThat(styleCodes.getValue()).containsExactly("BEBOP");
        assertThat(moodCodes.getValue()).containsExactly("MELANCHOLIC");
    }

    private static MatchedDimension match(StyleVocabulary style) {
        return new MatchedDimension(VocabularyDimension.STYLE, style.name());
    }
}
