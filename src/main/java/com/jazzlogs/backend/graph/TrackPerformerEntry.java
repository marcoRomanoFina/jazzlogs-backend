package com.jazzlogs.backend.graph;

import java.util.UUID;

/**
 * One artist's performance credit on a track, from Neo4j.
 *
 * @param artistId      the performing artist
 * @param artistName    the artist's name
 * @param role          how they performed (leader, sideman, ...)
 * @param instrument    what they played; {@code null} if not recorded
 * @param primaryCredit whether this is their primary credit on the track
 */
public record TrackPerformerEntry(
    UUID artistId,
    String artistName,
    String role,
    String instrument,
    boolean primaryCredit
) {
}
