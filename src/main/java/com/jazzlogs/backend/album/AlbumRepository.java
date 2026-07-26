package com.jazzlogs.backend.album;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlbumRepository extends JpaRepository<Album, UUID> {
}
