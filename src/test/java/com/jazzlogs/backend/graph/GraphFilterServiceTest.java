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
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

// Pure Mockito unit test — GraphService's real Cypher/Neo4jClient internals
// are never exercised directly anywhere in this codebase (see GraphService
// itself and its other consumers' tests); GraphService is mocked here to
// return canned GraphCandidate lists, and these tests only cover
// GraphFilterService's own job: defaulting, the empty-filters short-circuit,
// per-entityType dispatch, and the merge/sort/clamp of whatever the (mocked)
// finder methods return.
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
        GraphFilterFilters filters = new GraphFilterFilters(null, null, null, null, null, null, null, null, null);

        GraphFilterResult result = graphFilterService.filter(filters, USER_ID);

        assertThat(result.candidates()).isEmpty();
        verifyNoInteractions(graphService);
    }

    // Artist has no Rhythm relation (see GraphService.findArtistCandidates)
    // — requesting only rhythms with entityTypes=[ARTIST] can never match
    // anything, so this should short-circuit exactly like the all-empty
    // case above, without ever calling GraphService.
    @Test
    void onlyIrrelevantDimensionForRequestedEntityType_shortCircuitsWithoutQuerying() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ARTIST), null, List.of(RhythmVocabulary.MEDIUM_SWING), null, null, null, null, null, null
        );

        GraphFilterResult result = graphFilterService.filter(filters, USER_ID);

        assertThat(result.candidates()).isEmpty();
        verifyNoInteractions(graphService);
    }

    // contexts is relevant to all three entity types (Album/Track/Artist
    // each have a PERFECT_FOR relation to Context) — needed so this
    // exercises "all three finders get called", not the entityType-aware
    // short-circuit skipping Track/Artist for a style-only filter.
    @Test
    void nullEntityTypes_defaultsToAllThree_andQueriesAllThreeFinders() {
        GraphFilterFilters filters = new GraphFilterFilters(
            null, null, null, null, List.of(ContextVocabulary.CREATIVE_WORK), null, null, null, null
        );
        stubAllFindersEmpty();

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService).findTrackCandidates(any(), any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService).findArtistCandidates(any(), any(), any(), anyInt());
    }

    @Test
    void restrictedEntityTypes_onlyQueriesMatchingFinders() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ALBUM), List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService, never()).findTrackCandidates(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt());
        verify(graphService, never()).findArtistCandidates(any(), any(), any(), anyInt());
    }

    @Test
    void nullExcludeFlags_defaultToTrue() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ALBUM), List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), eq(true), eq(true), anyInt());
    }

    @Test
    void explicitFalseExcludeFlags_arePassedThrough() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ALBUM), List.of(StyleVocabulary.BEBOP), null, null, null, null, false, false, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), eq(false), eq(false), anyInt());
    }

    // excludeAlreadyRated applies to Track too (via RATED_TRACK, see
    // GraphService.findTrackCandidates) — not just Album.
    @Test
    void excludeFlags_defaultToTrue_forTrackToo() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.TRACK), null, null, List.of(MoodVocabulary.MELANCHOLIC), null, null, null, null, null
        );
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findTrackCandidates(any(), any(), any(), any(), eq(USER_ID), eq(true), eq(true), anyInt());
    }

    @Test
    void nullTopK_defaultsToDefaultTopK() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ALBUM), List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, null
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(GraphFilterService.DEFAULT_TOP_K));
    }

    @Test
    void topKAboveMax_isClampedRatherThanRejected() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ALBUM), List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, 1000
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(GraphFilterService.MAX_TOP_K));
    }

    @Test
    void negativeTopK_isFlooredAtZero() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ALBUM), List.of(StyleVocabulary.BEBOP), null, null, null, null, null, null, -5
        );
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());

        graphFilterService.filter(filters, USER_ID);

        verify(graphService).findAlbumCandidates(any(), any(), any(), eq(USER_ID), anyBoolean(), anyBoolean(), eq(0));
    }

    @Test
    void mergesResultsAcrossEntityTypes_sortsDescByMatchedDimensionCount_andClampsToTopK() {
        GraphFilterFilters filters = new GraphFilterFilters(
            null, List.of(StyleVocabulary.BEBOP), null, List.of(MoodVocabulary.MELANCHOLIC), null, null, null, null, 2
        );
        GraphCandidate lowAlbum = new GraphCandidate(CatalogItemType.ALBUM, UUID.randomUUID(), List.of(match(StyleVocabulary.BEBOP)));
        GraphCandidate highTrack = new GraphCandidate(CatalogItemType.TRACK, UUID.randomUUID(),
            List.of(match(MoodVocabulary.MELANCHOLIC), match(StyleVocabulary.BEBOP), match(MoodVocabulary.MELANCHOLIC)));
        GraphCandidate midArtist = new GraphCandidate(CatalogItemType.ARTIST, UUID.randomUUID(),
            List.of(match(StyleVocabulary.BEBOP), match(MoodVocabulary.MELANCHOLIC)));
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of(lowAlbum));
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of(highTrack));
        when(graphService.findArtistCandidates(any(), any(), any(), anyInt())).thenReturn(List.of(midArtist));

        GraphFilterResult result = graphFilterService.filter(filters, USER_ID);

        // topK=2: only the top 2 by matchedDimensions.size() survive, in
        // descending order — the album candidate (1 match) is dropped.
        assertThat(result.candidates()).containsExactly(highTrack, midArtist);
    }

    // Permissive matching (see GraphService's per-label Cypher) means a
    // candidate that only matched 1 of several requested dimensions is
    // still eligible — GraphFilterService must not apply any additional
    // "matched everything requested" filter of its own on top of whatever
    // GraphService already returned.
    @Test
    void candidateMatchingOnlyOneOfSeveralRequestedFilters_stillAppearsInResult() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ALBUM), List.of(StyleVocabulary.BEBOP), null,
            List.of(MoodVocabulary.MELANCHOLIC), null, null, null, null, null
        );
        GraphCandidate partialMatch = new GraphCandidate(CatalogItemType.ALBUM, UUID.randomUUID(), List.of(match(StyleVocabulary.BEBOP)));
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of(partialMatch));

        GraphFilterResult result = graphFilterService.filter(filters, USER_ID);

        assertThat(result.candidates()).containsExactly(partialMatch);
    }

    @Test
    void vocabularyEnumsArePassedAsTheirCodeNames() {
        GraphFilterFilters filters = new GraphFilterFilters(
            List.of(CatalogItemType.ALBUM), List.of(StyleVocabulary.BEBOP), null, List.of(MoodVocabulary.MELANCHOLIC), null, null, null, null, null
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

    private static MatchedDimension match(MoodVocabulary mood) {
        return new MatchedDimension(VocabularyDimension.MOOD, mood.name());
    }

    private void stubAllFindersEmpty() {
        when(graphService.findAlbumCandidates(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(graphService.findTrackCandidates(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(graphService.findArtistCandidates(any(), any(), any(), anyInt())).thenReturn(List.of());
    }
}
