package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.agent.tools.TrackLogReader.TrackLog;
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
import com.jazzlogs.backend.graph.TrackPerformerEntry;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

// GraphService and EmbeddingService are mocked, same as TrackServiceTest /
// EditorialServiceTest: Neo4j and OpenAI aren't what's under test, the
// Postgres side (track -> album -> artists, log -> blocks) is.
@SpringBootTest
@Transactional
class TrackLogReaderTest {

    @Autowired
    private TrackLogReader trackLogReader;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private EditorialService editorialService;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private GraphService graphService;

    @MockitoBean
    private EmbeddingService embeddingService;

    @BeforeEach
    void stubEmbeddings() {
        when(embeddingService.embedBatch(anyList()))
            .thenAnswer(call -> call.<List<String>>getArgument(0).stream().map(text -> new float[1536]).toList());
    }

    @Test
    void read_returnsTheTrackAndItsWholeLogInReadingOrder() {
        Track track = persistTrackWithLog();
        UUID trackId = track.getId();
        UUID pianistId = UUID.randomUUID();
        when(graphService.getTrackPlacement(trackId)).thenReturn(new TrackPlacement(trackId, 3));
        when(graphService.getTrackStyles(trackId)).thenReturn(List.of(new VocabularyTag("MODAL_JAZZ", "Modal Jazz")));
        when(graphService.getTrackPerformers(trackId))
            .thenReturn(List.of(new TrackPerformerEntry(pianistId, "Bill Evans", "SIDEMAN", List.of("PIANO"), false)));
        // A fresh persistence context, so album/artists/blocks really load lazily inside read()'s own transaction.
        entityManager.flush();
        entityManager.clear();

        TrackLog result = trackLogReader.read(trackId).orElseThrow();

        assertThat(result.track().entityId()).isEqualTo(trackId);
        assertThat(result.track().entityName()).isEqualTo("Reader Test Track");
        assertThat(result.track().artists()).containsExactly("Reader Lead Artist", "Reader Co-Lead Artist");
        assertThat(result.track().album()).isEqualTo("Reader Test Album");
        assertThat(result.track().albumReleaseYear()).isEqualTo(1961);
        assertThat(result.track().trackNumber()).isEqualTo(3);
        assertThat(result.track().duration()).isEqualTo("9:22");
        assertThat(result.track().styles()).containsExactly("MODAL_JAZZ");
        assertThat(result.track().performers()).extracting(TrackPerformerEntry::artistName).containsExactly("Bill Evans");

        assertThat(result.log().title()).isEqualTo("A Reader Test Log");
        assertThat(result.log().logNumber()).isEqualTo("42");
        assertThat(result.log().dek()).isEqualTo("The dek.");
        assertThat(result.log().writtenBy()).isEqualTo(JazzlogsCharacter.LAURA);
        assertThat(result.log().blocks()).extracting(TrackLogReader.Block::text).containsExactly("The hook.", "The context.");
        assertThat(result.log().blocks()).extracting(TrackLogReader.Block::contentCategory)
            .containsExactly(BlockContentCategory.HOOK, BlockContentCategory.CONTEXT);
    }

    @Test
    void read_isEmptyForAnIdThatIsNotATrack() {
        Track track = persistTrackWithLog();

        assertThat(trackLogReader.read(UUID.randomUUID())).isEmpty();
        // An album's id is a real catalog id, just not a track's — the case the agent can actually hit.
        assertThat(trackLogReader.read(track.getAlbum().getId())).isEmpty();
    }

    @Test
    void read_failsLoudlyForATrackWithNoLog() {
        Track track = persistTrack();

        assertThatThrownBy(() -> trackLogReader.read(track.getId())).isInstanceOf(IllegalStateException.class);
    }

    private Track persistTrackWithLog() {
        Track track = persistTrack();
        editorialService.upsertTrackEditorial(track.getId(), new TrackEditorialRequest(
            "A Reader Test Log", "42", "The dek.", JazzlogsCharacter.LAURA,
            List.of(
                new BlockRequest(EditorialBlockType.LEAD, null, "The hook.", BlockContentCategory.HOOK),
                new BlockRequest(EditorialBlockType.PARA, "Where it came from", "The context.", BlockContentCategory.CONTEXT)
            )
        ));
        return track;
    }

    private Track persistTrack() {
        Artist lead = artistRepository.save(new Artist("Reader Lead Artist", null, null, null));
        Artist coLead = artistRepository.save(new Artist("Reader Co-Lead Artist", null, null, null));
        Album album = albumRepository.save(new Album(List.of(lead, coLead), "Reader Test Album", null, null, null, 1961, 1));
        return trackRepository.save(new Track(album, null, "Reader Test Track", 562_000, null, null, null, null, null, null, null, null));
    }
}
