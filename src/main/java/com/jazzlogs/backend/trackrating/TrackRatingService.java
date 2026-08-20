package com.jazzlogs.backend.trackrating;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.syncfailure.Neo4jAsyncSyncExecutor;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.trackrating.dto.TrackRatingDto;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class TrackRatingService {

    private static final BigDecimal HALF_STEP = new BigDecimal("0.5");
    private static final BigDecimal MIN_RATING = BigDecimal.ONE;
    private static final BigDecimal MAX_RATING = new BigDecimal("5");

    // findByUserIdAndTrackId-then-save is a read-then-write race: two rapid
    // ratings of a track that's never been rated before (drag the stars,
    // change your mind, drag again before the first request lands) can both
    // read "no row yet" and both attempt an INSERT — the DB's unique
    // constraint (uq_track_ratings_user_track) lets only one through and
    // fails the other with DataIntegrityViolationException, which used to
    // surface as a raw 500 to whichever request lost the race. One retry is
    // provably enough: by the time the loser retries, the winner's row
    // definitely exists, so the retry finds it and does a plain UPDATE,
    // which can't itself violate the constraint. The extra attempt beyond
    // that is just cheap headroom, not a requirement.
    private static final int MAX_UPSERT_ATTEMPTS = 3;

    private final TrackRatingRepository trackRatingRepository;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;
    private final GraphService graphService;
    private final Neo4jAsyncSyncExecutor syncExecutor;
    private final PlatformTransactionManager transactionManager;

    public TrackRatingDto upsertRating(UUID userId, UUID trackId, BigDecimal rating) {
        assertValidRating(rating);

        TrackRating saved = upsertWithRetry(userId, trackId, rating);
        syncRatingToGraph(userId, trackId, saved.getRating(), saved.getUpdatedAt());

        return toDto(saved);
    }

    /**
     * Each attempt runs in its own fresh transaction — a transaction that
     * already threw is aborted and can't be reused for the retry's queries,
     * so this can't be a single @Transactional method with a try/catch
     * inside it. user/track are re-looked-up per attempt (cheap, by primary
     * key) rather than fetched once outside the loop, so nothing here is a
     * detached entity carried across transactions.
     */
    private TrackRating upsertWithRetry(UUID userId, UUID trackId, BigDecimal rating) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        DataIntegrityViolationException lastFailure = null;

        for (int attempt = 0; attempt < MAX_UPSERT_ATTEMPTS; attempt++) {
            try {
                return txTemplate.execute(status -> {
                    User user = getUserOrThrow(userId);
                    Track track = getTrackOrThrow(trackId);

                    TrackRating trackRating = trackRatingRepository.findByUserIdAndTrackId(userId, trackId)
                        .orElseGet(() -> new TrackRating(user, track, rating));
                    trackRating.update(rating);

                    return trackRatingRepository.save(trackRating);
                });
            } catch (DataIntegrityViolationException e) {
                lastFailure = e;
            }
        }
        throw lastFailure;
    }

    /**
     * Fire-and-forget via Neo4jAsyncSyncExecutor, same contract as
     * ReviewService.syncRatingToGraph: Postgres is already committed by the
     * time this runs, so a graph failure here is logged and swallowed, never
     * rolled back or surfaced to the caller.
     */
    private void syncRatingToGraph(UUID userId, UUID trackId, BigDecimal rating, Instant ratedAt) {
        syncExecutor.sync(
            SyncFailureEntityType.TRACK_RATED,
            ratedPayload(userId, trackId, rating, ratedAt),
            () -> graphService.rateTrack(userId, trackId, rating, ratedAt)
        );
    }

    // Values stored as canonical Strings — see SyncFailure's payload contract
    // and TrackRatedSyncRetryHandler.
    private Map<String, Object> ratedPayload(UUID userId, UUID trackId, BigDecimal rating, Instant ratedAt) {
        return Map.of(
            "userId", userId.toString(),
            "trackId", trackId.toString(),
            "rating", rating.toPlainString(),
            "ratedAt", ratedAt.toString()
        );
    }

    /**
     * Ahead of the DB's CHECK constraint on purpose — same rule, but a clear
     * 400 here beats a raw constraint-violation error from the insert.
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

    private TrackRatingDto toDto(TrackRating trackRating) {
        return new TrackRatingDto(
            trackRating.getId(),
            trackRating.getTrack().getId(),
            trackRating.getUserId(),
            trackRating.getRating(),
            trackRating.getCreatedAt(),
            trackRating.getUpdatedAt()
        );
    }

    private User getUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
    }

    private Track getTrackOrThrow(UUID trackId) {
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }
}
