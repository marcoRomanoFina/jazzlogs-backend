package com.jazzlogs.backend.album;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.dto.AlbumHeaderDto;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.EditorialByline;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.track.dto.TrackDto;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as PlaylistServiceTest: these tests cover AlbumService's own
// Postgres logic (getAlbumHeader is pure metadata now, no Neo4j involved;
// getAlbumTracks still batches per-track Neo4j lookups) — see
// GraphFilterServiceTest for why real Cypher is never exercised directly in
// this codebase.
@SpringBootTest
@Transactional
class AlbumServiceTest {

    @Autowired
    private AlbumService albumService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EditorialService editorialService;

    @MockitoBean
    private GraphService graphService;

    @Test
    void getAlbumHeader_returnsMinimalMetadata() {
        Artist artist = artistRepository.save(new Artist("Header Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Header Test Album", 2021);

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId());

        assertThat(dto.id()).isEqualTo(album.getId());
        assertThat(dto.artistId()).isEqualTo(artist.getId());
        assertThat(dto.artistName()).isEqualTo("Header Test Artist");
        assertThat(dto.name()).isEqualTo("Header Test Album");
        assertThat(dto.releaseYear()).isEqualTo(2021);
        assertThat(dto.totalTracks()).isEqualTo(1);
        assertThat(dto.loggedTrackCount()).isZero();
    }

    @Test
    void getAlbumHeader_loggedTrackCount_reflectsOnlyTracksWithAnEditorial() {
        Artist artist = artistRepository.save(new Artist("Logged Count Artist", null, null, null));
        Album album = persistAlbum(artist, "Logged Count Album", 2022);
        Track loggedTrack = persistTrack(album, "Logged Track");
        persistTrack(album, "Unlogged Track");

        editorialService.upsertTrackEditorial(
            loggedTrack.getId(), new TrackEditorialRequest("Logged Track Editorial", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId());

        assertThat(dto.loggedTrackCount()).isEqualTo(1);
    }

    @Test
    void getAlbumHeader_rejectsUnknownAlbum() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> albumService.getAlbumHeader(UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAlbumTracks_ordersByTrackNumberFromGraphPlacements() {
        Artist artist = artistRepository.save(new Artist("Tracks Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Tracks Test Album", 2019);
        Track trackA = persistTrack(album, "Track A");
        Track trackB = persistTrack(album, "Track B");
        entityManager.flush();
        entityManager.clear();

        // Track B placed first despite being persisted second — asserts
        // getAlbumTracks sorts by trackNumber, not insertion order.
        when(graphService.getTrackPlacements(album.getId())).thenReturn(List.of(
            new TrackPlacement(trackA.getId(), 2),
            new TrackPlacement(trackB.getId(), 1)
        ));

        List<TrackDto> tracks = albumService.getAlbumTracks(album.getId(), UUID.randomUUID());

        assertThat(tracks).extracting(TrackDto::name).containsExactly("Track B", "Track A");
        assertThat(tracks).extracting(TrackDto::trackNumber).containsExactly(1, 2);
    }

    @Test
    void getAlbumTracks_rejectsUnknownAlbum() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> albumService.getAlbumTracks(UUID.randomUUID(), UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Album persistAlbum(Artist artist, String name, Integer releaseYear) {
        return albumRepository.save(new Album(
            artist, name, null, null, null, releaseYear, 1
        ));
    }

    private Track persistTrack(Album album, String name) {
        return trackRepository.save(new Track(
            album, null, name, null, null, null, null, null, null, null, null, null
        ));
    }
}
