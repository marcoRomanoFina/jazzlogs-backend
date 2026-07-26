package com.jazzlogs.backend.trackrating;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackRatingRepository extends JpaRepository<TrackRating, UUID> {

    Optional<TrackRating> findByUserIdAndTrackId(UUID userId, UUID trackId);
}
