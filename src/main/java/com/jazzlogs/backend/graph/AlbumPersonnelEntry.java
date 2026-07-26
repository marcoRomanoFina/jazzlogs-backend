package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

public record AlbumPersonnelEntry(UUID artistId, String artistName, String role, List<String> instruments) {
}
