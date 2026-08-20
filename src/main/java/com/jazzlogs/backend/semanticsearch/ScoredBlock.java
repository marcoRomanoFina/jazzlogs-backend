package com.jazzlogs.backend.semanticsearch;

import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;

// similarityScore is cosine similarity (1 - cosine distance, see
// EditorialBlockRepository's semanticSearch* queries), higher = more
// similar — never combined/normalized against a graphFilter matchedDimensions
// count here or anywhere in this tool; that fusion, if any, is the LLM's job
// in its final synthesis, not this service's.
public record ScoredBlock(
    CatalogItemType entityType,
    UUID entityId,
    BlockContentCategory category,
    double similarityScore,
    String blockText,
    String entityName
) {
}
