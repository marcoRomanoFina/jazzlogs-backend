package com.jazzlogs.backend.track.dto;

import java.util.List;

// Full replace, not add-one — see StyleTagRequest's comment.
public record RhythmTagRequest(List<String> rhythmCodes) {
}
