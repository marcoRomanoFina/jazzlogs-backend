package com.jazzlogs.backend.review;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.listen.ListenService;
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
    private final ListenService listenService;
    private final LikeService likeService;
    private final GraphService graphService;
    private final Neo4jAsyncSyncExecutor syncExecutor;

    @Transactional
    public ReviewDto upsertReview(UUID userId, UUID albumId, BigDecimal rating, String text, List<UUID> standoutTrackIds) {
        assertValidRating(rating);

        User user = getUserOrThrow(userId);
        Album album = getAlbumOrThrow(albumId);

        if (!listenService.hasListenedToAlbum(userId, albumId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Debés escuchar el álbum antes de dejar una review");
        }

        Set<Track> standoutTracks = resolveStandoutTracks(albumId, standoutTrackIds);

        Review review = reviewRepository.findByUserIdAndAlbumId(userId, albumId)
            .orElseGet(() -> new Review(user, album, rating, text));

        review.update(rating, text);
        review.getStandoutTracks().clear();
        review.getStandoutTracks().addAll(standoutTracks);

        Review saved = reviewRepository.save(review);

        List<UUID> standoutTrackIdsForGraph = standoutTracks.stream().map(Track::getId).toList();
        syncRatingToGraph(userId, albumId, saved.getRating(), saved.getUpdatedAt());
        syncHighlightedTracksToGraph(userId, albumId, standoutTrackIdsForGraph);

        return toDto(saved, likeService.hasUserLiked(userId, LikeableEntityType.REVIEW, saved.getId()), user.getDisplayName());
    }

    /**
     * Fire-and-forget via Neo4jAsyncSyncExecutor, same contract as
     * ListenService's syncAlbumListenedToGraph: Postgres is already committed
     * by the time this runs, so a graph failure here is logged and swallowed,
     * never rolled back or surfaced to the caller.
     */
    private void syncRatingToGraph(UUID userId, UUID albumId, BigDecimal rating, Instant ratedAt) {
        syncExecutor.sync(
            SyncFailureEntityType.REVIEW_RATED,
            ratedPayload(userId, albumId, rating, ratedAt),
            () -> graphService.rateAlbum(userId, albumId, rating, ratedAt)
        );
    }

    /** Same fire-and-forget contract as syncRatingToGraph. */
    private void syncHighlightedTracksToGraph(UUID userId, UUID albumId, List<UUID> standoutTrackIds) {
        syncExecutor.sync(
            SyncFailureEntityType.REVIEW_HIGHLIGHTED,
            highlightedPayload(userId, albumId, standoutTrackIds),
            () -> graphService.setHighlightedTracks(userId, albumId, standoutTrackIds)
        );
    }

    // Values stored as canonical Strings — see SyncFailure's payload contract
    // and ReviewRatedSyncRetryHandler/ReviewHighlightedSyncRetryHandler.
    private Map<String, Object> ratedPayload(UUID userId, UUID albumId, BigDecimal rating, Instant ratedAt) {
        return Map.of(
            "userId", userId.toString(),
            "albumId", albumId.toString(),
            "rating", rating.toPlainString(),
            "ratedAt", ratedAt.toString()
        );
    }

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
        return reviews.stream().map(review -> toDto(review, liked.contains(review.getId()), names.get(review.getUserId()))).toList();
    }

    @Transactional(readOnly = true)
    public ReviewDto getMyReview(UUID albumId, UUID userId) {
        Review review = reviewRepository.findByUserIdAndAlbumId(userId, albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You haven't reviewed this album"));
        return toDto(review, likeService.hasUserLiked(userId, LikeableEntityType.REVIEW, review.getId()), getUserOrThrow(userId).getDisplayName());
    }

    private Map<UUID, String> namesByUserId(List<Review> reviews) {
        List<UUID> userIds = reviews.stream().map(Review::getUserId).distinct().toList();
        return userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, User::getDisplayName));
    }

    /** Every id must exist AND belong to this exact album — a track from another album can't be a standout here. */
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

    private ReviewDto toDto(Review review, boolean likedByCurrentUser, String userName) {
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
