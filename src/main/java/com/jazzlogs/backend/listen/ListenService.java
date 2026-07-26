package com.jazzlogs.backend.listen;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.saveditem.SaveableEntityType;
import com.jazzlogs.backend.saveditem.SavedItemRepository;
import com.jazzlogs.backend.syncfailure.Neo4jAsyncSyncExecutor;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;

/**
 * Dual-write on purpose: Postgres (user_album_listens/user_track_listens) is the
 * primary source of truth — the Review gate and any chronological feed read from
 * here, always, so they work even with Neo4j down. Neo4j gets a best-effort
 * mirror (:User)-[:LISTENED]->(:Album|:Track) for the recommendation agent to
 * traverse, dispatched through Neo4jAsyncSyncExecutor so a slow/down Neo4j never
 * adds latency to this request thread; a failure there is logged and swallowed,
 * never lets a listen fail.
 *
 * Also auto-removes the matching saved_items row (if any) in the same
 * transaction as the Postgres listen insert — unlike the Neo4j sync, there's no
 * external system involved here, so a failure just rolls back normally.
 */
@Service
@AllArgsConstructor
public class ListenService {

    private final UserAlbumListenRepository userAlbumListenRepository;
    private final UserTrackListenRepository userTrackListenRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final SavedItemRepository savedItemRepository;
    private final GraphService graphService;
    private final Neo4jAsyncSyncExecutor syncExecutor;

    /**
     * Also marks every track on the album as listened — same idempotent,
     * per-track dual write as markTrackListened, just driven from here instead
     * of a separate call per track. Tracks already marked (or added to the
     * album after a previous listen) are handled the same way either way:
     * insertIfNotExists no-ops for the ones that already exist.
     */
    @Transactional
    public void markAlbumListened(UUID userId, UUID albumId) {
        Album album = getAlbumOrThrow(albumId);

        boolean isNew = userAlbumListenRepository.insertIfNotExists(userId, albumId);
        savedItemRepository.deleteByUserIdAndEntityTypeAndEntityId(userId, SaveableEntityType.ALBUM, albumId);
        if (isNew) {
            syncAlbumListenedToGraph(userId, albumId);
        }

        for (Track track : album.getTracks()) {
            // Track existence is already guaranteed here (it came straight off
            // the loaded Album), so skip straight to the core logic instead of
            // re-running markTrackListened's own getTrackOrThrow per track.
            markTrackListenedCore(userId, track.getId());
        }
    }

    private void syncAlbumListenedToGraph(UUID userId, UUID albumId) {
        // Today: sync always, for every user — there's no subscription/plan model
        // yet. Once one exists, gate this with `if (!user.hasActiveSubscription()) return;`
        // as the first line here — everything else about this method stays the same.
        Instant listenedAt = Instant.now();
        syncExecutor.sync(
            SyncFailureEntityType.LISTENED,
            listenedPayload("ALBUM", userId, albumId, listenedAt),
            () -> graphService.markAlbumListened(userId, albumId, listenedAt)
        );
    }

    @Transactional
    public void markTrackListened(UUID userId, UUID trackId) {
        getTrackOrThrow(trackId);
        markTrackListenedCore(userId, trackId);
    }

    private void markTrackListenedCore(UUID userId, UUID trackId) {
        boolean isNew = userTrackListenRepository.insertIfNotExists(userId, trackId);
        savedItemRepository.deleteByUserIdAndEntityTypeAndEntityId(userId, SaveableEntityType.TRACK, trackId);
        if (isNew) {
            syncTrackListenedToGraph(userId, trackId);
        }
    }

    private void syncTrackListenedToGraph(UUID userId, UUID trackId) {
        // Same not-yet-gated-by-subscription note as syncAlbumListenedToGraph.
        Instant listenedAt = Instant.now();
        syncExecutor.sync(
            SyncFailureEntityType.LISTENED,
            listenedPayload("TRACK", userId, trackId, listenedAt),
            () -> graphService.markTrackListened(userId, trackId, listenedAt)
        );
    }

    // targetType disambiguates ALBUM vs TRACK within the single LISTENED entity
    // type — see ListenedSyncRetryHandler. Values are stored as canonical
    // Strings (see SyncFailure's payload contract).
    private Map<String, Object> listenedPayload(String targetType, UUID userId, UUID targetId, Instant listenedAt) {
        return Map.of(
            "targetType", targetType,
            "userId", userId.toString(),
            "targetId", targetId.toString(),
            "listenedAt", listenedAt.toString()
        );
    }

    /**
     * The Review creation gate calls this — Postgres only, never Neo4j. That's
     * the whole point of the dual write: the gate must work even with the graph
     * database down.
     */
    @Transactional(readOnly = true)
    public boolean hasListenedToAlbum(UUID userId, UUID albumId) {
        return userAlbumListenRepository.existsById(new UserAlbumListenId(userId, albumId));
    }

    @Transactional(readOnly = true)
    public boolean hasListenedToTrack(UUID userId, UUID trackId) {
        return userTrackListenRepository.existsById(new UserTrackListenId(userId, trackId));
    }

    private Album getAlbumOrThrow(UUID albumId) {
        return albumRepository.findById(albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found: " + albumId));
    }

    private Track getTrackOrThrow(UUID trackId) {
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }
}
