package com.jazzlogs.backend.editorial.dto;

import java.util.List;

public record ArtistEditorialRequest(
    String dek,
    String byline,
    Integer readMinutes,
    List<BlockRequest> blocks
) {
}
