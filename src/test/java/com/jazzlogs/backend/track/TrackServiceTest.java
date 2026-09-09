package com.jazzlogs.backend.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.spotify.SpotifyCatalogService;
import com.jazzlogs.backend.spotify.SpotifyTrackData;
import com.jazzlogs.backend.track.dto.CreateTrackRequest;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as AlbumServiceTest/ArtistServiceTest: markEntryPoint and
// createOrUpdateTrack are the tests below that actually reach it.
// SpotifyCatalogService is mocked too — createOrUpdateTrack calls out to the
// real Spotify API otherwise, which a unit test can't rely on.
@SpringBootTest
@Transactional
class TrackServiceTest {

    @Autowired
    private TrackService trackService;

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
    private SpotifyCatalogService spotifyCatalogService;

    // Real, currently-curated tracks can already be at (or near) the cap in
    // the shared dev DB this suite runs against — every test here needs a
    // known starting count (0), not whatever's actually featured live.
    @BeforeEach
    void clearRealFeaturedTracks() {
        entityManager.createQuery("UPDATE Track t SET t.featured = false").executeUpdate();
    }

    @Test
    void setFeatured_marksTrackAsFeatured() {
        Track track = persistTrack("Set Featured Track");

        trackService.setFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isTrue();
    }

    @Test
    void unsetFeatured_removesFeaturedFlag() {
        Track track = persistTrack("Unset Featured Track");
        trackService.setFeatured(track.getId());

        trackService.unsetFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void unsetFeatured_isNoOpWhenTrackWasNeverFeatured() {
        Track track = persistTrack("Never Featured Track");

        trackService.unsetFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void setFeatured_rejectsUnknownTrack() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> trackService.setFeatured(UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setFeatured_rejectsTrackWithNoEditorialYet() {
        Track track = persistTrackWithoutEditorial("No Editorial Track");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> trackService.setFeatured(track.getId())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void setFeatured_isIdempotentForAlreadyFeaturedTrack() {
        Track track = persistTrack("Idempotent Featured Track");
        trackService.setFeatured(track.getId());

        trackService.setFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isTrue();
    }

    @Test
    void setFeatured_rejectsASeventhTrackOnceAtTheCap() {
        for (int i = 0; i < TrackService.MAX_FEATURED_TRACKS; i++) {
            trackService.setFeatured(persistTrack("Cap Track " + i).getId());
        }
        Track seventh = persistTrack("Cap Track Overflow");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> trackService.setFeatured(seventh.getId())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(trackRepository.findById(seventh.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void markEntryPoint_acceptsTheTracksOwnArtist() {
        Artist artist = artistRepository.save(new Artist("Entry Point Test Artist", null, null, null));
        Track track = persistTrackFor(artist, "Entry Point Test Track");

        trackService.markEntryPoint(track.getId(), artist.getId());

        verify(graphService).markTrackAsEntryPoint(track.getId(), artist.getId());
    }

    @Test
    void markEntryPoint_rejectsAnArtistThatDoesNotOwnTheTrack() {
        Artist owner = artistRepository.save(new Artist("Owner Test Artist", null, null, null));
        Artist someoneElse = artistRepository.save(new Artist("Someone Else Test Artist", null, null, null));
        Track track = persistTrackFor(owner, "Mismatch Test Track");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> trackService.markEntryPoint(track.getId(), someoneElse.getId())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createOrUpdateTrack_assignsSequentialTrackNumberByUploadOrder_andBumpsTotalTracks() {
        Artist artist = artistRepository.save(new Artist("Upload Order Test Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Upload Order Test Album", null, null, null, 2024, 0,
            "LOG-" + UUID.randomUUID(), "LABEL", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        // Spotify's own trackNumber (99/1) is deliberately wrong/out of order here —
        // it must be ignored in favor of upload order.
        when(spotifyCatalogService.fetchTrack("spotify-track-first"))
            .thenReturn(new SpotifyTrackData("spotify-track-first", "First Track", 200000, null, 99, null));
        when(spotifyCatalogService.fetchTrack("spotify-track-second"))
            .thenReturn(new SpotifyTrackData("spotify-track-second", "Second Track", 200000, null, 1, null));
        when(graphService.getTrackPlacements(album.getId())).thenReturn(List.of(), List.of(new TrackPlacement(UUID.randomUUID(), 1)));

        Track first = trackService.createOrUpdateTrack(album.getId(), new CreateTrackRequest("spotify-track-first", false, null, null, null, null, null, null));
        Track second = trackService.createOrUpdateTrack(album.getId(), new CreateTrackRequest("spotify-track-second", false, null, null, null, null, null, null));

        verify(graphService).addTrackToAlbum(album.getId(), first.getId(), 1);
        verify(graphService).addTrackToAlbum(album.getId(), second.getId(), 2);
        assertThat(albumRepository.findById(album.getId()).orElseThrow().getTotalTracks()).isEqualTo(2);
    }

    @Test
    void createOrUpdateTrack_onUpdate_doesNotReassignTrackNumberOrTotalTracks() {
        Artist artist = artistRepository.save(new Artist("Update Test Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Update Test Album", null, null, null, 2024, 0,
            "LOG-" + UUID.randomUUID(), "LABEL", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        when(spotifyCatalogService.fetchTrack("spotify-track-update"))
            .thenReturn(new SpotifyTrackData("spotify-track-update", "Original Name", 200000, null, 1, null));
        when(graphService.getTrackPlacements(album.getId())).thenReturn(List.of());
        trackService.createOrUpdateTrack(album.getId(), new CreateTrackRequest("spotify-track-update", false, null, null, null, null, null, null));

        // Re-post the same track — a metadata refresh, not a new upload.
        when(spotifyCatalogService.fetchTrack("spotify-track-update"))
            .thenReturn(new SpotifyTrackData("spotify-track-update", "Renamed", 200000, null, 1, null));
        trackService.createOrUpdateTrack(album.getId(), new CreateTrackRequest("spotify-track-update", false, null, null, null, null, null, null));

        verify(graphService, times(1)).addTrackToAlbum(any(), any(), any(Integer.class));
        assertThat(albumRepository.findById(album.getId()).orElseThrow().getTotalTracks()).isEqualTo(1);
    }

    private Track persistTrackFor(Artist artist, String name) {
        Album album = albumRepository.save(new Album(
            artist, "Album for " + name, null, null, null, 2024, 1,
            "LOG-" + UUID.randomUUID(), "LABEL", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        return trackRepository.save(new Track(
            album, null, name, null, null, null, false, null, null, null, null, null, null
        ));
    }

    // setFeatured requires a TrackEditorial to already exist — every scenario
    // here needs one except the dedicated "rejects with no editorial" test.
    private Track persistTrack(String name) {
        Track track = persistTrackWithoutEditorial(name);
        editorialService.upsertTrackEditorial(track.getId(), new TrackEditorialRequest(name + " Editorial", "dek", "byline", List.of()));
        return track;
    }

    private Track persistTrackWithoutEditorial(String name) {
        Artist artist = artistRepository.save(new Artist("Featured Test Artist " + UUID.randomUUID(), null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Featured Test Album " + UUID.randomUUID(), null, null, null, 2024, 1,
            "LOG-" + UUID.randomUUID(), "LABEL", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        return trackRepository.save(new Track(
            album, null, name, null, null, null, false, null, null, null, null, null, null
        ));
    }
}
