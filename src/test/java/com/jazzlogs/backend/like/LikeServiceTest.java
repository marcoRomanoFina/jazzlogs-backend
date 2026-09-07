package com.jazzlogs.backend.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.note.Note;
import com.jazzlogs.backend.note.NoteRepository;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as PlaylistServiceTest/ReviewServiceTest: these tests cover
// LikeService's own Postgres logic, not Neo4j behavior.
@SpringBootTest
@Transactional
class LikeServiceTest {

    @Autowired
    private LikeService likeService;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @MockitoBean
    private GraphService graphService;

    @Test
    void addLike_createsLikeAndIncrementsCount() {
        User user = persistUser();
        Note note = persistNote();

        boolean created = likeService.addLike(user.getId(), LikeableEntityType.NOTE, note.getId());

        assertThat(created).isTrue();
        assertThat(likeService.countLikes(LikeableEntityType.NOTE, note.getId())).isEqualTo(1);
        assertThat(likeService.hasUserLiked(user.getId(), LikeableEntityType.NOTE, note.getId())).isTrue();
    }

    // Guards the flush-based fix in LikeService.addLike — before it, the
    // duplicate-insert race between existsById() and save() was never
    // actually exercised by the happy path, only a genuinely concurrent
    // request would hit it. This at least locks down that a same-thread
    // repeat call still behaves (existsById() short-circuits before ever
    // reaching save()).
    @Test
    void addLike_isIdempotent() {
        User user = persistUser();
        Note note = persistNote();

        likeService.addLike(user.getId(), LikeableEntityType.NOTE, note.getId());
        boolean createdAgain = likeService.addLike(user.getId(), LikeableEntityType.NOTE, note.getId());

        assertThat(createdAgain).isFalse();
        assertThat(likeService.countLikes(LikeableEntityType.NOTE, note.getId())).isEqualTo(1);
    }

    @Test
    void addLike_rejectsMissingEntity() {
        User user = persistUser();

        assertThatThrownBy(() -> likeService.addLike(user.getId(), LikeableEntityType.NOTE, UUID.randomUUID()))
            .isInstanceOf(ResponseStatusException.class);
    }

    private User persistUser() {
        return userRepository.save(new User(UUID.randomUUID(), "liker-" + UUID.randomUUID() + "@example.com"));
    }

    private Note persistNote() {
        Artist artist = artistRepository.save(new Artist("Like Test Artist " + UUID.randomUUID(), null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Like Test Album " + UUID.randomUUID(), null, null, null, 2024, 1,
            "LOG-" + UUID.randomUUID(), "LABEL", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        Track track = trackRepository.save(new Track(
            album, null, "Like Test Track", null, null, null, false, null, null, null, null, null, null
        ));
        return noteRepository.save(new Note(persistUser(), track, "Title", "Text", null));
    }
}
