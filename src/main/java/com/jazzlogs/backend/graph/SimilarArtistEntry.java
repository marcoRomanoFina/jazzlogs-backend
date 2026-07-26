package com.jazzlogs.backend.graph;

import java.util.UUID;

public record SimilarArtistEntry(UUID artistId, String name, String reason) {
}
