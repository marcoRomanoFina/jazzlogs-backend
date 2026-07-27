package com.jazzlogs.backend.playlist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.syncfailure.SyncFailureRepository;
import com.jazzlogs.backend.syncfailure.SyncFailureStatus;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

/**
 * Real GraphService here (not mocked, unlike PlaylistServiceTest) — no live
 * Neo4j in this test environment (bolt://localhost:7687 with a placeholder
 * password, see src/test/resources/application.properties), so every graph
 * call here genuinely fails and exercises the fire-and-forget path: the
 * calling method must still return normally, and the failure must land in
 * sync_failures for the retry worker to pick up later.
 */
@SpringBootTest
@Transactional
class PlaylistTrackSyncFailureTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);

    @Autowired
    private PlaylistService playlistService;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private SyncFailureRepository syncFailureRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Test
    void addTrack_succeedsAndRecordsSyncFailureWhenNeo4jUnavailable() {
        UUID playlistId = persistPlaylist();
        Track track = persistTrack(persistAlbum(persistArtist()));

        playlistService.addTrack(playlistId, track.getId(), null, null);

        awaitSyncFailure(SyncFailureEntityType.PLAYLIST_TRACK_ADDED);
    }

    @Test
    void removeTrack_succeedsAndRecordsSyncFailureWhenNeo4jUnavailable() {
        UUID playlistId = persistPlaylist();
        Track track = persistTrack(persistAlbum(persistArtist()));
        playlistService.addTrack(playlistId, track.getId(), null, null);

        playlistService.removeTrack(playlistId, track.getId());

        awaitSyncFailure(SyncFailureEntityType.PLAYLIST_TRACK_REMOVED);
    }

    @Test
    void reorderTracks_succeedsAndRecordsSyncFailureWhenNeo4jUnavailable() {
        UUID playlistId = persistPlaylist();
        Album album = persistAlbum(persistArtist());
        Track trackA = persistTrack(album);
        Track trackB = persistTrack(album);
        playlistService.addTrack(playlistId, trackA.getId(), null, null);
        playlistService.addTrack(playlistId, trackB.getId(), null, null);

        playlistService.reorderTracks(playlistId, List.of(trackB.getId(), trackA.getId()));

        awaitSyncFailure(SyncFailureEntityType.PLAYLIST_TRACKS_REORDERED);
    }

    /**
     * The Neo4j sync runs on a separate thread (Neo4jAsyncSyncExecutor), and
     * Neo4jSyncFailureRecorder.record commits in its own transaction — so the
     * row lands slightly after the calling method already returned. Polls
     * briefly instead of asserting immediately.
     */
    private void awaitSyncFailure(SyncFailureEntityType entityType) {
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            boolean found = syncFailureRepository.findByStatus(SyncFailureStatus.PENDING).stream()
                .anyMatch(failure -> failure.getEntityType() == entityType);
            if (found) {
                return;
            }
            sleep();
        }
        assertThat(syncFailureRepository.findByStatus(SyncFailureStatus.PENDING))
            .as("PENDING sync_failures row for %s within %s", entityType, POLL_TIMEOUT)
            .anyMatch(failure -> failure.getEntityType() == entityType);
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private UUID persistPlaylist() {
        Playlist playlist = playlistRepository.save(new Playlist(
            "test-slug-" + UUID.randomUUID(), "Test Playlist", null, null, null, null, true
        ));
        return playlist.getId();
    }

    private Artist persistArtist() {
        return artistRepository.save(new Artist("Test Artist", null, null, null));
    }

    private Album persistAlbum(Artist artist) {
        return albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, null,
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
    }

    private Track persistTrack(Album album) {
        return trackRepository.save(new Track(
            album, null, null, "Test Track", null, null, null, false,
            null, null, null, null, null, null
        ));
    }
}
