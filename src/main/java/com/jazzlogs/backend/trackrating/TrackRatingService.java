package com.jazzlogs.backend.trackrating;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.listen.ListenService;
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

    private final TrackRatingRepository trackRatingRepository;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;
    private final ListenService listenService;
    private final GraphService graphService;
    private final Neo4jAsyncSyncExecutor syncExecutor;

    @Transactional
    public TrackRatingDto upsertRating(UUID userId, UUID trackId, BigDecimal rating) {
        assertValidRating(rating);

        User user = getUserOrThrow(userId);
        Track track = getTrackOrThrow(trackId);

        if (!listenService.hasListenedToTrack(userId, trackId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Debés escuchar el track antes de calificarlo");
        }

        TrackRating trackRating = trackRatingRepository.findByUserIdAndTrackId(userId, trackId)
            .orElseGet(() -> new TrackRating(user, track, rating));
        trackRating.update(rating);

        TrackRating saved = trackRatingRepository.save(trackRating);
        syncRatingToGraph(userId, trackId, saved.getRating(), saved.getUpdatedAt());

        return toDto(saved);
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
