package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

/**
 * One artist's performance credit on a track, from Neo4j.
 *
 * @param artistId      the performing artist
 * @param artistName    the artist's name
 * @param role          how they performed (leader, sideman, ...)
 * @param instruments   what they played; empty if not recorded — an artist can be
 *                      credited on more than one instrument on the same track
 * @param primaryCredit whether this is their primary credit on the track
 */
public record TrackPerformerEntry(
    UUID artistId,
    String artistName,
    String role,
    List<String> instruments,
    boolean primaryCredit
) {
}
