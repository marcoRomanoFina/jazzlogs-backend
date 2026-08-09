package com.jazzlogs.backend.listen;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.playlist.Playlist;
import com.jazzlogs.backend.playlist.PlaylistRepository;
import com.jazzlogs.backend.saveditem.SaveableEntityType;
import com.jazzlogs.backend.saveditem.SavedItemRepository;
import com.jazzlogs.backend.series.SeriesChapter;
import com.jazzlogs.backend.series.SeriesChapterRepository;
import com.jazzlogs.backend.syncfailure.Neo4jAsyncSyncExecutor;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;

/**
 * One polymorphic table (listens: user_id, entity_type, entity_id) backs
 * every listenable type — same shape as Like/SavedItem, not a dedicated table
 * per type. Postgres is the primary source of truth — the Review creation
 * gate and any chronological feed read from here, always, so they work even
 * with Neo4j down. Neo4j gets a best-effort mirror (:User)-[:LISTENED]->
 * (:Album|:Track|:Playlist) for the recommendation agent to traverse,
 * dispatched through Neo4jAsyncSyncExecutor so a slow/down Neo4j never adds
 * latency to this request thread; a failure there is logged and swallowed,
 * never lets a listen fail. Series chapters are the exception — Postgres-only,
 * no Neo4j mirror at all (Series is out of the graph by design, see
 * SeriesService).
 *
 * Deliberately keeps one named public method per type (markAlbumListened,
 * markTrackListened, ...) instead of a single generic mark(entityType, id) —
 * each type has different side effects (Neo4j sync or not, saved_items
 * cleanup or not), and burying that behind one generic entry point would
 * hide, not simplify, those differences.
 *
 * Also auto-removes the matching saved_items row (if any) in the same
 * transaction as the Postgres listen insert — unlike the Neo4j sync, there's no
 * external system involved here, so a failure just rolls back normally.
 */
@Service
@AllArgsConstructor
public class ListenService {

    private final ListenRepository listenRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final PlaylistRepository playlistRepository;
    private final SeriesChapterRepository seriesChapterRepository;
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

        boolean isNew = listenRepository.insertIfNotExists(userId, ListenableEntityType.ALBUM, albumId);
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
        boolean isNew = listenRepository.insertIfNotExists(userId, ListenableEntityType.TRACK, trackId);
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

    // targetType disambiguates ALBUM vs TRACK vs PLAYLIST within the single
    // LISTENED entity type — see ListenedSyncRetryHandler. Values are stored as
    // canonical Strings (see SyncFailure's payload contract).
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
        return listenRepository.existsById(new ListenId(userId, ListenableEntityType.ALBUM, albumId));
    }

    // Total plays across every user — the album editorial page's "LISTENINGS"
    // stat, not a per-user check.
    @Transactional(readOnly = true)
    public long countAlbumListens(UUID albumId) {
        return listenRepository.countByEntityTypeAndEntityIdIn(ListenableEntityType.ALBUM, List.of(albumId));
    }

    @Transactional(readOnly = true)
    public boolean hasListenedToTrack(UUID userId, UUID trackId) {
        return listenRepository.existsById(new ListenId(userId, ListenableEntityType.TRACK, trackId));
    }

    // Batch — AlbumService.getAlbumDetail needs this for every track on the
    // album at once, not one existsById per track.
    @Transactional(readOnly = true)
    public Set<UUID> getListenedTrackIds(UUID userId, List<UUID> trackIds) {
        return new HashSet<>(listenRepository.findListenedEntityIds(userId, ListenableEntityType.TRACK, trackIds));
    }

    @Transactional
    public void markPlaylistListened(UUID userId, UUID playlistId) {
        getPlaylistOrThrow(playlistId);
        boolean isNew = listenRepository.insertIfNotExists(userId, ListenableEntityType.PLAYLIST, playlistId);
        savedItemRepository.deleteByUserIdAndEntityTypeAndEntityId(userId, SaveableEntityType.PLAYLIST, playlistId);
        if (isNew) {
            syncPlaylistListenedToGraph(userId, playlistId);
        }
    }

    private void syncPlaylistListenedToGraph(UUID userId, UUID playlistId) {
        Instant listenedAt = Instant.now();
        syncExecutor.sync(
            SyncFailureEntityType.LISTENED,
            listenedPayload("PLAYLIST", userId, playlistId, listenedAt),
            () -> graphService.markPlaylistListened(userId, playlistId, listenedAt)
        );
    }

    /**
     * Idempotent — does nothing if the playlist wasn't marked as listened.
     * Postgres-only: unlike mark, this doesn't remove the Neo4j LISTENED edge —
     * no unmark exists for Album/Track either, and MERGE means a later re-mark
     * just overwrites listenedAt, so a stale edge here is harmless.
     */
    @Transactional
    public void unmarkPlaylistListened(UUID userId, UUID playlistId) {
        listenRepository.deleteByUserIdAndEntityTypeAndEntityId(userId, ListenableEntityType.PLAYLIST, playlistId);
    }

    @Transactional(readOnly = true)
    public boolean hasListenedToPlaylist(UUID userId, UUID playlistId) {
        return listenRepository.existsById(new ListenId(userId, ListenableEntityType.PLAYLIST, playlistId));
    }

    /**
     * Postgres-only, no Neo4j call at all — see class javadoc. This row IS the
     * "chapter completed" signal (no separate progress table, per SeriesService).
     */
    @Transactional
    public void markSeriesChapterListened(UUID userId, UUID chapterId) {
        getSeriesChapterOrThrow(chapterId);
        listenRepository.insertIfNotExists(userId, ListenableEntityType.SERIES_CHAPTER, chapterId);
    }

    @Transactional(readOnly = true)
    public boolean hasListenedToSeriesChapter(UUID userId, UUID chapterId) {
        return listenRepository.existsById(new ListenId(userId, ListenableEntityType.SERIES_CHAPTER, chapterId));
    }

    private Album getAlbumOrThrow(UUID albumId) {
        return albumRepository.findById(albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found: " + albumId));
    }

    private Track getTrackOrThrow(UUID trackId) {
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }

    private SeriesChapter getSeriesChapterOrThrow(UUID chapterId) {
        return seriesChapterRepository.findById(chapterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Series chapter not found: " + chapterId));
    }

    private Playlist getPlaylistOrThrow(UUID playlistId) {
        return playlistRepository.findById(playlistId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found: " + playlistId));
    }
}
