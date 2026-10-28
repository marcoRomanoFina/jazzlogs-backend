package com.jazzlogs.backend.syncfailure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncFailureRepository extends JpaRepository<SyncFailure, UUID> {

    List<SyncFailure> findByStatus(SyncFailureStatus status);

    long deleteByStatusAndLastAttemptAtBefore(SyncFailureStatus status, Instant threshold);
}
