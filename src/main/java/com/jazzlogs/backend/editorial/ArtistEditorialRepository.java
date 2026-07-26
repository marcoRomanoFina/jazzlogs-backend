package com.jazzlogs.backend.editorial;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistEditorialRepository extends JpaRepository<ArtistEditorial, UUID> {

    Optional<ArtistEditorial> findByArtistId(UUID artistId);
}
