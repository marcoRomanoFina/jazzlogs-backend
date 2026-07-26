package com.jazzlogs.backend.artist;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistRepository extends JpaRepository<Artist, UUID> {
}
