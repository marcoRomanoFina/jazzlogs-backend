package com.jazzlogs.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.review.dto.ReviewDto;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as PlaylistServiceTest: these tests cover ReviewService's own
// Postgres validation/persistence logic, not Neo4j behavior.
@SpringBootTest
@Transactional
class ReviewServiceTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private GraphService graphService;

    @Test
    void createReview_createsNewReview() {
        User user = persistUser();
        Album album = persistAlbum();

        ReviewDto dto = reviewService.createReview(
            user.getId(), album.getId(), new BigDecimal("4.5"), "Great album", List.of()
        );

        assertThat(dto.albumId()).isEqualTo(album.getId());
        assertThat(dto.userId()).isEqualTo(user.getId());
        assertThat(dto.rating()).isEqualByComparingTo("4.5");
        assertThat(dto.text()).isEqualTo("Great album");
    }

    @Test
    void createReview_rejectsWhenAlreadyReviewed() {
        User user = persistUser();
        Album album = persistAlbum();
        reviewService.createReview(user.getId(), album.getId(), new BigDecimal("4"), "First", List.of());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> reviewService.createReview(user.getId(), album.getId(), new BigDecimal("3"), "Second", List.of())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createReview_rejectsInvalidRating() {
        User user = persistUser();
        Album album = persistAlbum();

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> reviewService.createReview(user.getId(), album.getId(), new BigDecimal("1.3"), null, List.of())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createReview_rejectsStandoutTrackFromADifferentAlbum() {
        User user = persistUser();
        Album album = persistAlbum();
        Album otherAlbum = persistAlbum();
        Track otherTrack = trackRepository.save(new Track(
            otherAlbum, null, "Other Album Track", null, null, null, false, null, null, null, null, null, null
        ));

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> reviewService.createReview(user.getId(), album.getId(), new BigDecimal("5"), null, List.of(otherTrack.getId()))
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateReview_updatesExistingReview() {
        User user = persistUser();
        Album album = persistAlbum();
        reviewService.createReview(user.getId(), album.getId(), new BigDecimal("3"), "Meh", List.of());

        ReviewDto updated = reviewService.updateReview(user.getId(), album.getId(), new BigDecimal("5"), "Actually great", List.of());

        assertThat(updated.rating()).isEqualByComparingTo("5");
        assertThat(updated.text()).isEqualTo("Actually great");
    }

    @Test
    void updateReview_rejectsWhenNoExistingReview() {
        User user = persistUser();
        Album album = persistAlbum();

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> reviewService.updateReview(user.getId(), album.getId(), new BigDecimal("5"), null, List.of())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAlbumReviews_putsCallersOwnReviewFirstRegardlessOfRecency() {
        Album album = persistAlbum();
        User me = persistUser();
        User other = persistUser();

        // Mine created first (older) — plain newest-first would put the
        // other one above it; mine-first should still win.
        reviewService.createReview(me.getId(), album.getId(), new BigDecimal("4"), "Mine", List.of());
        entityManager.flush();
        reviewService.createReview(other.getId(), album.getId(), new BigDecimal("3"), "Other, newer", List.of());

        Page<ReviewDto> page = reviewService.getAlbumReviews(album.getId(), me.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent().get(0).userId()).isEqualTo(me.getId());
    }

    @Test
    void getAlbumReviews_ordersOthersNewestFirst() {
        Album album = persistAlbum();
        User viewer = persistUser();
        User reviewerA = persistUser();
        User reviewerB = persistUser();

        reviewService.createReview(reviewerA.getId(), album.getId(), new BigDecimal("4"), "First", List.of());
        entityManager.flush();
        reviewService.createReview(reviewerB.getId(), album.getId(), new BigDecimal("3"), "Second", List.of());

        Page<ReviewDto> page = reviewService.getAlbumReviews(album.getId(), viewer.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ReviewDto::userId).containsExactly(reviewerB.getId(), reviewerA.getId());
    }

    @Test
    void getAlbumReviews_includesStandoutTracks() {
        Album album = persistAlbum();
        User user = persistUser();
        Track track = trackRepository.save(new Track(
            album, null, "Standout Track", null, null, null, false, null, null, null, null, null, null
        ));

        reviewService.createReview(user.getId(), album.getId(), new BigDecimal("5"), null, List.of(track.getId()));

        Page<ReviewDto> page = reviewService.getAlbumReviews(album.getId(), user.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent().get(0).standoutTracks()).extracting("name").containsExactly("Standout Track");
    }

    @Test
    void getAlbumReviews_respectsPageSize() {
        Album album = persistAlbum();
        User viewer = persistUser();
        for (int i = 0; i < 3; i++) {
            reviewService.createReview(persistUser().getId(), album.getId(), new BigDecimal("4"), "Review " + i, List.of());
        }

        Page<ReviewDto> page = reviewService.getAlbumReviews(album.getId(), viewer.getId(), PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    private User persistUser() {
        return userRepository.save(new User(UUID.randomUUID(), "reviewer-" + UUID.randomUUID() + "@example.com"));
    }

    private Album persistAlbum() {
        Artist artist = artistRepository.save(new Artist("Review Test Artist " + UUID.randomUUID(), null, null, null));
        return albumRepository.save(new Album(
            artist, "Review Test Album " + UUID.randomUUID(), null, null, null, 2024, 1,
            "LOG-" + UUID.randomUUID(), "LABEL", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
    }
}
