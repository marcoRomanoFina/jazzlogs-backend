package com.jazzlogs.backend.editorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import com.jazzlogs.backend.editorial.dto.FeaturedTrackDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
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
