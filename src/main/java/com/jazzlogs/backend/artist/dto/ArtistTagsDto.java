package com.jazzlogs.backend.artist.dto;

import java.util.List;

import com.jazzlogs.backend.graph.VocabularyTag;

/**
 * The artist's Neo4j-derived tags — not in the header ({@code
 * ArtistHeaderDto}). Similar artists and appearances (albums as
 * leader/sideman, individual tracks) get their own separate endpoints
 * instead of living here too.
 *
 * @param instruments instrument tags, from Neo4j
 * @param styles      style tags, from Neo4j
 * @param contexts    context tags, from Neo4j
 */
public record ArtistTagsDto(
    List<VocabularyTag> instruments,
    List<VocabularyTag> styles,
    List<VocabularyTag> contexts
) {
}
