package com.jazzlogs.backend.artist.dto;

import java.util.UUID;

public record SimilarArtistRequest(UUID similarArtistId, String reason, Boolean bidirectional) {
}
