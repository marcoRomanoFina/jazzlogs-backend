package com.jazzlogs.backend.album.dto;

import java.util.List;

// Full replace, not add-one — see StyleTagRequest's comment.
public record MoodTagRequest(List<String> moodCodes) {
}
