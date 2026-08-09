package com.jazzlogs.backend.album.dto;

import java.util.List;

// Full replace, not add-one: styleCodes is the complete set of styles this
// entity should have after the request — any style not in the list gets
// removed, see GraphService.replaceStyles/replaceArtistStyles.
public record StyleTagRequest(List<String> styleCodes) {
}
