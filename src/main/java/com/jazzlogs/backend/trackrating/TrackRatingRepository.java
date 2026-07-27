package com.jazzlogs.backend.trackrating;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackRatingRepository extends JpaRepository<TrackRating, UUID> {

    Optional<TrackRating> findByUserIdAndTrackId(UUID userId, UUID trackId);

    // One query for every track's stats at once — PlaylistService.getPlaylistDetail
    // needs this per track in the list, not one AVG/COUNT query per track.
    @Query("""
        SELECT tr.track.id AS trackId, AVG(tr.rating) AS avgRating, COUNT(tr) AS count
        FROM TrackRating tr
        WHERE tr.track.id IN :trackIds
        GROUP BY tr.track.id
        """)
    List<TrackRatingStats> getRatingStatsForTracks(@Param("trackIds") List<UUID> trackIds);

    interface TrackRatingStats {
        UUID getTrackId();
        BigDecimal getAvgRating();
        long getCount();
    }
}
