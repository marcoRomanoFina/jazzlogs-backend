package com.jazzlogs.backend.syncfailure;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Periodically retries every PENDING sync_failures row. One transaction for
 * the whole batch — same "loop inside one @Transactional" shape as
 * ListenService.markAlbumListened looping over an album's tracks; a single bad
 * row's Neo4j failure is caught per-row below and never aborts the batch.
 */
@Slf4j
@Component
public class SyncRetryWorker {

    private final SyncFailureRepository syncFailureRepository;
    private final Map<SyncFailureEntityType, SyncRetryHandler> handlers;
    private final int maxAttempts;


    public SyncRetryWorker(
        SyncFailureRepository syncFailureRepository,
        ListenedSyncRetryHandler listenedHandler,
        ReviewRatedSyncRetryHandler reviewRatedHandler,
        ReviewHighlightedSyncRetryHandler reviewHighlightedHandler,
        TrackRatedSyncRetryHandler trackRatedHandler,
        PlaylistTrackAddedSyncRetryHandler playlistTrackAddedHandler,
        PlaylistTrackRemovedSyncRetryHandler playlistTrackRemovedHandler,
        PlaylistTracksReorderedSyncRetryHandler playlistTracksReorderedHandler,
        @Value("${sync-failure.max-attempts:5}") int maxAttempts
    ) {
        this.syncFailureRepository = syncFailureRepository;
        this.handlers = Map.of(
            SyncFailureEntityType.LISTENED, listenedHandler,
            SyncFailureEntityType.REVIEW_RATED, reviewRatedHandler,
            SyncFailureEntityType.REVIEW_HIGHLIGHTED, reviewHighlightedHandler,
            SyncFailureEntityType.TRACK_RATED, trackRatedHandler,
            SyncFailureEntityType.PLAYLIST_TRACK_ADDED, playlistTrackAddedHandler,
            SyncFailureEntityType.PLAYLIST_TRACK_REMOVED, playlistTrackRemovedHandler,
            SyncFailureEntityType.PLAYLIST_TRACKS_REORDERED, playlistTracksReorderedHandler
        );
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${sync-failure.retry-interval-ms:300000}")
    @Transactional
    public void retryPendingFailures() {
        List<SyncFailure> pending = syncFailureRepository.findByStatus(SyncFailureStatus.PENDING);
        for (SyncFailure failure : pending) {
            retryOne(failure);
        }
    }

    private void retryOne(SyncFailure failure) {
        SyncRetryHandler handler = handlers.get(failure.getEntityType());
        if (handler == null) {
        
            log.warn("No retry handler registered for sync failure type {} (id={})", failure.getEntityType(), failure.getId());
            return;
        }

        try {
            handler.retry(failure.getPayload());
            failure.markResolved();
        } catch (Exception ex) {
            log.warn("Retry failed for sync failure id={} type={}", failure.getId(), failure.getEntityType(), ex);
            failure.recordAttemptFailure(ex.getClass().getSimpleName() + ": " + ex.getMessage(), maxAttempts);
        }
        syncFailureRepository.save(failure);
    }
}
