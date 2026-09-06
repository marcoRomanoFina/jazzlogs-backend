package com.jazzlogs.backend.note;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.like.LikeableRepository;

public interface NoteRepository extends LikeableRepository<Note> {

    // Same atomic-UPDATE pattern as EditorialRepository — not read-modify-save.
    @Modifying
    @Query("UPDATE Note n SET n.likeCount = n.likeCount + 1 WHERE n.id = :id")
    void incrementLikeCount(@Param("id") UUID entityId);

    @Modifying
    @Query("UPDATE Note n SET n.likeCount = GREATEST(n.likeCount - 1, 0) WHERE n.id = :id")
    void decrementLikeCount(@Param("id") UUID entityId);

    @Query("SELECT n.likeCount FROM Note n WHERE n.id = :id")
    Optional<Integer> findLikeCount(@Param("id") UUID entityId);

    /**
     * A track's whole note feed, paginated — the caller's own notes (if any)
     * always lead, then everyone else's, oldest first.
     *
     * <p>Paged, and pinned to the front at the SQL level (before the
     * createdAt tiebreak) — a track can accumulate way more notes than fit
     * in memory/a browser tab, so this has to be LIMIT/OFFSET in the
     * database, not "fetch everything and sort client-side" (that was the
     * previous, unpaged findByTrackIdOrderByCreatedAtAsc).
     *
     * @param trackId       the track
     * @param currentUserId the viewer, for "mine first"
     * @param pageable      page request
     * @return the matching page
     */
    @Query("SELECT n FROM Note n WHERE n.track.id = :trackId "
        + "ORDER BY CASE WHEN n.user.id = :currentUserId THEN 0 ELSE 1 END, n.createdAt ASC")
    Page<Note> findByTrackIdOrderByMineFirst(
        @Param("trackId") UUID trackId,
        @Param("currentUserId") UUID currentUserId,
        Pageable pageable
    );

    /**
     * One user's own notes on one track, oldest first, paginated.
     *
     * <p>Explicit JPQL, not a derived findByTrackIdAndUserId... — Note also
     * has a convenience getUserId() (delegating to user.getId()), and Spring
     * Data's property-path resolver picks that plain method up as if
     * "userId" were its own mapped attribute, generating invalid JPQL
     * ("Could not resolve attribute 'userId' of Note") instead of drilling
     * into the user association. Spelling out user.id sidesteps the
     * ambiguity entirely.
     *
     * @param trackId  the track
     * @param userId   the author
     * @param pageable page request
     * @return that user's notes on that track, oldest first
     */
    @Query("SELECT n FROM Note n WHERE n.track.id = :trackId AND n.user.id = :userId ORDER BY n.createdAt ASC")
    Page<Note> findByTrackIdAndUserIdOrderByCreatedAtAsc(
        @Param("trackId") UUID trackId, @Param("userId") UUID userId, Pageable pageable
    );

    // For NoteService.getNotesByAuthorsForAlbum (used by ReviewService) — one
    // query for every note behind a whole batch of reviews (a full album
    // review list, not one query per review). JOIN FETCH n.track is harmless
    // over-fetching here, not load-bearing — NoteDto only ever reads
    // note.getTrack().getId(), which the lazy proxy already knows from its
    // own FK column without a query.
    @Query("SELECT n FROM Note n JOIN FETCH n.track WHERE n.track.album.id = :albumId AND n.user.id IN :userIds")
    List<Note> findByAlbumIdAndUserIdIn(@Param("albumId") UUID albumId, @Param("userIds") Collection<UUID> userIds);
}
