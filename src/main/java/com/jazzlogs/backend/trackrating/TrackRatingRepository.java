package com.jazzlogs.backend.trackrating;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackRatingRepository extends JpaRepository<TrackRating, UUID> {

    // Explicit JPQL, not a derived findByUserIdAndTrackId(In) — TrackRating
    // also has a convenience getUserId() (delegating to user.getId()), and
    // Spring Data's property-path resolver picks that plain method up as if
    // "userId" were its own mapped attribute, generating invalid JPQL
    // ("Could not resolve attribute 'userId' of TrackRating") instead of
    // drilling into the user association. Spelling out user.id sidesteps the
    // ambiguity entirely.
    @Query("SELECT tr FROM TrackRating tr WHERE tr.user.id = :userId AND tr.track.id = :trackId")
    Optional<TrackRating> findByUserIdAndTrackId(@Param("userId") UUID userId, @Param("trackId") UUID trackId);

    // One query for the current user's rating on every track of an album at
    // once — AlbumService.getAlbumDetail needs this batched, not one
    // findByUserIdAndTrackId per track.
    @Query("SELECT tr FROM TrackRating tr WHERE tr.user.id = :userId AND tr.track.id IN :trackIds")
    List<TrackRating> findByUserIdAndTrackIdIn(@Param("userId") UUID userId, @Param("trackIds") List<UUID> trackIds);

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
