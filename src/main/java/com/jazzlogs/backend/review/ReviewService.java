package com.jazzlogs.backend.review;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.note.NoteService;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.review.dto.AlbumRatingStats;
import com.jazzlogs.backend.review.dto.ReviewDto;
import com.jazzlogs.backend.review.dto.StandoutTrackDto;
import com.jazzlogs.backend.syncfailure.Neo4jAsyncSyncExecutor;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class ReviewService {

    private static final BigDecimal HALF_STEP = new BigDecimal("0.5");
    private static final BigDecimal MIN_RATING = BigDecimal.ONE;
    private static final BigDecimal MAX_RATING = new BigDecimal("5");

    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final NoteService noteService;
    private final LikeService likeService;
    private final GraphService graphService;
    private final Neo4jAsyncSyncExecutor syncExecutor;
    private final EntityManager entityManager;

    /**
     * Creates {@code userId}'s review of {@code albumId} — fails if one
     * already exists (see {@link #updateReview} to edit it instead). One
     * review per user per album, enforced by {@code uq_reviews_user_album}.
     *
     * @param userId           the reviewer
     * @param albumId          the album being reviewed
     * @param rating           1 to 5, in 0.5 steps
     * @param text             optional — a rating alone is a valid review
     * @param standoutTrackIds optional; every id must exist and belong to {@code albumId}
     * @return the created review
     * @throws ResponseStatusException 400 if {@code rating} is missing, out
     *                                  of range, or not a 0.5 step; 400 if
     *                                  any {@code standoutTrackIds} entry
     *                                  doesn't exist or belongs to a
     *                                  different album; 404 if the user or
     *                                  album don't exist; 409 if this user
     *                                  already has a review of this album
     */
    @Transactional
    public ReviewDto createReview(UUID userId, UUID albumId, BigDecimal rating, String text, List<UUID> standoutTrackIds) {
        assertValidRating(rating);
        User user = getUserOrThrow(userId);
        Album album = getAlbumOrThrow(albumId);
        Set<Track> standoutTracks = resolveStandoutTracks(albumId, standoutTrackIds);

        if (reviewRepository.findByUserIdAndAlbumId(userId, albumId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already reviewed this album — use PUT to edit it");
        }

        Review review = new Review(user, album, rating, text, standoutTracks);
        Review saved = saveNewReview(review);

        return finishUpsert(saved, userId, albumId, standoutTrackIds);
    }

    /**
     * Edits {@code userId}'s existing review of {@code albumId} — full
     * replace of rating/text/standout tracks, not a partial patch.
     *
     * @param userId           the reviewer
     * @param albumId          the album being reviewed
     * @param rating           1 to 5, in 0.5 steps
     * @param text             optional — a rating alone is a valid review
     * @param standoutTrackIds optional; every id must exist and belong to {@code albumId}; replaces the previous set entirely
     * @return the updated review
     * @throws ResponseStatusException 400 (same as {@link #createReview}); 404 if this user has no review of this album yet
     */
    @Transactional
    public ReviewDto updateReview(UUID userId, UUID albumId, BigDecimal rating, String text, List<UUID> standoutTrackIds) {
        assertValidRating(rating);
        Set<Track> standoutTracks = resolveStandoutTracks(albumId, standoutTrackIds);

        Review review = reviewRepository.findByUserIdAndAlbumId(userId, albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You haven't reviewed this album yet"));

        review.update(rating, text);
        review.getStandoutTracks().clear();
        review.getStandoutTracks().addAll(standoutTracks);
        Review saved = reviewRepository.save(review);

        return finishUpsert(saved, userId, albumId, standoutTrackIds);
    }

    /**
     * Flushes right after {@code save}, not left to commit time —
     * {@code uk_reviews_user_album} is what actually rejects a duplicate,
     * but {@code JpaRepository.save} on a new entity only schedules the
     * INSERT for flush time by default; without an explicit flush here, a
     * genuine race (two creates landing at once, both past the
     * pre-check above) would surface outside this method instead of being
     * caught as a clean 409. Same reasoning as
     * {@code EditorialService#saveWithUniqueTitle}.
     *
     * @throws ResponseStatusException 409 if this user already has a review of this album
     */
    private Review saveNewReview(Review review) {
        try {
            Review saved = reviewRepository.save(review);
            entityManager.flush();
            return saved;
        } catch (DataIntegrityViolationException | ConstraintViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already reviewed this album — use PUT to edit it", e);
        }
    }

    /**
     * The part {@link #createReview} and {@link #updateReview} share once
     * the row itself is saved: sync to Neo4j, fetch notes, project to the
     * response DTO.
     *
     * @param standoutTrackIds the caller's original input, not {@code
     *                         saved.getStandoutTracks()} — that's a lazy
     *                         collection and {@code saved} is detached by
     *                         the time this runs in some call paths
     * @return {@code saved} projected into the response shape
     */
    private ReviewDto finishUpsert(Review saved, UUID userId, UUID albumId, List<UUID> standoutTrackIds) {
        List<UUID> standoutTrackIdsForGraph = standoutTrackIds == null
            ? List.of()
            : List.copyOf(new HashSet<>(standoutTrackIds));
        syncRatingToGraph(userId, albumId, saved.getRating(), saved.getUpdatedAt());
        syncHighlightedTracksToGraph(userId, albumId, standoutTrackIdsForGraph);

        List<NoteDto> notes = notesFor(userId, albumId);
        boolean liked = likeService.hasUserLiked(userId, LikeableEntityType.REVIEW, saved.getId());
        return toDto(saved, liked, getUserOrThrow(userId).getResolvedDisplayName(), notes);
    }

    /**
     * Fire-and-forget via Neo4jAsyncSyncExecutor, same contract as
     * ListenService's syncAlbumListenedToGraph: Postgres is already committed
     * by the time this runs, so a graph failure here is logged and swallowed,
     * never rolled back or surfaced to the caller.
     *
     * @param ratedAt when the review was created/last updated
     */
    private void syncRatingToGraph(UUID userId, UUID albumId, BigDecimal rating, Instant ratedAt) {
        syncExecutor.sync(
            SyncFailureEntityType.REVIEW_RATED,
            ratedPayload(userId, albumId, rating, ratedAt),
            () -> graphService.rateAlbum(userId, albumId, rating, ratedAt)
        );
    }

    /**
     * Same fire-and-forget contract as {@link #syncRatingToGraph}.
     *
     * @param standoutTrackIds replaces the full HIGHLIGHTED set for this user/album — not additive
     */
    private void syncHighlightedTracksToGraph(UUID userId, UUID albumId, List<UUID> standoutTrackIds) {
        syncExecutor.sync(
            SyncFailureEntityType.REVIEW_HIGHLIGHTED,
            highlightedPayload(userId, albumId, standoutTrackIds),
            () -> graphService.setHighlightedTracks(userId, albumId, standoutTrackIds)
        );
    }

    /**
     * Values stored as canonical Strings — see SyncFailure's payload contract
     * and ReviewRatedSyncRetryHandler.
     *
     * @return the {@code SyncFailure} payload for a REVIEW_RATED retry
     */
    private Map<String, Object> ratedPayload(UUID userId, UUID albumId, BigDecimal rating, Instant ratedAt) {
        return Map.of(
            "userId", userId.toString(),
            "albumId", albumId.toString(),
            "rating", rating.toPlainString(),
            "ratedAt", ratedAt.toString()
        );
    }

    /** @return the {@code SyncFailure} payload for a REVIEW_HIGHLIGHTED retry — see {@link #ratedPayload} */
    private Map<String, Object> highlightedPayload(UUID userId, UUID albumId, List<UUID> trackIds) {
        return Map.of(
            "userId", userId.toString(),
            "albumId", albumId.toString(),
            "trackIds", trackIds.stream().map(UUID::toString).toList()
        );
    }

    /** Idempotent — does nothing if the user had no review on this album. */
    @Transactional
    public void deleteReview(UUID userId, UUID albumId) {
        reviewRepository.findByUserIdAndAlbumId(userId, albumId).ifPresent(reviewRepository::delete);
    }

    /**
     * Average rating and review count across every user's review of this album — not per-track.
     *
     * @param albumId the album to aggregate
     * @return the stats, {@code avgRating} is {@code null} if the album has no reviews
     */
    @Transactional(readOnly = true)
    public AlbumRatingStats getAlbumRatingStats(UUID albumId) {
        ReviewRepository.RatingStats stats = reviewRepository.getRatingStats(albumId);
        return new AlbumRatingStats(stats.getAvgRating(), stats.getCount());
    }

    @Transactional(readOnly = true)
    public List<ReviewDto> getAlbumReviews(UUID albumId, UUID currentUserId) {
        List<Review> reviews = reviewRepository.findByAlbumIdWithStandoutTracks(albumId);
        Set<UUID> liked = likedIds(reviews, currentUserId);
        Map<UUID, String> names = namesByUserId(reviews);
        Map<UUID, List<NoteDto>> notes = notesByUserId(reviews, albumId, currentUserId);
        return reviews.stream()
            .map(review -> toDto(
                review,
                liked.contains(review.getId()),
                names.get(review.getUserId()),
                notes.getOrDefault(review.getUserId(), List.of())
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public ReviewDto getMyReview(UUID albumId, UUID userId) {
        Review review = reviewRepository.findByUserIdAndAlbumId(userId, albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You haven't reviewed this album"));
        boolean liked = likeService.hasUserLiked(userId, LikeableEntityType.REVIEW, review.getId());
        return toDto(review, liked, getUserOrThrow(userId).getResolvedDisplayName(), notesFor(userId, albumId));
    }

    private Map<UUID, String> namesByUserId(List<Review> reviews) {
        List<UUID> userIds = reviews.stream().map(Review::getUserId).distinct().toList();
        return userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, User::getResolvedDisplayName));
    }

    /**
     * Every id must exist AND belong to this exact album — a track from
     * another album can't be a standout here.
     *
     * @param standoutTrackIds {@code null}/empty means no standout tracks
     * @return the resolved tracks, deduplicated
     * @throws ResponseStatusException 400 if any id doesn't exist or belongs to a different album
     */
    private Set<Track> resolveStandoutTracks(UUID albumId, List<UUID> standoutTrackIds) {
        if (standoutTrackIds == null || standoutTrackIds.isEmpty()) {
            return Set.of();
        }

        List<Track> tracks = trackRepository.findAllById(standoutTrackIds);
        if (tracks.size() != new HashSet<>(standoutTrackIds).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more standout track ids don't exist");
        }
        for (Track track : tracks) {
            if (!track.getAlbum().getId().equals(albumId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Track " + track.getId() + " does not belong to album " + albumId);
            }
        }
        return new HashSet<>(tracks);
    }

    /**
     * The DB has no CHECK constraint enforcing this — {@code rating} is only
     * {@code numeric(2,1) NOT NULL}, no range or step check — so this is the
     * only thing stopping a bad value from being saved.
     *
     * @throws ResponseStatusException 400 if {@code rating} is missing, out of [1, 5], or not a multiple of 0.5
     */
    private void assertValidRating(BigDecimal rating) {
        if (rating == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating is required");
        }
        if (rating.compareTo(MIN_RATING) < 0 || rating.compareTo(MAX_RATING) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating must be between 1 and 5");
        }
        if (rating.remainder(HALF_STEP).compareTo(BigDecimal.ZERO) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "rating must be a multiple of 0.5 (1, 1.5, 2, ... 5), got " + rating);
        }
    }

    private Set<UUID> likedIds(List<Review> reviews, UUID currentUserId) {
        List<UUID> reviewIds = reviews.stream().map(Review::getId).toList();
        return likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.REVIEW, reviewIds);
    }

    /**
     * Every note behind a whole album's review list, grouped by reviewer —
     * delegates to NoteService so the Note -> NoteDto mapping (and its
     * likedByCurrentUser/userName batching) lives in one place, not
     * duplicated here. likedByCurrentUser is against the viewer
     * (currentUserId), not each note's own author.
     */
    private Map<UUID, List<NoteDto>> notesByUserId(List<Review> reviews, UUID albumId, UUID currentUserId) {
        List<UUID> authorUserIds = reviews.stream().map(Review::getUserId).distinct().toList();
        return noteService.getNotesByAuthorsForAlbum(albumId, authorUserIds, currentUserId);
    }

    /**
     * The "my own review" case (create/update/getMyReview) — author and
     * viewer are always the same person here, unlike {@link #notesByUserId}
     * (a whole album's reviews, one fixed viewer browsing many authors).
     *
     * @return {@code userId}'s own notes on this album, empty if none
     */
    private List<NoteDto> notesFor(UUID userId, UUID albumId) {
        return noteService.getNotesByAuthorsForAlbum(albumId, List.of(userId), userId)
            .getOrDefault(userId, List.of());
    }

    /**
     * @param userName pre-resolved (batched for a list, looked up directly for a single review) — not read off {@code review} itself
     * @return {@code review} projected into the response shape
     */
    private ReviewDto toDto(Review review, boolean likedByCurrentUser, String userName, List<NoteDto> notes) {
        List<StandoutTrackDto> standoutTracks = review.getStandoutTracks().stream()
            .map(track -> new StandoutTrackDto(track.getId(), track.getName()))
            .toList();

        return new ReviewDto(
            review.getId(),
            review.getAlbum().getId(),
            review.getUserId(),
            userName,
            review.getRating(),
            review.getText(),
            review.getLikeCount(),
            likedByCurrentUser,
            standoutTracks,
            notes,
            review.getCreatedAt(),
            review.getUpdatedAt()
        );
    }

    private User getUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
    }

    private Album getAlbumOrThrow(UUID albumId) {
        return albumRepository.findById(albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found: " + albumId));
    }
}
