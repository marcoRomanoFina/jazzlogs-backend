package com.jazzlogs.backend.editorial;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlbumEditorialRepository extends JpaRepository<AlbumEditorial, UUID> {

    Optional<AlbumEditorial> findByAlbumId(UUID albumId);
}
