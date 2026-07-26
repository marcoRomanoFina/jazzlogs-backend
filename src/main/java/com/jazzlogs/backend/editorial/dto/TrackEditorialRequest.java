package com.jazzlogs.backend.editorial.dto;

import java.util.List;

public record TrackEditorialRequest(List<BlockRequest> blocks) {
}
