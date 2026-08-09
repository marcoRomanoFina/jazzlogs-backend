package com.jazzlogs.backend.listen;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.jazzlogs.backend.playlist.Playlist;
import com.jazzlogs.backend.playlist.PlaylistRepository;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

// No live Neo4j in this test environment (bolt://localhost:7687 with a
// placeholder password, see src/test/resources/application.properties) — these
// assert the fire-and-forget contract holds: a listen must land in Postgres
// regardless of whether the async Neo4j mirror succeeds. One polymorphic
// `listens` table backs every type (see ListenService's class javadoc), so a
// single ListenRepository check works for all of them.
@SpringBootTest
@Transactional
class ListenServiceTest {

    @Autowired
    private ListenService listenService;

    @Autowired
    private ListenRepository listenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Test
    void markAlbumListened_succeedsEvenWithNeo4jUnavailable() {
        User user = persistUser();
        Album album = persistAlbum(persistArtist());

        listenService.markAlbumListened(user.getId(), album.getId());

        assertThat(hasListened(user.getId(), ListenableEntityType.ALBUM, album.getId())).isTrue();
    }

    @Test
    void markTrackListened_succeedsEvenWithNeo4jUnavailable() {
        User user = persistUser();
        Track track = persistTrack(persistAlbum(persistArtist()));

        listenService.markTrackListened(user.getId(), track.getId());

        assertThat(hasListened(user.getId(), ListenableEntityType.TRACK, track.getId())).isTrue();
    }

    @Test
    void markPlaylistListened_succeedsEvenWithNeo4jUnavailableAndIsIdempotent() {
        User user = persistUser();
        Playlist playlist = persistPlaylist();

        listenService.markPlaylistListened(user.getId(), playlist.getId());
        listenService.markPlaylistListened(user.getId(), playlist.getId());

        assertThat(hasListened(user.getId(), ListenableEntityType.PLAYLIST, playlist.getId())).isTrue();
    }

    @Test
    void unmarkPlaylistListened_isIdempotent() {
        User user = persistUser();
        Playlist playlist = persistPlaylist();
        listenService.markPlaylistListened(user.getId(), playlist.getId());

        listenService.unmarkPlaylistListened(user.getId(), playlist.getId());
        listenService.unmarkPlaylistListened(user.getId(), playlist.getId());

        assertThat(hasListened(user.getId(), ListenableEntityType.PLAYLIST, playlist.getId())).isFalse();
    }

    private boolean hasListened(UUID userId, ListenableEntityType entityType, UUID entityId) {
        return listenRepository.existsById(new ListenId(userId, entityType, entityId));
    }

    // Saved directly via the repository, not PlaylistService.create — that also
    // does a synchronous Neo4j tag read for the returned detail DTO (see
    // PlaylistService.getPlaylistDetail), which this test doesn't care about
    // and which would require a live Neo4j to succeed.
    private Playlist persistPlaylist() {
        return playlistRepository.save(new Playlist(
            "test-slug-" + UUID.randomUUID(), "Test Playlist", null, null, null, null, true
        ));
    }

    private User persistUser() {
        return userRepository.save(new User(UUID.randomUUID(), "test-" + UUID.randomUUID() + "@example.com"));
    }

    private Artist persistArtist() {
        return artistRepository.save(new Artist("Test Artist", null, null, null));
    }

    private Album persistAlbum(Artist artist) {
        return albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, "LOG-1", "LABEL-1",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
    }

    private Track persistTrack(Album album) {
        Track track = trackRepository.save(new Track(
            album, null, "Test Track", null, null, null, false,
            null, null, null, null, null, null
        ));
        album.getTracks().add(track);
        return track;
    }
}
