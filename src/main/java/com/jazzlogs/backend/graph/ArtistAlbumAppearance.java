package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.UUID;

public record ArtistAlbumAppearance(UUID albumId, String albumName, String role, List<String> instruments) {
}
