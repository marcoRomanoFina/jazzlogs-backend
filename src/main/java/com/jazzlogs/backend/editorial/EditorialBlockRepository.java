package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EditorialBlockRepository extends JpaRepository<EditorialBlock, UUID> {

    // editorialId traverses EditorialBlock.editorial.id — no JOIN to any
    // subclass table (album_editorials etc.): editorial_id on this table
    // already points straight at the editorials row EDITORIAL_CONTENT wants.
    List<EditorialBlock> findByEditorialIdOrderByPositionAsc(UUID editorialId);

    List<EditorialBlock> findByEditorialIdAndContentCategoryInOrderByPositionAsc(UUID editorialId, List<BlockContentCategory> categories);
}
