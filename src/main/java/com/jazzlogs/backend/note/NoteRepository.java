package com.jazzlogs.backend.note;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    List<Note> findByTrackIdOrderByCreatedAtAsc(UUID trackId);

    // Explicit JPQL, not a derived findByTrackIdAndUserId... — Note also has a
    // convenience getUserId() (delegating to user.getId()), and Spring Data's
    // property-path resolver picks that plain method up as if "userId" were
    // its own mapped attribute, generating invalid JPQL ("Could not resolve
    // attribute 'userId' of Note") instead of drilling into the user
    // association. Spelling out user.id sidesteps the ambiguity entirely.
    @Query("SELECT n FROM Note n WHERE n.track.id = :trackId AND n.user.id = :userId ORDER BY n.createdAt ASC")
    List<Note> findByTrackIdAndUserIdOrderByCreatedAtAsc(@Param("trackId") UUID trackId, @Param("userId") UUID userId);

    // For AlbumService.getAlbumDetail — one query for every note the current
    // user left on any track of this album, instead of one per track.
    @Query("SELECT n FROM Note n WHERE n.user.id = :userId AND n.track.album.id = :albumId")
    List<Note> findByUserAndAlbum(@Param("userId") UUID userId, @Param("albumId") UUID albumId);
}
