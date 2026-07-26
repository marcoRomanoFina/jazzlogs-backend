package com.jazzlogs.backend.editorial;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EditorialBlockRepository extends JpaRepository<EditorialBlock, UUID> {
}
