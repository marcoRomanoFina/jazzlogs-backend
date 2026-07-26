package com.jazzlogs.backend.editorial;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackEditorialRepository extends JpaRepository<TrackEditorial, UUID> {

    Optional<TrackEditorial> findByTrackId(UUID trackId);
}
