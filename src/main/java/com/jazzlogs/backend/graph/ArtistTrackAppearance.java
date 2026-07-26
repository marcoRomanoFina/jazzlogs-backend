package com.jazzlogs.backend.graph;

import java.util.UUID;

public record ArtistTrackAppearance(UUID trackId, String trackName, String role, String instrument, boolean primaryCredit) {
}
