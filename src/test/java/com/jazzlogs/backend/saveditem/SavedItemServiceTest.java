package com.jazzlogs.backend.saveditem;

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
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.playlist.Playlist;
import com.jazzlogs.backend.playlist.PlaylistRepository;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

// @Transactional here rolls back every test's persisted fixtures automatically
// (Spring's TransactionalTestExecutionListener) — no manual cleanup needed.
@SpringBootTest
@Transactional
class SavedItemServiceTest {

    @Autowired
    private SavedItemService savedItemService;

    @Autowired
    private SavedItemRepository savedItemRepository;

    @Autowired
    private ListenService listenService;

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
    void save_isIdempotent_forPlaylist() {
        User user = persistUser();
        UUID playlistId = persistPlaylist();

        boolean firstSave = savedItemService.save(user.getId(), SaveableEntityType.PLAYLIST, playlistId);
        boolean secondSave = savedItemService.save(user.getId(), SaveableEntityType.PLAYLIST, playlistId);

        assertThat(firstSave).isTrue();
        assertThat(secondSave).isFalse();
        assertThat(exists(user.getId(), SaveableEntityType.PLAYLIST, playlistId)).isTrue();

        savedItemService.remove(user.getId(), SaveableEntityType.PLAYLIST, playlistId);
        savedItemService.remove(user.getId(), SaveableEntityType.PLAYLIST, playlistId);
        assertThat(exists(user.getId(), SaveableEntityType.PLAYLIST, playlistId)).isFalse();
    }

    @Test
    void save_isIdempotent() {
        User user = persistUser();
        Album album = persistAlbum(persistArtist());

        boolean firstSave = savedItemService.save(user.getId(), SaveableEntityType.ALBUM, album.getId());
        boolean secondSave = savedItemService.save(user.getId(), SaveableEntityType.ALBUM, album.getId());

        assertThat(firstSave).isTrue();
        assertThat(secondSave).isFalse();
        assertThat(exists(user.getId(), SaveableEntityType.ALBUM, album.getId())).isTrue();
    }

    @Test
    void remove_isIdempotent() {
        User user = persistUser();
        Album album = persistAlbum(persistArtist());
        savedItemService.save(user.getId(), SaveableEntityType.ALBUM, album.getId());

        savedItemService.remove(user.getId(), SaveableEntityType.ALBUM, album.getId());
        savedItemService.remove(user.getId(), SaveableEntityType.ALBUM, album.getId());

        assertThat(exists(user.getId(), SaveableEntityType.ALBUM, album.getId())).isFalse();
    }

    @Test
    void listeningToSavedAlbum_removesSavedItem() {
        User user = persistUser();
        Album album = persistAlbum(persistArtist());
        savedItemService.save(user.getId(), SaveableEntityType.ALBUM, album.getId());

        listenService.markAlbumListened(user.getId(), album.getId());

        assertThat(exists(user.getId(), SaveableEntityType.ALBUM, album.getId())).isFalse();
    }

    @Test
    void listeningToSavedTrack_removesSavedItem() {
        User user = persistUser();
        Album album = persistAlbum(persistArtist());
        Track track = persistTrack(album);
        savedItemService.save(user.getId(), SaveableEntityType.TRACK, track.getId());

        listenService.markTrackListened(user.getId(), track.getId());

        assertThat(exists(user.getId(), SaveableEntityType.TRACK, track.getId())).isFalse();
    }

    /** Listening to the whole album also unsaves its individual saved tracks. */
    @Test
    void listeningToAlbum_removesSavedTracksToo() {
        User user = persistUser();
        Album album = persistAlbum(persistArtist());
        Track track = persistTrack(album);
        savedItemService.save(user.getId(), SaveableEntityType.TRACK, track.getId());

        listenService.markAlbumListened(user.getId(), album.getId());

        assertThat(exists(user.getId(), SaveableEntityType.TRACK, track.getId())).isFalse();
    }

    private boolean exists(UUID userId, SaveableEntityType entityType, UUID entityId) {
        return savedItemRepository.existsById(new SavedItemId(userId, entityType, entityId));
    }

    private User persistUser() {
        return userRepository.save(new User(UUID.randomUUID(), "test-" + UUID.randomUUID() + "@example.com"));
    }

    private Artist persistArtist() {
        return artistRepository.save(new Artist("Test Artist", null, null, null));
    }

    private Album persistAlbum(Artist artist) {
        return albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, "LOG-1",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
    }

    // Saved directly via the repository, not PlaylistService.create — that also
    // does a synchronous Neo4j tag read for the returned detail DTO, which this
    // test doesn't care about and which would require a live Neo4j to succeed.
    private UUID persistPlaylist() {
        Playlist playlist = playlistRepository.save(new Playlist(
            "test-slug-" + UUID.randomUUID(), "Test Playlist", null, null, null, null, true
        ));
        return playlist.getId();
    }

    // Keeps both sides of the bidirectional Album<->Track association in sync in
    // memory — album is already managed in this persistence context by the time
    // this runs, so ListenService.markAlbumListened's album.getTracks() loop
    // would otherwise still see the stale (empty) collection from construction.
    private Track persistTrack(Album album) {
        Track track = trackRepository.save(new Track(
            album, null, "Test Track", null, null, null, false,
            null, null, null, null, null, null
        ));
        album.getTracks().add(track);
        return track;
    }
}
