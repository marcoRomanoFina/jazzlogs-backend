package com.jazzlogs.backend.track.dto;

import java.util.List;

// Full replace, not add-one — see StyleTagRequest's comment. Separate from
// InstrumentTagRequest, which stays singular: that one backs Artist's
// primaryInstrument (one at a time, by definition), this backs Track's
// featured-instruments tag (a set).
public record FeaturedInstrumentsRequest(List<String> instrumentCodes) {
}
