package com.jazzlogs.backend.track.dto;

import java.util.UUID;

import com.jazzlogs.backend.track.PerformanceRole;

public record PerformerRequest(UUID artistId, PerformanceRole role, String instrumentCode, boolean primaryCredit) {
}
