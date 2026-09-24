package com.jazzlogs.backend.editorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.editorial.dto.BlockRequest;
import com.jazzlogs.backend.editorial.dto.FeaturedTrackDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialCatalogueDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.embedding.EmbeddingService;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.storage.ImageStorageService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

@SpringBootTest
@Transactional
class EditorialServiceTest {

    @Autowired
    private EditorialService editorialService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TrackEditorialRepository trackEditorialRepository;

    @MockitoBean
    private GraphService graphService;

    @MockitoBean
    private ImageStorageService imageStorageService;

    @MockitoBean
    private EmbeddingService embeddingService;

    @Test
    void upsertTrackEditorial_persists() {
        Track track = persistTrack("Test Track");

        TrackEditorialRequest request = new TrackEditorialRequest("A Title", "A dek", EditorialByline.JAZZLOGS, List.of());

        TrackEditorial saved = editorialService.upsertTrackEditorial(track.getId(), request);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("A Title");
    }

    /** Unsigned pieces (byline omitted) default to the outlet itself. */
    @Test
    void upsertTrackEditorial_defaultsBylineToJazzlogsWhenOmitted() {
        Track track = persistTrack("Byline Default Track");

        TrackEditorial saved = editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("No Byline Title", "dek", null, List.of())
        );

        assertThat(saved.getByline()).isEqualTo(EditorialByline.JAZZLOGS);
    }

    @Test
    void upsertTrackEditorial_rejectsDuplicateTitleAcrossDifferentTracks() {
        Track trackA = persistTrack("Unique Title Track A");
        Track trackB = persistTrack("Unique Title Track B");
        editorialService.upsertTrackEditorial(
            trackA.getId(), new TrackEditorialRequest("Duplicate Title Test", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> editorialService.upsertTrackEditorial(
                trackB.getId(), new TrackEditorialRequest("Duplicate Title Test", "dek", EditorialByline.JAZZLOGS, List.of())
            )
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void upsertTrackEditorial_allowsReSavingWithItsOwnUnchangedTitle() {
        Track track = persistTrack("Resave Title Track");
        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Resave Same Title", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        TrackEditorial resaved = editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Resave Same Title", "new dek", EditorialByline.JAZZLOGS, List.of())
        );

        assertThat(resaved.getDek()).isEqualTo("new dek");
    }

    @Test
    void upsertTrackEditorial_embedsBlocksWithTrackAlbumArtistMetadata() {
        Album album = persistAlbum("Metadata Test Album");
        Track track = trackRepository.save(new Track(
            album, null, "Metadata Test Track", null, null, null, false, null, null, null, null, null, null
        ));
        when(embeddingService.embedBatch(List.of("Some editorial prose."))).thenReturn(List.of(new float[] {0.1f}));

        BlockRequest block = new BlockRequest(EditorialBlockType.PARA, null, "Some editorial prose.", BlockContentCategory.HISTORICAL_CONTEXT);
        TrackEditorial saved = editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Metadata Test Editorial", "dek", EditorialByline.JAZZLOGS, List.of(block))
        );

        Map<String, Object> metadata = saved.getBlocks().get(0).getEmbeddingMetadata();
        assertThat(metadata)
            .containsEntry("editorialType", "TrackEditorial")
            .containsEntry("editorialId", saved.getId().toString())
            .containsEntry("trackName", "Metadata Test Track")
            .containsEntry("albumName", "Metadata Test Album")
            .containsEntry("artistName", "Metadata Test Album Artist");
    }

    @Test
    void countEditorials_reflectsTrackEditorialRowCount() {
        long before = editorialService.countEditorials();

        editorialService.upsertTrackEditorial(
            persistTrack("Count Test Track").getId(),
            new TrackEditorialRequest("Count Test Editorial", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        assertThat(editorialService.countEditorials()).isEqualTo(before + 1);
    }

    @Test
    void listEditorials_matchesByEditorialTitleOrTrackName() {
        Track track = persistTrack("Searchable Track Name");
        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("A Wholly Different Title", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        Page<TrackEditorialCatalogueDto> byTitle = editorialService.listEditorials(
            "wholly different", PageRequest.of(0, 10), UUID.randomUUID()
        );
        Page<TrackEditorialCatalogueDto> byTrackName = editorialService.listEditorials(
            "searchable track", PageRequest.of(0, 10), UUID.randomUUID()
        );

        assertThat(byTitle.getContent()).extracting(TrackEditorialCatalogueDto::trackId).contains(track.getId());
        assertThat(byTrackName.getContent()).extracting(TrackEditorialCatalogueDto::trackId).contains(track.getId());
    }

    @Test
    void listEditorials_returnsTrackAlbumFieldsAndLikeState() {
        Album album = persistAlbum("Catalogue Test Album");
        Track track = trackRepository.save(new Track(
            album, null, "Catalogue Test Track", null, null, "http://img.example/track.jpg", false,
            null, null, null, null, null, null
        ));
        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Catalogue Test Editorial", "A dek", EditorialByline.JAZZLOGS, List.of())
        );

        Page<TrackEditorialCatalogueDto> page = editorialService.listEditorials(
            "Catalogue Test Editorial", PageRequest.of(0, 10), UUID.randomUUID()
        );

        assertThat(page.getContent()).hasSize(1);
        TrackEditorialCatalogueDto dto = page.getContent().get(0);
        assertThat(dto.trackId()).isEqualTo(track.getId());
        assertThat(dto.trackName()).isEqualTo("Catalogue Test Track");
        assertThat(dto.trackImageUrl()).isEqualTo("http://img.example/track.jpg");
        assertThat(dto.albumName()).isEqualTo("Catalogue Test Album");
        assertThat(dto.albumId()).isEqualTo(album.getId());
        assertThat(dto.dek()).isEqualTo("A dek");
        assertThat(dto.likedByCurrentUser()).isFalse();
    }

    @Test
    void getFeaturedTracks_returnsOnlyTracksMarkedFeatured() {
        // Clears any real featured track first — this test needs a known
        // starting set, not whatever's actually curated live.
        entityManager.createQuery("UPDATE Track t SET t.featured = false").executeUpdate();

        Album album = persistAlbum("Featured Track Album");
        Track featuredTrack = trackRepository.save(new Track(
            album, null, "Featured Track", null, null, "http://img.example/featured-track.jpg", false,
            null, null, null, null, null, null
        ));
        Track otherTrack = trackRepository.save(new Track(
            album, null, "Not Featured Track", null, null, null, false, null, null, null, null, null, null
        ));
        editorialService.upsertTrackEditorial(
            featuredTrack.getId(), new TrackEditorialRequest("Featured Track Editorial", "dek", EditorialByline.JAZZLOGS, List.of())
        );
        editorialService.upsertTrackEditorial(
            otherTrack.getId(), new TrackEditorialRequest("Not Featured Track Editorial", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        trackRepository.markFeatured(featuredTrack.getId());

        List<FeaturedTrackDto> featured = editorialService.getFeaturedTracks(UUID.randomUUID());

        assertThat(featured).extracting("title").containsExactly("Featured Track Editorial");
        FeaturedTrackDto dto = featured.get(0);
        assertThat(dto.trackName()).isEqualTo("Featured Track");
        assertThat(dto.imageUrl()).isEqualTo("http://img.example/featured-track.jpg");
        assertThat(dto.albumName()).isEqualTo("Featured Track Album");
        assertThat(dto.albumId()).isEqualTo(album.getId());
    }

    @Test
    void setTrackEditorialImage_uploadsUnderItsOwnKeyAndPersistsTheReturnedUrl() {
        Track track = persistTrack("Track Image Track");
        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Track Image Editorial", "dek", EditorialByline.JAZZLOGS, List.of())
        );
        MockMultipartFile file = new MockMultipartFile("file", "image.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageStorageService.upload("track-editorials/" + track.getId() + "/image", file))
            .thenReturn("http://localhost:9000/jazzlogs-images/track-editorials/" + track.getId() + "/image.jpg");

        editorialService.setTrackEditorialImage(track.getId(), file);

        TrackEditorial reloaded = trackEditorialRepository.findByTrackId(track.getId()).orElseThrow();
        assertThat(reloaded.getImageUrl()).isEqualTo("http://localhost:9000/jazzlogs-images/track-editorials/" + track.getId() + "/image.jpg");
    }

    @Test
    void setTrackEditorialImage_rejectsATrackWithNoEditorialYet() {
        Track track = persistTrack("No Editorial Track");
        MockMultipartFile file = new MockMultipartFile("file", "image.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> editorialService.setTrackEditorialImage(track.getId(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Album persistAlbum(String name) {
        Artist artist = artistRepository.save(new Artist(name + " Artist", null, null, null));
        return albumRepository.save(new Album(
            artist, name, null, null, null, 2024, 1
        ));
    }

    private Track persistTrack(String name) {
        Album album = persistAlbum(name + " Album");
        return trackRepository.save(new Track(
            album, null, name, null, null, null, false, null, null, null, null, null, null
        ));
    }
}
