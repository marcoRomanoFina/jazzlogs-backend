package com.jazzlogs.backend.track.dto;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.track.PerformanceRole;

public record PerformerRequest(UUID artistId, PerformanceRole role, List<String> instrumentCodes, boolean primaryCredit) {
}
