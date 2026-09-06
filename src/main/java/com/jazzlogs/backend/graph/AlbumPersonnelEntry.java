package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

/**
 * One artist's personnel credit across an album (aggregated over every
 * track they performed on), from Neo4j.
 *
 * @param artistId    the performing artist
 * @param artistName  the artist's name
 * @param role        how they performed (leader, sideman, ...)
 * @param instruments every instrument they're credited with anywhere on the album
 */
public record AlbumPersonnelEntry(UUID artistId, String artistName, String role, List<String> instruments) {
}
