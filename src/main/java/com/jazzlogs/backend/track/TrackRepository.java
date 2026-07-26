package com.jazzlogs.backend.track;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jazzlogs.backend.saveditem.SavedItemResolver;

public interface TrackRepository extends JpaRepository<Track, UUID>, SavedItemResolver {

    @Override
    default Optional<Resolved> resolve(UUID entityId) {
        return findById(entityId).map(TrackRepository::toResolved);
    }

    @Override
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        return findAllById(entityIds).stream()
            .collect(Collectors.toMap(Track::getId, TrackRepository::toResolved));
    }

    private static Resolved toResolved(Track track) {
        return new Resolved(track.getName(), track.getImageUrl(), track.getSpotifyUrl());
    }
}
