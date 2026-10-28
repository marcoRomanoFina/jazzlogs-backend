package com.jazzlogs.backend.syncfailure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Reaps old RESOLVED sync_failures rows so the table doesn't grow forever.
 * DEAD rows are never touched here — those need a human to look at them.
 */
@Slf4j
@Component
public class SyncFailureCleanupJob {

    private final SyncFailureRepository syncFailureRepository;

    @Value("${sync-failure.cleanup.retention-days:30}")
    private int retentionDays;

    public SyncFailureCleanupJob(SyncFailureRepository syncFailureRepository) {
        this.syncFailureRepository = syncFailureRepository;
    }

    @Scheduled(fixedDelayString = "${sync-failure.cleanup.interval-ms:86400000}")
    @Transactional
    public void cleanupResolvedFailures() {
        Instant threshold = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long deleted = syncFailureRepository.deleteByStatusAndLastAttemptAtBefore(SyncFailureStatus.RESOLVED, threshold);
        if (deleted > 0) {
            log.info("Cleaned up {} resolved sync_failures rows older than {} days", deleted, retentionDays);
        }
    }
}
