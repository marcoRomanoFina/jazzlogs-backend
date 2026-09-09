package com.jazzlogs.backend.album;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
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
import com.jazzlogs.backend.album.dto.CoverColorRequest;
import com.jazzlogs.backend.album.dto.LetterColorRequest;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.graph.AlbumHeaderGraphData;
import com.jazzlogs.backend.graph.AlbumPersonnelEntry;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.track.dto.TrackDto;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as PlaylistServiceTest: these tests cover AlbumService's own
// Postgres batching/assembly logic (the split between getAlbumHeader and
// getAlbumTracks), not Neo4j's actual Cypher — see GraphFilterServiceTest
// for why that's never exercised directly in this codebase.
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
    private ListenService listenService;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private GraphService graphService;

    @Test
    void getAlbumHeader_returnsAlbumFieldsAndGraphData() {
        Artist artist = artistRepository.save(new Artist("Header Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Header Test Album", 2021);

        AlbumHeaderGraphData graphData = new AlbumHeaderGraphData(
            List.of("Hard Bop"),
            List.of("Warm"),
            List.of("Morning Coffee"),
            List.of(new AlbumPersonnelEntry(UUID.randomUUID(), "Some Artist", "LEADER", List.of("PIANO")))
        );
        when(graphService.getAlbumHeaderGraphData(album.getId())).thenReturn(graphData);

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId(), UUID.randomUUID());

        assertThat(dto.id()).isEqualTo(album.getId());
        assertThat(dto.artistId()).isEqualTo(artist.getId());
        assertThat(dto.artistName()).isEqualTo("Header Test Artist");
        assertThat(dto.name()).isEqualTo("Header Test Album");
        assertThat(dto.releaseYear()).isEqualTo(2021);
        assertThat(dto.styles()).containsExactly("Hard Bop");
        assertThat(dto.moods()).containsExactly("Warm");
        assertThat(dto.contexts()).containsExactly("Morning Coffee");
        assertThat(dto.personnel()).hasSize(1);
        assertThat(dto.avgRating()).isNull();
        assertThat(dto.reviewCount()).isZero();
    }

    @Test
    void getAlbumHeader_rejectsUnknownAlbum() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> albumService.getAlbumHeader(UUID.randomUUID(), UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAlbumHeader_hasListenedTrue_whenEveryTrackIsListened() {
        Artist artist = artistRepository.save(new Artist("Listened Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Listened Test Album", 2020);
        Track track = persistTrack(album, "Only Track");
        // album.getTracks() is a lazy collection on the same managed Album
        // instance getAlbumHeader will re-fetch (same persistence context,
        // same identity) — without flushing, it lazy-loads before the new
        // Track row actually exists in Postgres and comes back empty.
        entityManager.flush();
        entityManager.clear();
        when(graphService.getAlbumHeaderGraphData(album.getId()))
            .thenReturn(new AlbumHeaderGraphData(List.of(), List.of(), List.of(), List.of()));

        UUID userId = UUID.randomUUID();
        listenService.markTrackListened(userId, track.getId());

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId(), userId);

        assertThat(dto.hasListened()).isTrue();
        assertThat(dto.listenedTrackCount()).isEqualTo(1);
    }

    @Test
    void getAlbumHeader_hasListenedFalse_whenNoTracksListened() {
        Artist artist = artistRepository.save(new Artist("Unlistened Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Unlistened Test Album", 2020);
        persistTrack(album, "Track A");
        entityManager.flush();
        entityManager.clear();
        when(graphService.getAlbumHeaderGraphData(album.getId()))
            .thenReturn(new AlbumHeaderGraphData(List.of(), List.of(), List.of(), List.of()));

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId(), UUID.randomUUID());

        assertThat(dto.hasListened()).isFalse();
        assertThat(dto.listenedTrackCount()).isZero();
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
    void setCoverColor_setsItAndSurfacesItOnTheHeader() {
        Artist artist = artistRepository.save(new Artist("Cover Color Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Cover Color Test Album", 2022);
        when(graphService.getAlbumHeaderGraphData(album.getId()))
            .thenReturn(new AlbumHeaderGraphData(List.of(), List.of(), List.of(), List.of()));

        albumService.setCoverColor(album.getId(), new CoverColorRequest("#a86b32"));

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId(), UUID.randomUUID());
        assertThat(dto.coverColor()).isEqualTo("#a86b32");
    }

    @Test
    void clearCoverColor_resetsItToNull() {
        Artist artist = artistRepository.save(new Artist("Clear Cover Color Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Clear Cover Color Test Album", 2022);
        when(graphService.getAlbumHeaderGraphData(album.getId()))
            .thenReturn(new AlbumHeaderGraphData(List.of(), List.of(), List.of(), List.of()));
        albumService.setCoverColor(album.getId(), new CoverColorRequest("#a86b32"));

        albumService.clearCoverColor(album.getId());

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId(), UUID.randomUUID());
        assertThat(dto.coverColor()).isNull();
    }

    @Test
    void setLetterColor_setsItAndSurfacesItOnTheHeader() {
        Artist artist = artistRepository.save(new Artist("Letter Color Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Letter Color Test Album", 2022);
        when(graphService.getAlbumHeaderGraphData(album.getId()))
            .thenReturn(new AlbumHeaderGraphData(List.of(), List.of(), List.of(), List.of()));

        albumService.setLetterColor(album.getId(), new LetterColorRequest("#a86b32"));

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId(), UUID.randomUUID());
        assertThat(dto.letterColor()).isEqualTo("#a86b32");
    }

    @Test
    void clearLetterColor_resetsItToNull() {
        Artist artist = artistRepository.save(new Artist("Clear Letter Color Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Clear Letter Color Test Album", 2022);
        when(graphService.getAlbumHeaderGraphData(album.getId()))
            .thenReturn(new AlbumHeaderGraphData(List.of(), List.of(), List.of(), List.of()));
        albumService.setLetterColor(album.getId(), new LetterColorRequest("#a86b32"));

        albumService.clearLetterColor(album.getId());

        AlbumHeaderDto dto = albumService.getAlbumHeader(album.getId(), UUID.randomUUID());
        assertThat(dto.letterColor()).isNull();
    }

    @Test
    void markEntryPoint_acceptsTheAlbumsOwnArtist() {
        Artist artist = artistRepository.save(new Artist("Entry Point Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Entry Point Test Album", 2018);

        albumService.markEntryPoint(album.getId(), artist.getId());

        verify(graphService).markAsEntryPoint(album.getId(), artist.getId());
    }

    @Test
    void markEntryPoint_rejectsAnArtistThatDoesNotOwnTheAlbum() {
        Artist owner = artistRepository.save(new Artist("Owner Test Artist", null, null, null));
        Artist someoneElse = artistRepository.save(new Artist("Someone Else Test Artist", null, null, null));
        Album album = persistAlbum(owner, "Mismatch Test Album", 2018);

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> albumService.markEntryPoint(album.getId(), someoneElse.getId())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void removePersonnel_removesLeaderEdge() {
        Artist artist = artistRepository.save(new Artist("Remove Leader Test Artist", null, null, null));
        Album album = persistAlbum(artist, "Remove Leader Test Album", 2018);

        albumService.removePersonnel(album.getId(), artist.getId(), PersonnelRole.LEADER);

        verify(graphService).removeAlbumLeader(artist.getId(), album.getId());
    }

    @Test
    void removePersonnel_removesSidemanEdge() {
        Artist owner = artistRepository.save(new Artist("Sideman Owner Test Artist", null, null, null));
        Artist sideman = artistRepository.save(new Artist("Remove Sideman Test Artist", null, null, null));
        Album album = persistAlbum(owner, "Remove Sideman Test Album", 2018);

        albumService.removePersonnel(album.getId(), sideman.getId(), PersonnelRole.SIDEMAN);

        verify(graphService).removeSideman(sideman.getId(), album.getId());
    }

    @Test
    void removePersonnel_rejectsUnknownAlbum() {
        Artist artist = artistRepository.save(new Artist("Unknown Album Test Artist", null, null, null));

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> albumService.removePersonnel(UUID.randomUUID(), artist.getId(), PersonnelRole.SIDEMAN)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
            artist, name, null, null, null, releaseYear, 1,
            "LOG-" + UUID.randomUUID(), "LABEL", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
    }

    private Track persistTrack(Album album, String name) {
        return trackRepository.save(new Track(
            album, null, name, null, null, null, false, null, null, null, null, null, null
        ));
    }
}
