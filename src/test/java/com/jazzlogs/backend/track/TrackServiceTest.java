package com.jazzlogs.backend.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;

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

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as AlbumServiceTest/ArtistServiceTest: markEntryPoint is the one
// test below that actually reaches it.
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
