package com.jazzlogs.backend.graph;

import java.util.UUID;

public record TrackPerformerEntry(
    UUID artistId,
    String artistName,
    String role,
    String instrument,
    boolean primaryCredit
) {
}
