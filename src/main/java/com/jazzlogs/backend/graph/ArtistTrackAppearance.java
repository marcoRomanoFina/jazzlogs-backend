package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

public record ArtistTrackAppearance(UUID trackId, String trackName, String role, List<String> instruments, boolean primaryCredit) {
}
