package com.jazzlogs.backend.tracksearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlockType;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.BlockRequest;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.embedding.EmbeddingService;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TaggedTrack;
import com.jazzlogs.backend.graph.VocabularyDimension;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;

// The graph and the embedding model are mocked — which tracks are tagged how
// and what a phrase "means" are theirs to decide, and are set up by hand here.
// Everything on the Postgres side is real: the passage ranking and the track
// cards are native SQL that only a real database can vouch for.
//
// Meaning is faked with two orthogonal directions: a block whose text says
// "nocturne" points one way, anything else the other, and the search phrase
// points the "nocturne" way — so a nocturne block scores 1.0 and the rest 0.0.
@SpringBootTest
@Transactional
class TrackSearchServiceTest {

    private static final int DIMENSIONS = 1536;
    private static final String PHRASE = "something for the small hours";

    @Autowired
    private TrackSearchService trackSearchService;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EditorialService editorialService;

    @Autowired
    private ListenService listenService;

    @MockitoBean
    private GraphService graphService;

    @MockitoBean
    private EmbeddingService embeddingService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(new User(UUID.randomUUID(), "searcher-" + UUID.randomUUID() + "@example.com")).getId();
        when(embeddingService.embedBatch(anyList())).thenAnswer(call ->
            call.<List<String>>getArgument(0).stream().map(text -> direction(text.contains("nocturne") ? 0 : 1)).toList());
        when(embeddingService.embed(PHRASE)).thenReturn(direction(0));
    }

    @Test
    void nothingToSearchBy_isRejected() {
        TrackSearchCriteria empty = new TrackSearchCriteria(null, null, null, null, null, null, null, "   ");

        assertThatThrownBy(() -> trackSearchService.search(empty, userId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tagsOnly_keepsTheGraphsOrder_andNeverEmbedsAnything() {
        Track first = persistTrackWithLog("Tagged First", JazzlogsCharacter.LAURA, "plain");
        Track second = persistTrackWithLog("Tagged Second", JazzlogsCharacter.MARK, "plain");
        Map<VocabularyDimension, List<String>> relaxed = Map.of(VocabularyDimension.MOOD, List.of("RELAXED"));
        when(graphService.findTracksByTags(any(), eq(null), eq(null), anyInt()))
            .thenReturn(List.of(new TaggedTrack(first.getId(), relaxed), new TaggedTrack(second.getId(), relaxed)));

        List<TrackCandidate> result = trackSearchService.search(moods(null, MoodVocabulary.RELAXED), userId);

        assertThat(result).extracting(TrackCandidate::entityName).containsExactly("Tagged First", "Tagged Second");
        assertThat(result.get(0).matchedTags()).isEqualTo(relaxed);
        assertThat(result.get(0).closestPassage()).isNull();
        assertThat(result.get(0).writtenBy()).isEqualTo(JazzlogsCharacter.LAURA);
        assertThat(result.get(0).artistFullName()).isEqualTo("Search Lead, Search Co-Lead");
        assertThat(result.get(0).album()).isEqualTo("Search Album");
        verify(embeddingService, never()).embed(any());
    }

    @Test
    void tagsAndPhrase_rankTheTaggedPoolByMeaning() {
        Track plain = persistTrackWithLog("Tagged Plain", JazzlogsCharacter.MARK, "plain");
        Track nocturne = persistTrackWithLog("Tagged Nocturne", JazzlogsCharacter.MARK, "a nocturne");
        Track outsideThePool = persistTrackWithLog("Untagged Nocturne", JazzlogsCharacter.MARK, "a nocturne");
        Map<VocabularyDimension, List<String>> relaxed = Map.of(VocabularyDimension.MOOD, List.of("RELAXED"));
        // The graph puts the plain one first; meaning should overturn that.
        when(graphService.findTracksByTags(any(), eq(null), eq(null), anyInt()))
            .thenReturn(List.of(new TaggedTrack(plain.getId(), relaxed), new TaggedTrack(nocturne.getId(), relaxed)));

        List<TrackCandidate> result = trackSearchService.search(moods(PHRASE, MoodVocabulary.RELAXED), userId);

        assertThat(result).extracting(TrackCandidate::entityName).containsExactly("Tagged Nocturne", "Tagged Plain");
        assertThat(result).extracting(TrackCandidate::entityId).doesNotContain(outsideThePool.getId());
        assertThat(result.get(0).closestPassage().text()).isEqualTo("a nocturne");
        assertThat(result.get(0).closestPassage().category()).isEqualTo(BlockContentCategory.MOOD_AND_ATMOSPHERE);
        assertThat(result.get(0).closestPassage().similarity()).isEqualTo(1.0);
        assertThat(result.get(0).matchedTags()).isEqualTo(relaxed);
    }

    @Test
    void phraseOnly_ranksTheWholeCatalog_withoutAskingTheGraph() {
        Track nocturne = persistTrackWithLog("Catalog Nocturne", JazzlogsCharacter.ALICE, "a nocturne");

        List<TrackCandidate> result = trackSearchService.search(moods(PHRASE), userId);

        // Real logs live in this database too, but none of them points exactly the phrase's way.
        assertThat(result.get(0).entityId()).isEqualTo(nocturne.getId());
        assertThat(result.get(0).matchedTags()).isEmpty();
        assertThat(result).hasSizeLessThanOrEqualTo(TrackSearchService.MAX_CANDIDATES);
        verify(graphService, never()).findTracksByTags(any(), any(), any(), anyInt());
    }

    @Test
    void aLogMatchingInSeveralBlocks_isStillOneCandidate() {
        Track track = persistTrack("Two Nocturnes");
        editorialService.upsertTrackEditorial(track.getId(), new TrackEditorialRequest(
            "Two Nocturnes Log", "7", "dek", JazzlogsCharacter.BOB,
            List.of(
                new BlockRequest(EditorialBlockType.LEAD, null, "first nocturne", BlockContentCategory.HOOK),
                new BlockRequest(EditorialBlockType.PARA, null, "second nocturne", BlockContentCategory.CONTEXT)
            )
        ));

        List<TrackCandidate> result = trackSearchService.search(moods(PHRASE), userId);

        assertThat(result).extracting(TrackCandidate::entityId).containsOnlyOnce(track.getId());
    }

    @Test
    void quoteBlocks_areNeverWhatATrackMatchesOn() {
        Track track = persistTrack("Quoted Nocturne");
        editorialService.upsertTrackEditorial(track.getId(), new TrackEditorialRequest(
            "Quoted Nocturne Log", "7", "dek", JazzlogsCharacter.BOB,
            List.of(
                new BlockRequest(EditorialBlockType.QUOTE, null, "a nocturne, said someone", BlockContentCategory.MOOD_AND_ATMOSPHERE),
                new BlockRequest(EditorialBlockType.PARA, null, "plain", BlockContentCategory.CONTEXT)
            )
        ));
        when(graphService.findTracksByTags(any(), eq(null), eq(null), anyInt()))
            .thenReturn(List.of(new TaggedTrack(track.getId(), Map.of())));

        List<TrackCandidate> result = trackSearchService.search(moods(PHRASE, MoodVocabulary.RELAXED), userId);

        // The quote is the block that points the phrase's way, and it is ignored: the plain paragraph is all that is left.
        assertThat(result.get(0).closestPassage().text()).isEqualTo("plain");
        assertThat(result.get(0).closestPassage().similarity()).isEqualTo(0.0);
    }

    @Test
    void scopeOnly_returnsTheScopesTracks() {
        Track track = persistTrackWithLog("Scoped Track", JazzlogsCharacter.MARK, "plain");
        UUID albumId = track.getAlbum().getId();
        when(graphService.findTracksByTags(any(), eq(albumId), eq(null), anyInt()))
            .thenReturn(List.of(new TaggedTrack(track.getId(), Map.of())));

        List<TrackCandidate> result = trackSearchService.search(
            new TrackSearchCriteria(null, null, null, null, null, albumId, null, null), userId
        );

        assertThat(result).extracting(TrackCandidate::entityName).containsExactly("Scoped Track");
    }

    @Test
    void marksWhatTheUserAlreadyListenedTo_withoutHidingIt() {
        Track heard = persistTrackWithLog("Already Heard", JazzlogsCharacter.MARK, "plain");
        Track fresh = persistTrackWithLog("Never Heard", JazzlogsCharacter.MARK, "plain");
        listenService.markTrackListened(userId, heard.getId());
        when(graphService.findTracksByTags(any(), eq(null), eq(null), anyInt()))
            .thenReturn(List.of(new TaggedTrack(heard.getId(), Map.of()), new TaggedTrack(fresh.getId(), Map.of())));

        List<TrackCandidate> result = trackSearchService.search(moods(null, MoodVocabulary.RELAXED), userId);

        assertThat(result).extracting(TrackCandidate::alreadyListened).containsExactly(true, false);
    }

    @Test
    void aTrackTheGraphKnowsButTheCatalogDoesNot_isLeftOut() {
        Track real = persistTrackWithLog("Still Here", JazzlogsCharacter.MARK, "plain");
        when(graphService.findTracksByTags(any(), eq(null), eq(null), anyInt()))
            .thenReturn(List.of(new TaggedTrack(UUID.randomUUID(), Map.of()), new TaggedTrack(real.getId(), Map.of())));

        List<TrackCandidate> result = trackSearchService.search(moods(null, MoodVocabulary.RELAXED), userId);

        assertThat(result).extracting(TrackCandidate::entityName).containsExactly("Still Here");
    }

    private static TrackSearchCriteria moods(String lookingFor, MoodVocabulary... moods) {
        return new TrackSearchCriteria(null, List.of(moods), null, null, null, null, null, lookingFor);
    }

    /** A unit vector along one axis — two different axes are as unrelated as two meanings can be. */
    private static float[] direction(int axis) {
        float[] vector = new float[DIMENSIONS];
        vector[axis] = 1f;
        return vector;
    }

    private Track persistTrackWithLog(String name, JazzlogsCharacter writtenBy, String blockText) {
        Track track = persistTrack(name);
        editorialService.upsertTrackEditorial(track.getId(), new TrackEditorialRequest(
            name + " Log", "7", "dek", writtenBy,
            List.of(new BlockRequest(EditorialBlockType.PARA, null, blockText, BlockContentCategory.MOOD_AND_ATMOSPHERE))
        ));
        return track;
    }

    private Track persistTrack(String name) {
        Artist lead = artistRepository.save(new Artist("Search Lead", null, null, null));
        Artist coLead = artistRepository.save(new Artist("Search Co-Lead", null, null, null));
        Album album = albumRepository.save(new Album(List.of(lead, coLead), "Search Album", null, null, null, 1961, 1));
        return trackRepository.save(new Track(album, null, name, null, null, null, null, null, null, null, null, null));
    }
}
