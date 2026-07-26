package com.jazzlogs.backend.listen;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dual-write on purpose: Postgres (user_album_listens/user_track_listens) is the
 * primary source of truth — the Review gate and any chronological feed read from
 * here, always, so they work even with Neo4j down. Neo4j gets a best-effort
 * mirror (:User)-[:LISTENED]->(:Album|:Track) for the recommendation agent to
 * traverse; a failure there is logged and swallowed, never lets a listen fail.
 */
@Slf4j
@Service
@AllArgsConstructor
public class ListenService {

    private final UserAlbumListenRepository userAlbumListenRepository;
    private final UserTrackListenRepository userTrackListenRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final GraphService graphService;

    @Transactional
    public void markAlbumListened(UUID userId, UUID albumId) {
        getAlbumOrThrow(albumId);

        boolean isNew = userAlbumListenRepository.insertIfNotExists(userId, albumId);
        if (isNew) {
            syncAlbumListenedToGraph(userId, albumId);
        }
    }

    private void syncAlbumListenedToGraph(UUID userId, UUID albumId) {
        // Today: sync always, for every user — there's no subscription/plan model
        // yet. Once one exists, gate this with `if (!user.hasActiveSubscription()) return;`
        // as the first line here — everything else about this method stays the same.
        try {
            graphService.markAlbumListened(userId, albumId, Instant.now());
        } catch (Exception ex) {
            log.warn("Failed to sync listened to Neo4j for user {} album {}", userId, albumId, ex);
        }
    }

    @Transactional
    public void markTrackListened(UUID userId, UUID trackId) {
        getTrackOrThrow(trackId);

        boolean isNew = userTrackListenRepository.insertIfNotExists(userId, trackId);
        if (isNew) {
            syncTrackListenedToGraph(userId, trackId);
        }
    }

    private void syncTrackListenedToGraph(UUID userId, UUID trackId) {
        // Same not-yet-gated-by-subscription note as syncAlbumListenedToGraph.
        try {
            graphService.markTrackListened(userId, trackId, Instant.now());
        } catch (Exception ex) {
            log.warn("Failed to sync listened to Neo4j for user {} track {}", userId, trackId, ex);
        }
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
