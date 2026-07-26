package com.jazzlogs.backend.album;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jazzlogs.backend.saveditem.SavedItemResolver;

public interface AlbumRepository extends JpaRepository<Album, UUID>, SavedItemResolver {

    @Override
    default Optional<Resolved> resolve(UUID entityId) {
        return findById(entityId).map(AlbumRepository::toResolved);
    }

    @Override
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        return findAllById(entityIds).stream()
            .collect(Collectors.toMap(Album::getId, AlbumRepository::toResolved));
    }

    private static Resolved toResolved(Album album) {
        return new Resolved(album.getName(), album.getImageUrl(), album.getSpotifyUrl());
    }
}
