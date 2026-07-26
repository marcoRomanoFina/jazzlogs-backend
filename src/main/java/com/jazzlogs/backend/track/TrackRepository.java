package com.jazzlogs.backend.track;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackRepository extends JpaRepository<Track, UUID> {
}
