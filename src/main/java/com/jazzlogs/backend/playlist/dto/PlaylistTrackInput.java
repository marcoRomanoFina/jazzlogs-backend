package com.jazzlogs.backend.playlist.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record PlaylistTrackInput(
    @NotNull UUID trackId,
    String title,
    String curatorNote
) {
}
