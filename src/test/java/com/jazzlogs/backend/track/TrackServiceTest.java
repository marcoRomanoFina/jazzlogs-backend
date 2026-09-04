package com.jazzlogs.backend.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;

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
    private EntityManager entityManager;

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
    void setFeatured_isIdempotentForAlreadyFeaturedTrack() {
        Track track = persistTrack("Idempotent Featured Track");
        trackService.setFeatured(track.getId());

        trackService.setFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isTrue();
    }

    @Test
    void setFeatured_rejectsASeventhTrackOnceAtTheCap() {
        // Clears every real featured track first — this test needs a known
        // starting count (0), not whatever's actually curated live.
        entityManager.createQuery("UPDATE Track t SET t.featured = false").executeUpdate();

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

    private Track persistTrack(String name) {
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
